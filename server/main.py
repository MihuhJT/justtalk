"""JustTalk server: HTTP for login and history, one WebSocket per client for live events.

Run:  .venv/bin/python main.py   (listens on 0.0.0.0:8000)
Android emulator reaches the Mac at http://10.0.2.2:8000, a real phone at http://<Mac LAN IP>:8000.
"""
from contextvars import ContextVar
import hashlib
import json
import mimetypes
import os
import random
import secrets
import sqlite3
import subprocess
import sys
import time
from collections import deque
from datetime import date, datetime
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel

BASE = Path(__file__).parent
DB_PATH = Path(os.environ.get("JT_DB_PATH", str(BASE / "justtalk.db")))
INVITE_PATH = BASE / "invite.txt"
# id of the app's creator: the only one allowed the short username "jt"
OWNER_PATH = BASE / "owner.txt"

app = FastAPI(title="JustTalk")
BOT_ORIGIN = ContextVar("bot_origin", default=None)

# ---------- flood guard (0.11.2) ----------
# Not a real DDoS shield (a home PC can't be one), but one script can no longer
# spam the server, guess the invite code or brute-force passwords.
REG_PER_IP_HOUR = 5     # register attempts from one address per hour, right or wrong invite
REG_PER_DAY = 10        # new accounts per day on the whole server
LOGIN_PER_IP_MIN = 10
REQ_PER_MIN = 300       # any requests from one account (or one address when logged out)
REQ_PER_IP_MIN = 900    # all requests from one address
WS_EVENTS_PER_10S = 40  # messages, reactions, typing... per socket
WS_PER_USER = 8         # open sockets of one account

hits: dict[tuple, deque] = {}


def hit(key, limit, window):
    """Sliding window counter: False when [key] already made [limit] hits in the last [window] seconds."""
    now = time.monotonic()
    q = hits.setdefault(key, deque())
    while q and q[0] <= now - window:
        q.popleft()
    if len(q) >= limit:
        return False
    q.append(now)
    return True


def client_ip(request):
    # through the tunnel every request comes from localhost; the real address is in the forwarded headers
    fwd = request.headers.get("x-forwarded-for") or request.headers.get("x-real-ip") or ""
    return fwd.split(",")[0].strip() or (request.client.host if request.client else "?")


def too_many(text):
    return JSONResponse({"detail": text}, status_code=429)


@app.middleware("http")
async def flood_guard(request: Request, call_next):
    path = request.url.path
    ip = client_ip(request)
    who = request.headers.get("authorization") or request.query_params.get("t") or ip
    # the admin panel and app downloads don't count
    if not path.startswith(("/admin/", "/app/")):
        # random fake tokens would each get their own counter, so the address has a (wider) limit too
        if not hit(("all", who), REQ_PER_MIN, 60) or not hit(("ip", ip), REQ_PER_IP_MIN, 60):
            return too_many("Слишком много запросов, подожди минуту")
        if path == "/register" and not hit(("reg", ip), REG_PER_IP_HOUR, 3600):
            return too_many("Слишком много попыток регистрации, попробуй через час")
        if path == "/login" and not hit(("login", ip), LOGIN_PER_IP_MIN, 60):
            return too_many("Слишком много попыток входа, подожди минуту")
    # forget idle counters so memory doesn't grow forever
    if len(hits) > 50_000:
        cutoff = time.monotonic() - 3600
        for k in [k for k, q in hits.items() if not q or q[-1] < cutoff]:
            del hits[k]
    return await call_next(request)


# ---------- storage ----------

class DBConnection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


def db():
    con = sqlite3.connect(DB_PATH, factory=DBConnection)
    con.row_factory = sqlite3.Row
    return con


def init_db():
    with db() as con:
        con.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            username TEXT NOT NULL UNIQUE,
            token TEXT NOT NULL UNIQUE,
            created INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS chats (
            id INTEGER PRIMARY KEY,
            kind TEXT NOT NULL,          -- 'dm' or 'group'
            title TEXT,                  -- groups only
            dm_key TEXT UNIQUE           -- 'minId:maxId' for dm, keeps one dm per pair
        );
        CREATE TABLE IF NOT EXISTS members (
            chat_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            last_read INTEGER NOT NULL DEFAULT 0,  -- id of the last message this user has read
            PRIMARY KEY (chat_id, user_id)
        );
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY,
            chat_id INTEGER NOT NULL,
            sender_id INTEGER NOT NULL,
            text TEXT NOT NULL,
            created INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS msg_chat ON messages(chat_id, id);
        """)
        # profile: "about" was added later, old databases get the column here
        cols = [r["name"] for r in con.execute("PRAGMA table_info(users)")]
        if "about" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN about TEXT NOT NULL DEFAULT ''")
        # photos: file name in media/ plus its size, so the phone can reserve space before loading
        cols = [r["name"] for r in con.execute("PRAGMA table_info(messages)")]
        if "photo" not in cols:
            con.execute("ALTER TABLE messages ADD COLUMN photo TEXT")
            con.execute("ALTER TABLE messages ADD COLUMN photo_w INTEGER NOT NULL DEFAULT 0")
            con.execute("ALTER TABLE messages ADD COLUMN photo_h INTEGER NOT NULL DEFAULT 0")
        # message actions: reply, edit, delete
        cols = [r["name"] for r in con.execute("PRAGMA table_info(messages)")]
        if "reply_to" not in cols:
            con.execute("ALTER TABLE messages ADD COLUMN reply_to INTEGER NOT NULL DEFAULT 0")
        if "edited" not in cols:
            con.execute("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        if "deleted" not in cols:
            con.execute("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        # chat menu: my own name for a chat, muted notifications, history cleared up to a message id
        cols = [r["name"] for r in con.execute("PRAGMA table_info(members)")]
        if "alias" not in cols:
            con.execute("ALTER TABLE members ADD COLUMN alias TEXT NOT NULL DEFAULT ''")
            con.execute("ALTER TABLE members ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
            con.execute("ALTER TABLE members ADD COLUMN cleared INTEGER NOT NULL DEFAULT 0")
        if "hidden" not in cols:
            # set by "Удалить переписку"/"Удалить кента": the chat stays out of my list until a new message
            con.execute("ALTER TABLE members ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            # chats cleared by v0.7 with nothing new since then stay hidden
            con.execute("""UPDATE members SET hidden = 1 WHERE cleared > 0 AND NOT EXISTS
                           (SELECT 1 FROM messages WHERE messages.chat_id = members.chat_id AND messages.id > members.cleared AND deleted = 0)""")
        # profile v2: avatar file in media/, optional birthday (ДД.ММ.ГГГГ) and gender ('m', 'f' or '')
        cols = [r["name"] for r in con.execute("PRAGMA table_info(users)")]
        if "avatar" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN avatar TEXT NOT NULL DEFAULT ''")
            con.execute("ALTER TABLE users ADD COLUMN birthday TEXT NOT NULL DEFAULT ''")
            con.execute("ALTER TABLE users ADD COLUMN gender TEXT NOT NULL DEFAULT ''")
        # for the Mac panel: which app version each kent runs and when he was last connected
        cols = [r["name"] for r in con.execute("PRAGMA table_info(users)")]
        if "app_version" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN app_version TEXT NOT NULL DEFAULT ''")
            con.execute("ALTER TABLE users ADD COLUMN last_seen INTEGER NOT NULL DEFAULT 0")
        # 0.9: Конфиденциальность → «Показывать в сети»; reg_id makes a retried /register return the same account
        cols = [r["name"] for r in con.execute("PRAGMA table_info(users)")]
        if "show_online" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN show_online INTEGER NOT NULL DEFAULT 1")
            con.execute("ALTER TABLE users ADD COLUMN reg_id TEXT NOT NULL DEFAULT ''")
        # 0.10: voice, circles and files (media = file name in media/), forwards, group avatars, pinned chats
        cols = [r["name"] for r in con.execute("PRAGMA table_info(messages)")]
        if "media" not in cols:
            for col in ("media TEXT", "media_kind TEXT NOT NULL DEFAULT ''", "media_name TEXT NOT NULL DEFAULT ''",
                        "media_size INTEGER NOT NULL DEFAULT 0", "duration INTEGER NOT NULL DEFAULT 0", "wave TEXT NOT NULL DEFAULT ''",
                        "thumb TEXT NOT NULL DEFAULT ''", "fwd_from INTEGER NOT NULL DEFAULT 0", "fwd_name TEXT NOT NULL DEFAULT ''"):
                con.execute(f"ALTER TABLE messages ADD COLUMN {col}")
        if "avatar" not in [r["name"] for r in con.execute("PRAGMA table_info(chats)")]:
            con.execute("ALTER TABLE chats ADD COLUMN avatar TEXT NOT NULL DEFAULT ''")
        if "owner_id" not in [r["name"] for r in con.execute("PRAGMA table_info(chats)")]:
            con.execute("ALTER TABLE chats ADD COLUMN owner_id INTEGER NOT NULL DEFAULT 0")
        if "pinned_message" not in [r["name"] for r in con.execute("PRAGMA table_info(chats)")]:
            con.execute("ALTER TABLE chats ADD COLUMN pinned_message INTEGER NOT NULL DEFAULT 0")
        if "pinned" not in [r["name"] for r in con.execute("PRAGMA table_info(members)")]:
            # 0 = not pinned, otherwise the moment it was pinned
            con.execute("ALTER TABLE members ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        con.executescript("""
        -- one reaction per person per message
        CREATE TABLE IF NOT EXISTS reactions (message_id INTEGER NOT NULL, user_id INTEGER NOT NULL, emoji TEXT NOT NULL, PRIMARY KEY (message_id, user_id));
        CREATE TABLE IF NOT EXISTS blocks (user_id INTEGER NOT NULL, blocked_id INTEGER NOT NULL, PRIMARY KEY (user_id, blocked_id));
        -- kents I removed: hidden from my list until I write to them again
        CREATE TABLE IF NOT EXISTS removed (user_id INTEGER NOT NULL, other_id INTEGER NOT NULL, PRIMARY KEY (user_id, other_id));
        -- 0.11: people I marked as кент (everyone else is просто знакомый); one-way, like a contact list
        CREATE TABLE IF NOT EXISTS friends (user_id INTEGER NOT NULL, other_id INTEGER NOT NULL, PRIMARY KEY (user_id, other_id));
        -- 0.11: places on the map; kind = a preset id or the owner's own category; photos = comma-separated names in media/
        -- vis: 'kents' = my kents see it, 'me' = only me
        CREATE TABLE IF NOT EXISTS spots (
            id INTEGER PRIMARY KEY,
            owner_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            kind TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL,
            about TEXT NOT NULL DEFAULT '',
            photos TEXT NOT NULL DEFAULT '',
            vis TEXT NOT NULL DEFAULT 'kents',
            created INTEGER NOT NULL
        );
        """)
        # 0.11: login by @username + password; too many wrong tries lock the account for a while
        cols = [r["name"] for r in con.execute("PRAGMA table_info(users)")]
        if "pass_hash" not in cols:
            con.execute("ALTER TABLE users ADD COLUMN pass_hash TEXT NOT NULL DEFAULT ''")
            con.execute("ALTER TABLE users ADD COLUMN login_fails INTEGER NOT NULL DEFAULT 0")
            con.execute("ALTER TABLE users ADD COLUMN locked_until INTEGER NOT NULL DEFAULT 0")
        # 0.11: "Позвать на спот" messages carry a copy of the spot (json), so the card works even for a spot the reader can't see
        if "spot" not in [r["name"] for r in con.execute("PRAGMA table_info(messages)")]:
            con.execute("ALTER TABLE messages ADD COLUMN spot TEXT NOT NULL DEFAULT ''")
        # 0.15: a live card in the chat (poll, gather, split, watch, call): '{"type": "poll", "id": 5}', state lives in its own table
        if "card" not in [r["name"] for r in con.execute("PRAGMA table_info(messages)")]:
            con.execute("ALTER TABLE messages ADD COLUMN card TEXT NOT NULL DEFAULT ''")
        con.executescript("""
        -- options = json list of strings; votes: one row per chosen option
        CREATE TABLE IF NOT EXISTS polls (
            id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, owner_id INTEGER NOT NULL, question TEXT NOT NULL,
            options TEXT NOT NULL, multi INTEGER NOT NULL DEFAULT 0, anon INTEGER NOT NULL DEFAULT 0, closed INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS poll_votes (poll_id INTEGER NOT NULL, user_id INTEGER NOT NULL, option INTEGER NOT NULL,
                                               PRIMARY KEY (poll_id, user_id, option));
        -- сбор: when (unix), where (a spot copy as json and/or free text); status per person: yes / maybe / no
        CREATE TABLE IF NOT EXISTS gathers (
            id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, owner_id INTEGER NOT NULL, title TEXT NOT NULL, at INTEGER NOT NULL,
            place TEXT NOT NULL DEFAULT '', spot TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
            cancelled INTEGER NOT NULL DEFAULT 0, reminded INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS gather_rsvp (gather_id INTEGER NOT NULL, user_id INTEGER NOT NULL, status TEXT NOT NULL,
                                                PRIMARY KEY (gather_id, user_id));
        -- скидываемся: the owner paid (or collects), everyone owes a share in whole rubles
        CREATE TABLE IF NOT EXISTS splits (
            id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, owner_id INTEGER NOT NULL, title TEXT NOT NULL, total INTEGER NOT NULL,
            details TEXT NOT NULL DEFAULT '', closed INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS split_shares (split_id INTEGER NOT NULL, user_id INTEGER NOT NULL, amount INTEGER NOT NULL,
                                                 paid INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (split_id, user_id));
        -- смотреть вместе: source youtube / vk / file; playback state = playing, position (s) at server time "at"
        CREATE TABLE IF NOT EXISTS watches (
            id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, owner_id INTEGER NOT NULL, source TEXT NOT NULL, src TEXT NOT NULL,
            title TEXT NOT NULL DEFAULT '', playing INTEGER NOT NULL DEFAULT 0, pos REAL NOT NULL DEFAULT 0, at REAL NOT NULL DEFAULT 0,
            ended INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL
        );
        -- voice calls: the audio itself never touches the disk, this is only the history line
        CREATE TABLE IF NOT EXISTS calls (
            id INTEGER PRIMARY KEY, chat_id INTEGER NOT NULL, owner_id INTEGER NOT NULL, started INTEGER NOT NULL,
            answered INTEGER NOT NULL DEFAULT 0, ended INTEGER NOT NULL DEFAULT 0, people TEXT NOT NULL DEFAULT ''
        );
        -- «Я тут»: one place per person, gone after "until"
        CREATE TABLE IF NOT EXISTS checkins (user_id INTEGER PRIMARY KEY, spot_id INTEGER NOT NULL, at INTEGER NOT NULL, until INTEGER NOT NULL);
        """)


def invite_code():
    # one shared 9-digit code for the whole friend group, made on first start
    if not INVITE_PATH.exists():
        INVITE_PATH.write_text("".join(random.choice("0123456789") for _ in range(9)))
    return INVITE_PATH.read_text().strip()


def owner_id():
    try:
        return int(OWNER_PATH.read_text().strip())
    except (OSError, ValueError):
        return 0


def user_json(row):
    # someone who hides "в сети" is never shown online and has no "был в сети" time
    shown = bool(row["show_online"])
    return {"id": row["id"], "name": row["name"], "username": row["username"], "about": row["about"],
            "avatar": row["avatar"], "birthday": row["birthday"], "gender": row["gender"],
            "online": shown and is_active(row["id"]), "last_seen": row["last_seen"] if shown else 0}


def me_json(row):
    # only my own profile says whether a password is set: the app asks for one after the update
    return {**user_json(row), "has_password": bool(row["pass_hash"])}


def blocked_between(con, a, b):
    return con.execute("SELECT 1 FROM blocks WHERE (user_id = ? AND blocked_id = ?) OR (user_id = ? AND blocked_id = ?)",
                       (a, b, b, a)).fetchone() is not None


def dm_block_error(con, chat_id, me_id):
    """Why I can't write to this dm, or None. Groups are never blocked."""
    chat = con.execute("SELECT kind FROM chats WHERE id = ?", (chat_id,)).fetchone()
    if not chat or chat["kind"] != "dm":
        return None
    other = next((i for i in member_ids(con, chat_id) if i != me_id), me_id)
    if con.execute("SELECT 1 FROM blocks WHERE user_id = ? AND blocked_id = ?", (me_id, other)).fetchone():
        return "Ты заблокировал этого кента"
    if con.execute("SELECT 1 FROM blocks WHERE user_id = ? AND blocked_id = ?", (other, me_id)).fetchone():
        return "Кент ограничил тебе сообщения"
    return None


def reactions_of(con, message_id):
    out = {}
    for r in con.execute("SELECT emoji, user_id FROM reactions WHERE message_id = ? ORDER BY rowid", (message_id,)):
        out.setdefault(r["emoji"], []).append(r["user_id"])
    return out


def media_label(row):
    # what a quote or a chat list line says about a message without text
    if row["card"]:
        return card_label(row["card"])
    if row["spot"]:
        return "📍 " + json.loads(row["spot"]).get("title", "Спот")
    return {"voice": "🎤 Голосовое", "circle": "⏺ Кружок", "file": "📎 " + (row["media_name"] or "Файл")}.get(row["media_kind"], "")


def msg_json(row, con=None, viewer=0):
    out = {"id": row["id"], "chat_id": row["chat_id"], "sender_id": row["sender_id"],
           "text": row["text"], "created": row["created"],
           "reply_to": row["reply_to"], "edited": bool(row["edited"]),
           "photo": row["photo"], "photo_w": row["photo_w"], "photo_h": row["photo_h"],
           "media": row["media"], "media_kind": row["media_kind"], "media_name": row["media_name"], "media_size": row["media_size"],
           "duration": row["duration"], "wave": row["wave"], "thumb": row["thumb"],
           "fwd_from": row["fwd_from"], "fwd_name": row["fwd_name"],
           "spot": json.loads(row["spot"]) if row["spot"] else None}
    if con is not None:
        out["bot"] = bot.metadata(con, row["id"], viewer)
        out["reactions"] = reactions_of(con, row["id"])
    # 0.15: the card's current state; an anonymous poll differs per reader (only my own votes are shown as mine)
    if con is not None and row["card"]:
        out["card"] = card_json(con, row["card"], viewer)
    # short copy of the quoted message, for when the client hasn't loaded the original
    if con is not None and row["reply_to"]:
        q = con.execute("SELECT * FROM messages WHERE id = ?", (row["reply_to"],)).fetchone()
        if q:
            out["reply"] = {"id": q["id"], "sender_id": q["sender_id"], "text": q["text"][:120] or ("📷 Фото" if q["photo"] else media_label(q)),
                            "deleted": bool(q["deleted"])}
    return out


def own_message(con, chat_id, user_id, ev):
    # edit/delete: only my own, not yet deleted message in this chat
    return con.execute("SELECT * FROM messages WHERE id = ? AND chat_id = ? AND sender_id = ? AND deleted = 0",
                       (int(ev.get("message_id", 0)), chat_id, user_id)).fetchone()


def auth(token):
    with db() as con:
        row = con.execute("SELECT * FROM users WHERE token = ?", (token or "",)).fetchone()
    if not row:
        raise HTTPException(401, "bad token")
    return row


def member_ids(con, chat_id):
    return [r["user_id"] for r in con.execute("SELECT user_id FROM members WHERE chat_id = ?", (chat_id,))]


def chat_json(con, chat_id, me_id):
    chat = con.execute("SELECT * FROM chats WHERE id = ?", (chat_id,)).fetchone()
    members = [user_json(r) for r in con.execute(
        "SELECT u.* FROM users u JOIN members m ON m.user_id = u.id WHERE m.chat_id = ?", (chat_id,))]
    mine = con.execute("SELECT * FROM members WHERE chat_id = ? AND user_id = ?", (chat_id, me_id)).fetchone()
    # messages up to "cleared" are gone for me after "Удалить переписку"
    last = con.execute("SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND id > ? ORDER BY id DESC LIMIT 1",
                       (chat_id, mine["cleared"])).fetchone()
    unread = con.execute("SELECT COUNT(*) FROM messages WHERE chat_id = ? AND id > ? AND sender_id NOT IN (?, 0) AND deleted = 0",
                         (chat_id, max(mine["last_read"], mine["cleared"]), me_id)).fetchone()[0]
    # how far the others have read: drives the double tick on my messages
    others_read = con.execute("SELECT MIN(last_read) FROM members WHERE chat_id = ? AND user_id != ?",
                              (chat_id, me_id)).fetchone()[0] or 0
    if chat["kind"] == "dm":
        other = next((m for m in members if m["id"] != me_id), members[0])
        title = other["name"]
    else:
        title = chat["title"]
    has_history = bool(con.execute("SELECT 1 FROM messages WHERE chat_id=? AND sender_id>0 LIMIT 1", (chat_id,)).fetchone()) if chat["kind"] == "dm" else False
    return {"id": chat_id, "kind": chat["kind"], "title": mine["alias"] or title, "alias": mine["alias"], "muted": bool(mine["muted"]),
            "hidden": bool(mine["hidden"]), "members": members, "avatar": chat["avatar"], "pinned": mine["pinned"], "pinned_message": chat["pinned_message"], "owner_id": chat["owner_id"], "has_history": has_history,
            "last": msg_json(last, con, me_id) if last else None, "unread": unread, "others_read": others_read}


# ---------- http ----------

class RegisterIn(BaseModel):
    invite: str
    name: str
    username: str
    # random id of one login attempt: if the answer got lost on the way, the retry gets the same account
    request_id: str = ""


def reserved(u):
    # names that look like the app itself: jt, jt_official, just_talk, justtalk2 ...
    flat = "".join(c for c in u if c.isalpha())
    return "jt" in flat or any(w in flat for w in ("justtalk", "justtolk", "justalk"))


def valid_username(u, user_id=0):
    # "jt" is the creator's: two letters, shown bold and highlighted in the app
    if u == "jt":
        return user_id != 0 and user_id == owner_id()
    if reserved(u):
        return False
    return 3 <= len(u) <= 20 and all(c.isascii() and (c.isalnum() or c == "_") for c in u)


def username_error(u, user_id=0):
    if reserved(u) and not valid_username(u, user_id):
        return "Ники с JT и JustTalk заняты приложением"
    if not valid_username(u, user_id):
        return "Юзернейм: 3–20 символов, латиница, цифры и _"
    return None


@app.post("/register")
def register(body: RegisterIn):
    if body.invite.strip() != invite_code():
        raise HTTPException(403, "Неверный код приглашения")
    name = body.name.strip()
    username = body.username.strip().lower().lstrip("@")
    if not name:
        raise HTTPException(400, "Нужно имя")
    if why := username_error(username):
        raise HTTPException(400, why)
    rid = body.request_id.strip()[:64]
    if rid:
        with db() as con:
            row = con.execute("SELECT * FROM users WHERE reg_id = ? AND username = ?", (rid, username)).fetchone()
        if row:
            return {"token": row["token"], "user": me_json(row)}
    with db() as con:
        day = con.execute("SELECT COUNT(*) FROM users WHERE created > ?", (int(time.time()) - 86400,)).fetchone()[0]
    if day >= REG_PER_DAY:
        raise HTTPException(429, "Сегодня регистраций уже слишком много, попробуй завтра")
    token = secrets.token_hex(24)
    try:
        with db() as con:
            cur = con.execute("INSERT INTO users (name, username, token, created, reg_id) VALUES (?, ?, ?, ?, ?)",
                              (name, username, token, int(time.time()), rid))
            row = con.execute("SELECT * FROM users WHERE id = ?", (cur.lastrowid,)).fetchone()
    except sqlite3.IntegrityError:
        raise HTTPException(409, f"@{username} уже занят")
    return {"token": token, "user": me_json(row)}


@app.get("/me")
def me(authorization: str = Header("")):
    return me_json(auth(authorization))


# ---------- profile ----------

@app.get("/username/{username}")
def username_free(username: str, authorization: str = Header("")):
    me_row = auth(authorization)
    u = username.strip().lower().lstrip("@")
    with db() as con:
        row = con.execute("SELECT id FROM users WHERE username = ?", (u,)).fetchone()
    # my own current username counts as free
    valid = valid_username(u, me_row["id"])
    # reserved names (jt, justtalk...) look taken to everyone but the creator
    return {"username": u, "valid": valid, "free": (not row or row["id"] == me_row["id"]) and (valid or not reserved(u)),
            "error": username_error(u, me_row["id"])}


class ProfileIn(BaseModel):
    name: str
    username: str
    about: str = ""
    # None = old app that doesn't know these fields: keep what is saved
    birthday: str | None = None
    gender: str | None = None


def clean_birthday(b):
    b = b.strip()
    if not b:
        return ""
    try:
        d = datetime.strptime(b, "%d.%m.%Y").date()
    except ValueError:
        raise HTTPException(400, "Дата рождения: ДД.ММ.ГГГГ")
    if not 1920 <= d.year or d > date.today():
        raise HTTPException(400, "Странная дата рождения")
    return d.strftime("%d.%m.%Y")


@app.post("/me")
async def update_me(body: ProfileIn, authorization: str = Header("")):
    me_row = auth(authorization)
    name = body.name.strip()[:40]
    username = body.username.strip().lower().lstrip("@")
    about = body.about.strip()[:140]
    if not name:
        raise HTTPException(400, "Нужно имя")
    # an old reserved-looking name may stay, a new one may not
    if username != me_row["username"] and (why := username_error(username, me_row["id"])):
        raise HTTPException(409 if reserved(username) else 400, why)
    birthday = me_row["birthday"] if body.birthday is None else clean_birthday(body.birthday)
    gender = me_row["gender"] if body.gender is None else (body.gender if body.gender in ("m", "f") else "")
    try:
        with db() as con:
            con.execute("UPDATE users SET name = ?, username = ?, about = ?, birthday = ?, gender = ? WHERE id = ?",
                        (name, username, about, birthday, gender, me_row["id"]))
            row = con.execute("SELECT * FROM users WHERE id = ?", (me_row["id"],)).fetchone()
    except sqlite3.IntegrityError:
        raise HTTPException(409, f"@{username} уже занят")
    # everyone online reloads chats and users to see the new name
    await push(list(online), {"type": "chat"})
    return me_json(row)


@app.get("/users")
def users(authorization: str = Header("")):
    # only people I share a chat with; everyone else is found through /users/search
    me_row = auth(authorization)
    with db() as con:
        rows = con.execute("""SELECT DISTINCT u.* FROM users u JOIN members m ON m.user_id = u.id
                              WHERE u.id != ? AND m.chat_id IN (SELECT chat_id FROM members WHERE user_id = ?) ORDER BY u.name""",
                           (me_row["id"], me_row["id"])).fetchall()
        gone = {r["other_id"] for r in con.execute("SELECT other_id FROM removed WHERE user_id = ?", (me_row["id"],))}
        kents = friend_ids(con, me_row["id"])
        corresponded = {r["user_id"] for r in con.execute("""SELECT DISTINCT m.user_id FROM members m JOIN chats c ON c.id=m.chat_id
            WHERE c.kind='dm' AND m.user_id != ? AND EXISTS(SELECT 1 FROM members self WHERE self.chat_id=c.id AND self.user_id=?)
            AND EXISTS(SELECT 1 FROM messages msg WHERE msg.chat_id=c.id AND msg.sender_id>0)""", (me_row["id"], me_row["id"]))}
    return [{**user_json(r), "removed": r["id"] in gone, "kent": r["id"] in kents,
             "corresponded": r["id"] in corresponded} for r in rows]


@app.get("/users/search")
def search_users(q: str = "", authorization: str = Header("")):
    # by the start of the @username, at least one letter: nobody can list the whole app
    me_row = auth(authorization)
    q = q.strip().lower().lstrip("@")
    if not q:
        return []
    like = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    with db() as con:
        rows = con.execute("SELECT * FROM users WHERE id != ? AND username LIKE ? ESCAPE '\\' ORDER BY username LIMIT 20",
                           (me_row["id"], like)).fetchall()
    return [user_json(r) for r in rows]


@app.post("/me/avatar")
async def set_avatar(request: Request, authorization: str = Header("")):
    # raw JPEG like chat photos; the phone already cropped and shrank it
    me_row = auth(authorization)
    data = await request.body()
    if not data or len(data) > MAX_PHOTO or not data.startswith(b"\xff\xd8"):
        raise HTTPException(400, "Нужен JPEG до 15 МБ")
    name = secrets.token_hex(16) + ".jpg"
    (MEDIA / name).write_bytes(data)
    with db() as con:
        con.execute("UPDATE users SET avatar = ? WHERE id = ?", (name, me_row["id"]))
        row = con.execute("SELECT * FROM users WHERE id = ?", (me_row["id"],)).fetchone()
    await push(list(online), {"type": "chat"})
    return me_json(row)


@app.delete("/me/avatar")
async def drop_avatar(authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        con.execute("UPDATE users SET avatar = '' WHERE id = ?", (me_row["id"],))
        row = con.execute("SELECT * FROM users WHERE id = ?", (me_row["id"],)).fetchone()
    await push(list(online), {"type": "chat"})
    return me_json(row)


@app.get("/chats")
def chats(authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        ids = [r["chat_id"] for r in con.execute("SELECT chat_id FROM members WHERE user_id = ?", (me_row["id"],))]
        # a cleared chat leaves the list until a new message arrives
        result = [c for c in (chat_json(con, i, me_row["id"]) for i in ids) if not c["hidden"]]
    # pinned on top (latest pinned first), then newest activity; empty chats go last
    result.sort(key=lambda c: (c["pinned"], c["last"]["id"] if c["last"] else 0), reverse=True)
    return result


class DmIn(BaseModel):
    user_id: int


@app.post("/chats/dm")
def open_dm(body: DmIn, authorization: str = Header("")):
    me_row = auth(authorization)
    a, b = sorted([me_row["id"], body.user_id])
    key = f"{a}:{b}"
    with db() as con:
        if not con.execute("SELECT 1 FROM users WHERE id = ?", (body.user_id,)).fetchone():
            raise HTTPException(404, "Нет такого кента")
        row = con.execute("SELECT id FROM chats WHERE dm_key = ?", (key,)).fetchone()
        if row:
            chat_id = row["id"]
        else:
            chat_id = con.execute("INSERT INTO chats (kind, dm_key) VALUES ('dm', ?)", (key,)).lastrowid
            con.executemany("INSERT INTO members (chat_id, user_id) VALUES (?, ?)", [(chat_id, a), (chat_id, b)])
        # writing to a removed kent brings him back
        con.execute("DELETE FROM removed WHERE user_id = ? AND other_id = ?", (me_row["id"], body.user_id))
        con.execute("UPDATE members SET hidden = 0 WHERE chat_id = ? AND user_id = ?", (chat_id, me_row["id"]))
        return chat_json(con, chat_id, me_row["id"])


@app.get("/chats/{chat_id}/messages")
def messages(chat_id: int, before: int = 0, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        if me_row["id"] not in member_ids(con, chat_id):
            raise HTTPException(403, "Не твой чат")
        cleared = con.execute("SELECT cleared FROM members WHERE chat_id = ? AND user_id = ?", (chat_id, me_row["id"])).fetchone()[0]
        rows = con.execute(
            "SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND id > ? AND (? = 0 OR id < ?) ORDER BY id DESC LIMIT 100",
            (chat_id, cleared, before, before)).fetchall()
        return [msg_json(r, con, me_row["id"]) for r in reversed(rows)]


# ---------- photos ----------

MEDIA = BASE / "media"
MEDIA.mkdir(exist_ok=True)
MAX_PHOTO = 15 * 1024 * 1024


@app.post("/chats/{chat_id}/photo")
async def send_photo(chat_id: int, request: Request, w: int = 0, h: int = 0, caption: str = "", client_id: str = "",
                     authorization: str = Header("")):
    # the body is the raw JPEG: the phone already shrank it, no multipart needed
    me_row = auth(authorization)
    data = await request.body()
    if not data or len(data) > MAX_PHOTO:
        raise HTTPException(400, "Фото пустое или больше 15 МБ")
    if not data.startswith(b"\xff\xd8"):
        raise HTTPException(400, "Нужен JPEG")
    with db() as con:
        ids = member_ids(con, chat_id)
        if me_row["id"] not in ids:
            raise HTTPException(403, "Не твой чат")
        check_channel_post(con, chat_id, me_row["id"])
        if why := dm_block_error(con, chat_id, me_row["id"]):
            raise HTTPException(403, why)
        # random name: links can't be guessed, so files are served without a token
        name = secrets.token_hex(16) + ".jpg"
        (MEDIA / name).write_bytes(data)
        cur = con.execute(
            "INSERT INTO messages (chat_id, sender_id, text, created, photo, photo_w, photo_h) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (chat_id, me_row["id"], caption.strip()[:4000], int(time.time()), name, max(w, 0), max(h, 0)))
        con.execute("UPDATE members SET last_read = ? WHERE chat_id = ? AND user_id = ?",
                    (cur.lastrowid, chat_id, me_row["id"]))
        con.execute("UPDATE members SET hidden = 0 WHERE chat_id = ?", (chat_id,))
        msg = msg_json(con.execute("SELECT * FROM messages WHERE id = ?", (cur.lastrowid,)).fetchone())
    await push(ids, {"type": "message", "message": msg, "client_id": client_id})
    return msg


# "on" = files open only with a kent's token. Off until everyone runs 0.11.2+, older apps load files without one.
MEDIA_AUTH_PATH = BASE / "media_auth.txt"


@app.get("/media/{name}")
def media(name: str, t: str = "", authorization: str = Header("")):
    token = authorization or t
    if token:
        auth(token)
    elif MEDIA_AUTH_PATH.exists() and MEDIA_AUTH_PATH.read_text().strip() == "on":
        raise HTTPException(401, "bad token")
    path = MEDIA / Path(name).name
    if not path.is_file():
        raise HTTPException(404, "Нет файла")
    # names never change, so phones may cache them forever
    kind = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return FileResponse(path, media_type=kind, headers={"Cache-Control": "private, max-age=31536000, immutable"})


# ---------- voice, circles, files ----------

MAX_FILE = 100 * 1024 * 1024
# Windows and macOS guess odd types for these; players want the plain ones
mimetypes.add_type("audio/mp4", ".m4a")
mimetypes.add_type("video/mp4", ".mp4")


@app.post("/upload")
async def upload(request: Request, ext: str = "bin", big: int = 0, authorization: str = Header("")):
    """Raw body straight to disk, up to 100 MB; the message itself comes after with POST /chats/{id}/media."""
    auth(authorization)
    ext = "".join(c for c in ext.lower() if c.isalnum())[:8] or "bin"
    name = f"{secrets.token_hex(16)}.{ext}"
    path = MEDIA / name
    size = 0
    # 0.15: a video for "Смотреть вместе" may be up to 1 GB
    limit = MAX_WATCH_FILE if big and ext in ("mp4", "mkv", "webm", "mov", "3gp") else MAX_FILE
    with path.open("wb") as f:
        async for chunk in request.stream():
            size += len(chunk)
            if size > limit:
                f.close()
                path.unlink(missing_ok=True)
                raise HTTPException(413, "Файл больше 1 ГБ" if limit == MAX_WATCH_FILE else "Файл больше 100 МБ")
            f.write(chunk)
    if size == 0:
        path.unlink(missing_ok=True)
        raise HTTPException(400, "Пустой файл")
    return {"name": name, "size": size}


class MediaIn(BaseModel):
    kind: str            # voice, circle or file
    media: str           # name from /upload
    name: str = ""       # original file name, for files
    duration: int = 0    # ms, voice and circles
    wave: str = ""       # voice: loudness bars as digits 0-9
    thumb: str = ""      # circle: first frame (jpeg from /upload)
    caption: str = ""
    client_id: str = ""
    reply_to: int = 0


@app.post("/chats/{chat_id}/media")
async def send_media(chat_id: int, body: MediaIn, authorization: str = Header("")):
    me_row = auth(authorization)
    if body.kind not in ("voice", "circle", "file"):
        raise HTTPException(400, "Неизвестный тип")
    path = MEDIA / Path(body.media).name
    if not path.is_file():
        raise HTTPException(400, "Файл не загрузился")
    thumb = Path(body.thumb).name if body.thumb and (MEDIA / Path(body.thumb).name).is_file() else ""
    with db() as con:
        ids = member_ids(con, chat_id)
        if me_row["id"] not in ids:
            raise HTTPException(403, "Не твой чат")
        check_channel_post(con, chat_id, me_row["id"])
        if why := dm_block_error(con, chat_id, me_row["id"]):
            raise HTTPException(403, why)
        reply_to = body.reply_to if body.reply_to and con.execute(
            "SELECT 1 FROM messages WHERE id = ? AND chat_id = ?", (body.reply_to, chat_id)).fetchone() else 0
        cur = con.execute(
            """INSERT INTO messages (chat_id, sender_id, text, created, reply_to, media, media_kind, media_name, media_size, duration, wave, thumb)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (chat_id, me_row["id"], body.caption.strip()[:4000], int(time.time()), reply_to, path.name, body.kind,
             body.name.strip()[:200], path.stat().st_size, max(body.duration, 0), body.wave[:200], thumb))
        con.execute("UPDATE members SET last_read = ? WHERE chat_id = ? AND user_id = ?", (cur.lastrowid, chat_id, me_row["id"]))
        con.execute("UPDATE members SET hidden = 0 WHERE chat_id = ?", (chat_id,))
        msg = msg_json(con.execute("SELECT * FROM messages WHERE id = ?", (cur.lastrowid,)).fetchone(), con)
    await push(ids, {"type": "message", "message": msg, "client_id": body.client_id})
    return msg


@app.get("/chats/{chat_id}/search")
def search_messages(chat_id: int, q: str = "", authorization: str = Header("")):
    # SQLite's LIKE ignores case only for latin letters, so Russian is matched here in Python
    me_row = auth(authorization)
    needle = q.strip().casefold()
    if not needle:
        return []
    with db() as con:
        if me_row["id"] not in member_ids(con, chat_id):
            raise HTTPException(403, "Не твой чат")
        cleared = con.execute("SELECT cleared FROM members WHERE chat_id = ? AND user_id = ?", (chat_id, me_row["id"])).fetchone()[0]
        rows = con.execute("SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND sender_id != 0 AND id > ? ORDER BY id DESC",
                           (chat_id, cleared)).fetchall()
        found = [r for r in rows if needle in (r["text"] + " " + r["media_name"]).casefold()][:50]
        return [msg_json(r) for r in found]


# ---------- app updates ----------

APP_DIR = BASE / "app"
APP_DIR.mkdir(exist_ok=True)


@app.get("/app/latest")
def app_latest():
    # app/latest.json: {"version": "0.10", "code": 10, "file": "JustTalk-0.10.apk", "notes": "..."}
    try:
        info = json.loads((APP_DIR / "latest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"code": 0}
    return {**info, "path": f"/app/{info.get('file', '')}"}


@app.get("/app/{name}")
def app_file(name: str):
    path = APP_DIR / Path(name).name
    if path.suffix != ".apk" or not path.is_file():
        raise HTTPException(404, "Нет файла")
    return FileResponse(path, media_type="application/vnd.android.package-archive", filename=path.name)


# ---------- groups ----------

def system_message(con, chat_id, text):
    # sender_id 0 = service line like "Рома добавил Дэна", drawn centered in the chat
    cur = con.execute("INSERT INTO messages (chat_id, sender_id, text, created) VALUES (?, 0, ?, ?)",
                      (chat_id, text, int(time.time())))
    return msg_json(con.execute("SELECT * FROM messages WHERE id = ?", (cur.lastrowid,)).fetchone())


def names(con, ids):
    rows = con.execute(f"SELECT name FROM users WHERE id IN ({','.join('?' * len(ids))})", ids).fetchall()
    return ", ".join(r["name"] for r in rows)


def check_member(con, chat_id, user_id):
    chat = con.execute("SELECT * FROM chats WHERE id = ?", (chat_id,)).fetchone()
    if not chat or user_id not in member_ids(con, chat_id):
        raise HTTPException(403, "Не твой чат")
    if chat["kind"] not in ("group", "channel"):
        raise HTTPException(400, "Это не группа")
    if chat["kind"] == "channel" and chat["owner_id"] != user_id:
        raise HTTPException(403, "Каналом управляет создатель")


def check_channel_post(con, chat_id, user_id):
    chat = con.execute("SELECT kind, owner_id FROM chats WHERE id=?", (chat_id,)).fetchone()
    if chat and chat["kind"] == "channel" and chat["owner_id"] != user_id:
        raise HTTPException(403, "Публиковать в канале может только создатель")


def existing_users(con, ids):
    ids = list(dict.fromkeys(int(i) for i in ids))
    found = [r["id"] for r in con.execute(f"SELECT id FROM users WHERE id IN ({','.join('?' * len(ids))})", ids)] if ids else []
    return found


class GroupIn(BaseModel):
    title: str
    user_ids: list[int]


@app.post("/chats/group")
async def create_group(body: GroupIn, authorization: str = Header("")):
    me_row = auth(authorization)
    title = body.title.strip()[:60]
    if not title:
        raise HTTPException(400, "Нужно название группы")
    with db() as con:
        others = [i for i in existing_users(con, body.user_ids) if i != me_row["id"]]
        if not others:
            raise HTTPException(400, "Выбери хотя бы одного кента")
        chat_id = con.execute("INSERT INTO chats (kind, title) VALUES ('group', ?)", (title,)).lastrowid
        everyone = [me_row["id"]] + others
        con.executemany("INSERT INTO members (chat_id, user_id) VALUES (?, ?)", [(chat_id, u) for u in everyone])
        msg = system_message(con, chat_id, f"{me_row['name']} создал группу «{title}»")
        result = chat_json(con, chat_id, me_row["id"])
    await push(everyone, {"type": "chat", "chat_id": chat_id})
    await push(everyone, {"type": "message", "message": msg})
    return result


class PinMessageIn(BaseModel):
    message_id: int = 0


@app.post("/chats/{chat_id}/pin-message")
async def pin_message(chat_id: int, body: PinMessageIn, authorization: str = Header("")):
    user = auth(authorization)
    with db() as con:
        ids = member_ids(con, chat_id)
        if user["id"] not in ids:
            raise HTTPException(403, "Не твой чат")
        check_channel_post(con, chat_id, user["id"])
        if body.message_id and not con.execute("SELECT 1 FROM messages WHERE id=? AND chat_id=? AND deleted=0", (body.message_id, chat_id)).fetchone():
            raise HTTPException(404, "Сообщение не найдено")
        con.execute("UPDATE chats SET pinned_message=? WHERE id=?", (body.message_id, chat_id))
    await push(ids, {"type": "chat", "chat_id": chat_id})
    return {"ok": True}


class ChannelIn(BaseModel):
    title: str
    user_ids: list[int] = []


@app.post("/chats/channel")
async def create_channel(body: ChannelIn, authorization: str = Header("")):
    user = auth(authorization)
    title = body.title.strip()[:60]
    if not title:
        raise HTTPException(400, "Нужно название канала")
    with db() as con:
        others = [i for i in existing_users(con, body.user_ids) if i != user["id"]]
        chat_id = con.execute("INSERT INTO chats (kind, title, owner_id) VALUES ('channel', ?, ?)", (title, user["id"])).lastrowid
        everyone = [user["id"]] + others
        con.executemany("INSERT INTO members (chat_id, user_id) VALUES (?, ?)", [(chat_id, i) for i in everyone])
        result = chat_json(con, chat_id, user["id"])
    await push(everyone, {"type": "chat", "chat_id": chat_id})
    return result


class MembersIn(BaseModel):
    user_ids: list[int]


@app.post("/chats/{chat_id}/members")
async def add_members(chat_id: int, body: MembersIn, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        check_member(con, chat_id, me_row["id"])
        current = member_ids(con, chat_id)
        new = [i for i in existing_users(con, body.user_ids) if i not in current]
        if not new:
            raise HTTPException(400, "Они уже в группе")
        con.executemany("INSERT INTO members (chat_id, user_id) VALUES (?, ?)", [(chat_id, u) for u in new])
        msg = system_message(con, chat_id, f"{me_row['name']} добавил: {names(con, new)}")
        result = chat_json(con, chat_id, me_row["id"])
    everyone = current + new
    await push(everyone, {"type": "chat", "chat_id": chat_id})
    await push(everyone, {"type": "message", "message": msg})
    return result


@app.post("/chats/{chat_id}/leave")
async def leave_group(chat_id: int, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        chat = con.execute("SELECT kind, owner_id FROM chats WHERE id=?", (chat_id,)).fetchone()
        if not chat or me_row["id"] not in member_ids(con, chat_id) or chat["kind"] not in ("group", "channel"):
            raise HTTPException(403, "Не твой чат")
        if chat["kind"] == "channel" and chat["owner_id"] == me_row["id"]:
            raise HTTPException(409, "Создатель не может покинуть канал")
        con.execute("DELETE FROM members WHERE chat_id = ? AND user_id = ?", (chat_id, me_row["id"]))
        rest = member_ids(con, chat_id)
        msg = system_message(con, chat_id, f"{me_row['name']} вышел из группы")
    await push(rest + [me_row["id"]], {"type": "chat", "chat_id": chat_id})
    await push(rest, {"type": "message", "message": msg})
    return {"ok": True}


class TitleIn(BaseModel):
    title: str


@app.post("/chats/{chat_id}/title")
async def group_title(chat_id: int, body: TitleIn, authorization: str = Header("")):
    me_row = auth(authorization)
    title = body.title.strip()[:60]
    if not title:
        raise HTTPException(400, "Нужно название группы")
    with db() as con:
        check_member(con, chat_id, me_row["id"])
        con.execute("UPDATE chats SET title = ? WHERE id = ?", (title, chat_id))
        msg = system_message(con, chat_id, f"{me_row['name']} переименовал группу в «{title}»")
        ids = member_ids(con, chat_id)
        result = chat_json(con, chat_id, me_row["id"])
    await push(ids, {"type": "chat", "chat_id": chat_id})
    await push(ids, {"type": "message", "message": msg})
    return result


@app.post("/chats/{chat_id}/avatar")
async def group_avatar(chat_id: int, request: Request, authorization: str = Header("")):
    me_row = auth(authorization)
    data = await request.body()
    if not data or len(data) > MAX_PHOTO or not data.startswith(b"\xff\xd8"):
        raise HTTPException(400, "Нужен JPEG до 15 МБ")
    with db() as con:
        check_member(con, chat_id, me_row["id"])
        name = secrets.token_hex(16) + ".jpg"
        (MEDIA / name).write_bytes(data)
        con.execute("UPDATE chats SET avatar = ? WHERE id = ?", (name, chat_id))
        msg = system_message(con, chat_id, f"{me_row['name']} сменил фото группы")
        ids = member_ids(con, chat_id)
        result = chat_json(con, chat_id, me_row["id"])
    await push(ids, {"type": "chat", "chat_id": chat_id})
    await push(ids, {"type": "message", "message": msg})
    return result


# ---------- chat menu: rename, mute, clear, block, remove kent ----------

class ChatSettingsIn(BaseModel):
    alias: str | None = None
    muted: bool | None = None
    pinned: bool | None = None


@app.post("/chats/{chat_id}/settings")
def chat_settings(chat_id: int, body: ChatSettingsIn, authorization: str = Header("")):
    # personal: only I see the new name, only my notifications go quiet
    me_row = auth(authorization)
    with db() as con:
        if me_row["id"] not in member_ids(con, chat_id):
            raise HTTPException(403, "Не твой чат")
        if body.alias is not None:
            con.execute("UPDATE members SET alias = ? WHERE chat_id = ? AND user_id = ?", (body.alias.strip()[:60], chat_id, me_row["id"]))
        if body.muted is not None:
            con.execute("UPDATE members SET muted = ? WHERE chat_id = ? AND user_id = ?", (int(body.muted), chat_id, me_row["id"]))
        if body.pinned is not None:
            con.execute("UPDATE members SET pinned = ? WHERE chat_id = ? AND user_id = ?",
                        (int(time.time()) if body.pinned else 0, chat_id, me_row["id"]))
        return chat_json(con, chat_id, me_row["id"])


def clear_for(con, chat_id, user_id):
    top = con.execute("SELECT COALESCE(MAX(id), 0) FROM messages WHERE chat_id = ?", (chat_id,)).fetchone()[0]
    con.execute("UPDATE members SET cleared = ?, last_read = MAX(last_read, ?), hidden = 1 WHERE chat_id = ? AND user_id = ?",
                (top, top, chat_id, user_id))


@app.post("/chats/{chat_id}/clear")
def clear_chat(chat_id: int, authorization: str = Header("")):
    # only for me: the other side keeps the history
    me_row = auth(authorization)
    with db() as con:
        if me_row["id"] not in member_ids(con, chat_id):
            raise HTTPException(403, "Не твой чат")
        clear_for(con, chat_id, me_row["id"])
    return {"ok": True}


@app.get("/blocks")
def blocks(authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        rows = con.execute("SELECT u.* FROM users u JOIN blocks b ON b.blocked_id = u.id WHERE b.user_id = ? ORDER BY u.name",
                           (me_row["id"],)).fetchall()
    return [user_json(r) for r in rows]


@app.post("/blocks/{user_id}")
def block(user_id: int, authorization: str = Header("")):
    me_row = auth(authorization)
    if user_id == me_row["id"]:
        raise HTTPException(400, "Себя не заблокировать")
    with db() as con:
        if not con.execute("SELECT 1 FROM users WHERE id = ?", (user_id,)).fetchone():
            raise HTTPException(404, "Нет такого кента")
        con.execute("INSERT OR IGNORE INTO blocks (user_id, blocked_id) VALUES (?, ?)", (me_row["id"], user_id))
    return {"ok": True}


@app.delete("/blocks/{user_id}")
def unblock(user_id: int, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        con.execute("DELETE FROM blocks WHERE user_id = ? AND blocked_id = ?", (me_row["id"], user_id))
    return {"ok": True}


@app.post("/kents/{user_id}/remove")
def remove_kent(user_id: int, authorization: str = Header("")):
    # clears our dm for me and hides him from my lists; writing to him again brings him back
    me_row = auth(authorization)
    a, b = sorted([me_row["id"], user_id])
    with db() as con:
        con.execute("INSERT OR IGNORE INTO removed (user_id, other_id) VALUES (?, ?)", (me_row["id"], user_id))
        row = con.execute("SELECT id FROM chats WHERE dm_key = ?", (f"{a}:{b}",)).fetchone()
        if row:
            clear_for(con, row["id"], me_row["id"])
    return {"ok": True}


# ---------- passwords (0.11) ----------

LOCK_AFTER = 5          # wrong passwords in a row
LOCK_FOR = 15 * 60      # seconds


def hash_password(p):
    # stdlib only: nothing new to install on the server PC
    salt = secrets.token_bytes(16)
    n = 200_000
    return f"pbkdf2${n}${salt.hex()}${hashlib.pbkdf2_hmac('sha256', p.encode(), salt, n).hex()}"


def check_password(p, stored):
    try:
        _, n, salt, h = stored.split("$")
        got = hashlib.pbkdf2_hmac("sha256", p.encode(), bytes.fromhex(salt), int(n)).hex()
    except ValueError:
        return False
    return secrets.compare_digest(got, h)


def password_error(p):
    if len(p) < 6:
        return "Пароль: минимум 6 символов"
    if len(p) > 100:
        return "Слишком длинный пароль"
    return None


class PasswordIn(BaseModel):
    password: str
    # needed only when a password is already set
    old: str = ""
    # the first-time screen may change the @username together with the password
    username: str | None = None


@app.post("/me/password")
def set_password(body: PasswordIn, authorization: str = Header("")):
    me_row = auth(authorization)
    if why := password_error(body.password):
        raise HTTPException(400, why)
    if me_row["pass_hash"] and not check_password(body.old, me_row["pass_hash"]):
        raise HTTPException(403, "Старый пароль не подходит")
    username = me_row["username"] if body.username is None else body.username.strip().lower().lstrip("@")
    if username != me_row["username"] and (why := username_error(username, me_row["id"])):
        raise HTTPException(409 if reserved(username) else 400, why)
    try:
        with db() as con:
            con.execute("UPDATE users SET username = ?, pass_hash = ? WHERE id = ?", (username, hash_password(body.password), me_row["id"]))
            row = con.execute("SELECT * FROM users WHERE id = ?", (me_row["id"],)).fetchone()
    except sqlite3.IntegrityError:
        raise HTTPException(409, f"@{username} уже занят")
    return me_json(row)


@app.post("/me/logout_all")
async def logout_all(authorization: str = Header("")):
    """A new token for this phone; every other device gets "bad token" and drops to the login screen."""
    me_row = auth(authorization)
    token = secrets.token_hex(24)
    with db() as con:
        con.execute("UPDATE users SET token = ? WHERE id = ?", (token, me_row["id"]))
    for ws in list(online.get(me_row["id"], ())):
        try:
            await ws.close(code=4401)
        except Exception:
            pass
    return {"token": token}


class LoginIn(BaseModel):
    username: str
    password: str


@app.post("/login")
def login(body: LoginIn):
    """A second phone or a reinstall: the same account and token, all chats come from the server."""
    username = body.username.strip().lower().lstrip("@")
    now = int(time.time())
    wrong = HTTPException(403, "Неверный юзернейм или пароль")
    with db() as con:
        row = con.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
        if not row:
            raise wrong
        if row["locked_until"] > now:
            raise HTTPException(429, f"Много неверных попыток, попробуй через {(row['locked_until'] - now + 59) // 60} мин")
        if not row["pass_hash"]:
            raise HTTPException(403, "У этого аккаунта ещё нет пароля")
        ok = check_password(body.password, row["pass_hash"])
        if not ok:
            fails = row["login_fails"] + 1
            if fails >= LOCK_AFTER:
                con.execute("UPDATE users SET login_fails = 0, locked_until = ? WHERE id = ?", (now + LOCK_FOR, row["id"]))
            else:
                con.execute("UPDATE users SET login_fails = ? WHERE id = ?", (fails, row["id"]))
        else:
            con.execute("UPDATE users SET login_fails = 0, locked_until = 0 WHERE id = ?", (row["id"],))
    # raised only after the block: an exception inside "with con" would roll the fail counter back
    if not ok:
        raise wrong
    return {"token": row["token"], "user": me_json(row)}


# ---------- kents and acquaintances (0.11) ----------

def friend_ids(con, user_id):
    return {r["other_id"] for r in con.execute("SELECT other_id FROM friends WHERE user_id = ?", (user_id,))}


class KentIn(BaseModel):
    kent: bool


@app.post("/friends/{user_id}")
def set_kent(user_id: int, body: KentIn, authorization: str = Header("")):
    # my own list: the other side isn't told
    me_row = auth(authorization)
    if user_id == me_row["id"]:
        raise HTTPException(400, "Это ты")
    with db() as con:
        if not con.execute("SELECT 1 FROM users WHERE id = ?", (user_id,)).fetchone():
            raise HTTPException(404, "Нет такого человека")
        if body.kent:
            con.execute("INSERT OR IGNORE INTO friends (user_id, other_id) VALUES (?, ?)", (me_row["id"], user_id))
        else:
            con.execute("DELETE FROM friends WHERE user_id = ? AND other_id = ?", (me_row["id"], user_id))
    return {"ok": True}


# ---------- spots (0.11) ----------

MAX_SPOT_PHOTOS = 5


def spot_json(con, row):
    owner = con.execute("SELECT name FROM users WHERE id = ?", (row["owner_id"],)).fetchone()
    return {"id": row["id"], "owner_id": row["owner_id"], "owner_name": owner["name"] if owner else "",
            "title": row["title"], "kind": row["kind"], "lat": row["lat"], "lng": row["lng"], "about": row["about"],
            "photos": [p for p in row["photos"].split(",") if p], "vis": row["vis"], "created": row["created"]}


def can_see(con, row, user_id):
    if row["owner_id"] == user_id:
        return True
    return row["vis"] == "kents" and con.execute(
        "SELECT 1 FROM friends WHERE user_id = ? AND other_id = ?", (row["owner_id"], user_id)).fetchone() is not None


def get_spot(con, spot_id, user_id):
    row = con.execute("SELECT * FROM spots WHERE id = ?", (spot_id,)).fetchone()
    if not row or not can_see(con, row, user_id):
        raise HTTPException(404, "Нет такого спота")
    return row


class SpotIn(BaseModel):
    title: str
    kind: str
    lat: float
    lng: float
    about: str = ""
    photos: list[str] = []
    vis: str = "kents"


def clean_spot(body):
    title = body.title.strip()[:60]
    kind = body.kind.strip()[:24]
    if not title:
        raise HTTPException(400, "Нужно название")
    if not kind:
        raise HTTPException(400, "Нужна категория")
    if not (-90 <= body.lat <= 90 and -180 <= body.lng <= 180):
        raise HTTPException(400, "Странная точка на карте")
    photos = [Path(p).name for p in body.photos if (MEDIA / Path(p).name).is_file()][:MAX_SPOT_PHOTOS]
    vis = body.vis if body.vis in ("kents", "me") else "kents"
    return title, kind, body.lat, body.lng, body.about.strip()[:500], ",".join(photos), vis


async def push_spots(con, owner_id):
    # the owner's other phones and the kents who may see it reload the map
    await push([owner_id, *friend_ids(con, owner_id)], {"type": "spots"})


@app.get("/spots")
def spots(authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        rows = con.execute("SELECT * FROM spots ORDER BY id").fetchall()
        return [{**spot_json(con, r), "here": checkins_for(con, r, me_row["id"])} for r in rows if can_see(con, r, me_row["id"])]


@app.post("/spots")
async def create_spot(body: SpotIn, authorization: str = Header("")):
    me_row = auth(authorization)
    vals = clean_spot(body)
    with db() as con:
        cur = con.execute("INSERT INTO spots (title, kind, lat, lng, about, photos, vis, owner_id, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                          (*vals, me_row["id"], int(time.time())))
        result = spot_json(con, con.execute("SELECT * FROM spots WHERE id = ?", (cur.lastrowid,)).fetchone())
        await push_spots(con, me_row["id"])
    return result


def editable_spot(con, spot_id, me_row):
    # the author, and the app's creator for cleaning up
    row = get_spot(con, spot_id, me_row["id"])
    if row["owner_id"] != me_row["id"] and me_row["id"] != owner_id():
        raise HTTPException(403, "Это не твой спот")
    return row


@app.put("/spots/{spot_id}")
async def update_spot(spot_id: int, body: SpotIn, authorization: str = Header("")):
    me_row = auth(authorization)
    vals = clean_spot(body)
    with db() as con:
        row = editable_spot(con, spot_id, me_row)
        con.execute("UPDATE spots SET title = ?, kind = ?, lat = ?, lng = ?, about = ?, photos = ?, vis = ? WHERE id = ?", (*vals, spot_id))
        result = spot_json(con, con.execute("SELECT * FROM spots WHERE id = ?", (spot_id,)).fetchone())
        await push_spots(con, row["owner_id"])
    return result


@app.delete("/spots/{spot_id}")
async def delete_spot(spot_id: int, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        row = editable_spot(con, spot_id, me_row)
        con.execute("DELETE FROM spots WHERE id = ?", (spot_id,))
        await push_spots(con, row["owner_id"])
    return {"ok": True}


class CallIn(BaseModel):
    text: str = ""


@app.post("/spots/{spot_id}/call")
async def call_to_spot(spot_id: int, body: CallIn, authorization: str = Header("")):
    """«Позвать на спот»: a message with the spot card in the dm with each of my kents (not everyone I talk to)."""
    me_row = auth(authorization)
    me_id = me_row["id"]
    now = int(time.time())
    sent = []
    with db() as con:
        row = get_spot(con, spot_id, me_id)
        snap = json.dumps({"id": row["id"], "title": row["title"], "kind": row["kind"], "lat": row["lat"], "lng": row["lng"]}, ensure_ascii=False)
        text = body.text.strip()[:500] or f"Еду на «{row['title']}», подтягивайся!"
        kents = friend_ids(con, me_id)
        if not kents:
            raise HTTPException(400, "Сначала отметь кентов: Профиль → Кенты")
        for other in sorted(kents):
            if blocked_between(con, me_id, other):
                continue
            a, b = sorted([me_id, other])
            chat = con.execute("SELECT id FROM chats WHERE dm_key = ?", (f"{a}:{b}",)).fetchone()
            if chat:
                chat_id = chat["id"]
            else:
                chat_id = con.execute("INSERT INTO chats (kind, dm_key) VALUES ('dm', ?)", (f"{a}:{b}",)).lastrowid
                con.executemany("INSERT INTO members (chat_id, user_id) VALUES (?, ?)", [(chat_id, a), (chat_id, b)])
            cur = con.execute("INSERT INTO messages (chat_id, sender_id, text, created, spot) VALUES (?, ?, ?, ?, ?)",
                              (chat_id, me_id, text, now, snap))
            con.execute("UPDATE members SET last_read = ? WHERE chat_id = ? AND user_id = ?", (cur.lastrowid, chat_id, me_id))
            con.execute("UPDATE members SET hidden = 0 WHERE chat_id = ?", (chat_id,))
            msg = msg_json(con.execute("SELECT * FROM messages WHERE id = ?", (cur.lastrowid,)).fetchone(), con)
            sent.append((chat_id, [a, b], msg))
    for chat_id, ids, msg in sent:
        await push(ids, {"type": "message", "message": msg})
    return {"sent": len(sent)}


# ---------- 0.15 Beta: live cards in chats ----------
# A card message has empty text and card = '{"type": ..., "id": ...}'. Its state is built fresh from the
# tables on every read; after a change everyone in the chat gets a "card" event with the new state.

def card_label(card):
    c = json.loads(card)
    return {"poll": "📊 Опрос", "gather": "📅 Сбор", "split": "💸 Скидываемся", "watch": "🎬 Смотрим вместе",
            "call": "📞 Звонок", "bot_message": "Сообщение от JT бота"}.get(c.get("type"), "")


def card_json(con, card, viewer=0):
    c = json.loads(card)
    kind, cid = c.get("type"), int(c.get("id", 0))
    build = {"poll": poll_json, "gather": gather_json, "split": split_json, "watch": watch_json, "call": call_json, "bot_message": bot.draft_json}.get(kind)
    out = build(con, cid, viewer) if build else None
    return out or {"type": kind, "id": cid, "gone": True}


async def push_card(con, chat_id, kind, cid):
    """Everyone in the chat redraws the card; each gets his own copy (anonymous polls show only your own votes)."""
    row = con.execute("SELECT id, card FROM messages WHERE chat_id = ? AND card = ?",
                      (chat_id, json.dumps({"type": kind, "id": cid}))).fetchone()
    if not row:
        return
    for uid in member_ids(con, chat_id):
        if uid in online:
            await push([uid], {"type": "card", "chat_id": chat_id, "message_id": row["id"], "card": card_json(con, row["card"], uid)})


async def post_card(con, chat_id, me_id, kind, cid, client_id=""):
    """The message that carries a new card; like any message it lifts the chat and counts as read for me."""
    card = json.dumps({"type": kind, "id": cid})
    # apps before 0.15 don't know cards: they show this text instead (0.15 hides it under the card)
    built = card_json(con, card, me_id)
    fallback = card_label(card) + {"poll": lambda c: ": " + c.get("question", ""), "gather": lambda c: ": " + c.get("title", ""),
                                   "split": lambda c: ": " + c.get("title", ""), "watch": lambda c: ": " + c.get("title", "")}.get(kind, lambda c: "")(built)
    fallback += " · обнови JustTalk до 0.15, чтобы увидеть"
    cur = con.execute("INSERT INTO messages (chat_id, sender_id, text, created, card) VALUES (?, ?, ?, ?, ?)",
                      (chat_id, -1 if BOT_ORIGIN.get() else me_id, fallback, int(time.time()), card))
    if origin := BOT_ORIGIN.get():
        bot.tag(con, cur.lastrowid, origin)
    con.execute("UPDATE members SET last_read = ? WHERE chat_id = ? AND user_id = ?", (cur.lastrowid, chat_id, me_id))
    con.execute("UPDATE members SET hidden = 0 WHERE chat_id = ?", (chat_id,))
    row = con.execute("SELECT * FROM messages WHERE id = ?", (cur.lastrowid,)).fetchone()
    for uid in member_ids(con, chat_id):
        await push([uid], {"type": "message", "message": msg_json(row, con, uid), "client_id": client_id})
    return msg_json(row, con, me_id)


def chat_for_card(con, chat_id, me_id):
    if me_id not in member_ids(con, chat_id):
        raise HTTPException(403, "Не твой чат")
    check_channel_post(con, chat_id, me_id)
    if why := dm_block_error(con, chat_id, me_id):
        raise HTTPException(403, why)


def card_row(con, table, cid, me_id):
    """The card's row, only for people in its chat."""
    row = con.execute(f"SELECT * FROM {table} WHERE id = ?", (cid,)).fetchone()
    if not row or me_id not in member_ids(con, row["chat_id"]):
        raise HTTPException(404, "Нет такой карточки")
    return row


# ---------- polls ----------

def poll_json(con, pid, viewer):
    p = con.execute("SELECT * FROM polls WHERE id = ?", (pid,)).fetchone()
    if not p:
        return None
    votes = con.execute("SELECT user_id, option FROM poll_votes WHERE poll_id = ?", (pid,)).fetchall()
    options = []
    for i, text in enumerate(json.loads(p["options"])):
        who = [v["user_id"] for v in votes if v["option"] == i]
        # anonymous: counts only, nobody's name
        options.append({"text": text, "votes": len(who), "voters": [] if p["anon"] else who})
    return {"type": "poll", "id": pid, "owner_id": p["owner_id"], "question": p["question"], "options": options,
            "multi": bool(p["multi"]), "anon": bool(p["anon"]), "closed": bool(p["closed"]),
            "mine": sorted(v["option"] for v in votes if v["user_id"] == viewer),
            "voters": len({v["user_id"] for v in votes})}


class PollIn(BaseModel):
    question: str
    options: list[str]
    multi: bool = False
    anon: bool = False
    client_id: str = ""


@app.post("/chats/{chat_id}/poll")
async def create_poll(chat_id: int, body: PollIn, authorization: str = Header("")):
    me_row = auth(authorization)
    question = body.question.strip()[:200]
    options = [o.strip()[:100] for o in body.options if o.strip()][:10]
    if not question:
        raise HTTPException(400, "Нужен вопрос")
    if len(options) < 2:
        raise HTTPException(400, "Нужно хотя бы два варианта")
    with db() as con:
        chat_for_card(con, chat_id, me_row["id"])
        pid = con.execute("INSERT INTO polls (chat_id, owner_id, question, options, multi, anon) VALUES (?, ?, ?, ?, ?, ?)",
                          (chat_id, me_row["id"], question, json.dumps(options, ensure_ascii=False), int(body.multi), int(body.anon))).lastrowid
        return await post_card(con, chat_id, me_row["id"], "poll", pid, body.client_id)


class VoteIn(BaseModel):
    options: list[int]   # all my choices at once; empty = take my vote back


@app.post("/polls/{pid}/vote")
async def vote(pid: int, body: VoteIn, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        p = card_row(con, "polls", pid, me_row["id"])
        if p["closed"]:
            raise HTTPException(400, "Опрос закрыт")
        n = len(json.loads(p["options"]))
        picked = sorted({i for i in body.options if 0 <= i < n})
        if not p["multi"]:
            picked = picked[:1]
        con.execute("DELETE FROM poll_votes WHERE poll_id = ? AND user_id = ?", (pid, me_row["id"]))
        con.executemany("INSERT INTO poll_votes (poll_id, user_id, option) VALUES (?, ?, ?)", [(pid, me_row["id"], i) for i in picked])
        await push_card(con, p["chat_id"], "poll", pid)
        return poll_json(con, pid, me_row["id"])


@app.post("/polls/{pid}/close")
async def close_poll(pid: int, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        p = card_row(con, "polls", pid, me_row["id"])
        if p["owner_id"] != me_row["id"]:
            raise HTTPException(403, "Закрыть может только автор")
        con.execute("UPDATE polls SET closed = 1 WHERE id = ?", (pid,))
        await push_card(con, p["chat_id"], "poll", pid)
        return poll_json(con, pid, me_row["id"])


# ---------- сборы ----------

def gather_json(con, gid, viewer):
    g = con.execute("SELECT * FROM gathers WHERE id = ?", (gid,)).fetchone()
    if not g:
        return None
    rsvp = {"yes": [], "maybe": [], "no": []}
    for r in con.execute("SELECT user_id, status FROM gather_rsvp WHERE gather_id = ? ORDER BY rowid", (gid,)):
        rsvp.setdefault(r["status"], []).append(r["user_id"])
    return {"type": "gather", "id": gid, "owner_id": g["owner_id"], "title": g["title"], "at": g["at"], "place": g["place"],
            "spot": json.loads(g["spot"]) if g["spot"] else None, "note": g["note"], "cancelled": bool(g["cancelled"]), "rsvp": rsvp}


class GatherIn(BaseModel):
    title: str
    at: int                 # unix seconds
    place: str = ""
    spot_id: int = 0
    note: str = ""
    client_id: str = ""


@app.post("/chats/{chat_id}/gather")
async def create_gather(chat_id: int, body: GatherIn, authorization: str = Header("")):
    me_row = auth(authorization)
    title = body.title.strip()[:80]
    if not title:
        raise HTTPException(400, "Нужно название сбора")
    if body.at < time.time() - 3600:
        raise HTTPException(400, "Это время уже прошло")
    with db() as con:
        chat_for_card(con, chat_id, me_row["id"])
        spot = ""
        if body.spot_id:
            s = get_spot(con, body.spot_id, me_row["id"])
            spot = json.dumps({"id": s["id"], "title": s["title"], "kind": s["kind"], "lat": s["lat"], "lng": s["lng"]}, ensure_ascii=False)
        gid = con.execute("INSERT INTO gathers (chat_id, owner_id, title, at, place, spot, note) VALUES (?, ?, ?, ?, ?, ?, ?)",
                          (chat_id, me_row["id"], title, body.at, body.place.strip()[:120], spot, body.note.strip()[:300])).lastrowid
        # the one who calls everyone is going himself
        con.execute("INSERT INTO gather_rsvp (gather_id, user_id, status) VALUES (?, ?, 'yes')", (gid, me_row["id"]))
        return await post_card(con, chat_id, me_row["id"], "gather", gid, body.client_id)


class RsvpIn(BaseModel):
    status: str   # yes / maybe / no / "" (take it back)


@app.post("/gathers/{gid}/rsvp")
async def rsvp(gid: int, body: RsvpIn, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        g = card_row(con, "gathers", gid, me_row["id"])
        if g["cancelled"]:
            raise HTTPException(400, "Сбор отменён")
        if body.status in ("yes", "maybe", "no"):
            con.execute("INSERT OR REPLACE INTO gather_rsvp (gather_id, user_id, status) VALUES (?, ?, ?)", (gid, me_row["id"], body.status))
        else:
            con.execute("DELETE FROM gather_rsvp WHERE gather_id = ? AND user_id = ?", (gid, me_row["id"]))
        await push_card(con, g["chat_id"], "gather", gid)
        return gather_json(con, gid, me_row["id"])


@app.post("/gathers/{gid}/cancel")
async def cancel_gather(gid: int, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        g = card_row(con, "gathers", gid, me_row["id"])
        if g["owner_id"] != me_row["id"]:
            raise HTTPException(403, "Отменить может только тот, кто собирал")
        con.execute("UPDATE gathers SET cancelled = 1 WHERE id = ?", (gid,))
        msg = system_message(con, g["chat_id"], f"{me_row['name']} отменил сбор «{g['title']}»")
        await push_card(con, g["chat_id"], "gather", gid)
        await push(member_ids(con, g["chat_id"]), {"type": "message", "message": msg})
        return gather_json(con, gid, me_row["id"])


async def remind_gathers():
    """An hour before a сбор the chat gets a line about it (checked every half a minute)."""
    import asyncio
    while True:
        await asyncio.sleep(30)
        try:
            now = int(time.time())
            with db() as con:
                rows = con.execute("SELECT * FROM gathers WHERE cancelled = 0 AND reminded = 0 AND at - 3600 <= ? AND at > ?",
                                   (now, now)).fetchall()
                for g in rows:
                    con.execute("UPDATE gathers SET reminded = 1 WHERE id = ?", (g["id"],))
                    going = con.execute("SELECT COUNT(*) FROM gather_rsvp WHERE gather_id = ? AND status = 'yes'", (g["id"],)).fetchone()[0]
                    mins = max(1, (g["at"] - now + 30) // 60)
                    msg = system_message(con, g["chat_id"], f"⏰ Сбор «{g['title']}» через {mins} мин · идут: {going}")
                    await push(member_ids(con, g["chat_id"]), {"type": "message", "message": msg})
        except Exception as e:  # a broken row must not stop the reminders for good
            print("remind_gathers:", e, flush=True)


# ---------- скидываемся ----------

def split_json(con, sid, viewer):
    s = con.execute("SELECT * FROM splits WHERE id = ?", (sid,)).fetchone()
    if not s:
        return None
    shares = [{"user_id": r["user_id"], "amount": r["amount"], "paid": bool(r["paid"])}
              for r in con.execute("SELECT * FROM split_shares WHERE split_id = ? ORDER BY rowid", (sid,))]
    return {"type": "split", "id": sid, "owner_id": s["owner_id"], "title": s["title"], "total": s["total"], "details": s["details"],
            "closed": bool(s["closed"]), "shares": shares}


class SplitIn(BaseModel):
    title: str
    total: int               # rubles
    user_ids: list[int]      # who chips in (may include me)
    details: str = ""        # where to send: card / phone for СБП
    client_id: str = ""
    amounts: dict[int, int] | None = None


@app.post("/chats/{chat_id}/split")
async def create_split(chat_id: int, body: SplitIn, authorization: str = Header("")):
    me_row = auth(authorization)
    title = body.title.strip()[:80]
    if not title:
        raise HTTPException(400, "На что скидываемся?")
    if not 1 <= body.total <= 10_000_000:
        raise HTTPException(400, "Странная сумма")
    with db() as con:
        chat_for_card(con, chat_id, me_row["id"])
        members = member_ids(con, chat_id)
        people = [u for u in dict.fromkeys(body.user_ids) if u in members]
        if not people or people == [me_row["id"]]:
            raise HTTPException(400, "Выбери, кто скидывается")
        sid = con.execute("INSERT INTO splits (chat_id, owner_id, title, total, details) VALUES (?, ?, ?, ?, ?)",
                          (chat_id, me_row["id"], title, body.total, body.details.strip()[:200])).lastrowid
        # equal shares; the leftover rubles go to the first ones, so the sum is exact
        base, extra = divmod(body.total, len(people))
        if body.amounts is not None and (set(body.amounts) != set(people) or any(v < 0 for v in body.amounts.values()) or sum(body.amounts.values()) != body.total):
            raise HTTPException(400, "Доли должны совпадать с участниками и общей суммой")
        con.executemany("INSERT INTO split_shares (split_id, user_id, amount, paid) VALUES (?, ?, ?, ?)",
                        [(sid, u, body.amounts[u] if body.amounts is not None else base + (1 if i < extra else 0), int(u == me_row["id"])) for i, u in enumerate(people)])
        return await post_card(con, chat_id, me_row["id"], "split", sid, body.client_id)


class PaidIn(BaseModel):
    user_id: int
    paid: bool


@app.post("/splits/{sid}/paid")
async def split_paid(sid: int, body: PaidIn, authorization: str = Header("")):
    """Everyone marks himself; the one who collects can mark anybody."""
    me_row = auth(authorization)
    with db() as con:
        s = card_row(con, "splits", sid, me_row["id"])
        if body.user_id != me_row["id"] and s["owner_id"] != me_row["id"]:
            raise HTTPException(403, "Отметить можно только себя")
        con.execute("UPDATE split_shares SET paid = ? WHERE split_id = ? AND user_id = ?", (int(body.paid), sid, body.user_id))
        left = con.execute("SELECT COUNT(*) FROM split_shares WHERE split_id = ? AND paid = 0", (sid,)).fetchone()[0]
        con.execute("UPDATE splits SET closed = ? WHERE id = ?", (int(left == 0), sid))
        await push_card(con, s["chat_id"], "split", sid)
        if left == 0 and not s["closed"]:
            msg = system_message(con, s["chat_id"], f"💸 Все скинулись на «{s['title']}»")
            await push(member_ids(con, s["chat_id"]), {"type": "message", "message": msg})
        return split_json(con, sid, me_row["id"])


# ---------- «Я тут» ----------

CHECKIN_HOURS = 3


def checkins_for(con, spot_row, viewer):
    """Who is at this spot now: the viewer himself and those who marked him as кент."""
    now = int(time.time())
    out = []
    for r in con.execute("SELECT c.*, u.name, u.avatar FROM checkins c JOIN users u ON u.id = c.user_id WHERE c.spot_id = ? AND c.until > ?",
                         (spot_row["id"], now)):
        if r["user_id"] == viewer or con.execute("SELECT 1 FROM friends WHERE user_id = ? AND other_id = ?", (r["user_id"], viewer)).fetchone():
            out.append({"user_id": r["user_id"], "name": r["name"], "avatar": r["avatar"], "at": r["at"]})
    return out


class CheckinIn(BaseModel):
    spot_id: int        # 0 = I left


@app.post("/me/checkin")
async def checkin(body: CheckinIn, authorization: str = Header("")):
    me_row = auth(authorization)
    now = int(time.time())
    with db() as con:
        if body.spot_id:
            get_spot(con, body.spot_id, me_row["id"])
            con.execute("INSERT OR REPLACE INTO checkins (user_id, spot_id, at, until) VALUES (?, ?, ?, ?)",
                        (me_row["id"], body.spot_id, now, now + CHECKIN_HOURS * 3600))
        else:
            con.execute("DELETE FROM checkins WHERE user_id = ?", (me_row["id"],))
        await push([me_row["id"], *friend_ids(con, me_row["id"])], {"type": "spots"})
    return {"ok": True}


# ---------- смотреть вместе ----------

# watch id -> user ids with the player open right now
watchers: dict[int, set[int]] = {}
MAX_WATCH_FILE = 1024 * 1024 * 1024


def watch_state(w):
    # where the video is right now, as the server sees it
    pos = w["pos"] + (time.time() - w["at"] if w["playing"] else 0)
    return {"playing": bool(w["playing"]), "pos": round(pos, 2), "server_now": time.time()}


def watch_json(con, wid, viewer):
    w = con.execute("SELECT * FROM watches WHERE id = ?", (wid,)).fetchone()
    if not w:
        return None
    return {"type": "watch", "id": wid, "owner_id": w["owner_id"], "source": w["source"], "src": w["src"], "title": w["title"],
            "ended": bool(w["ended"]), "viewers": sorted(watchers.get(wid, ())), **watch_state(w)}


class WatchIn(BaseModel):
    source: str     # youtube / vk / file
    src: str        # video id for youtube, "oid_id_hash" for vk, media file name for file
    title: str = ""
    client_id: str = ""


@app.post("/chats/{chat_id}/watch")
async def create_watch(chat_id: int, body: WatchIn, authorization: str = Header("")):
    me_row = auth(authorization)
    if body.source not in ("youtube", "vk", "file"):
        raise HTTPException(400, "Неизвестный источник")
    src = body.src.strip()[:300]
    if body.source == "file":
        src = Path(src).name
        if not (MEDIA / src).is_file():
            raise HTTPException(400, "Видео не загрузилось")
    elif not src or not all(c.isalnum() or c in "-_" for c in src):
        raise HTTPException(400, "Не понял ссылку")
    with db() as con:
        chat_for_card(con, chat_id, me_row["id"])
        wid = con.execute("INSERT INTO watches (chat_id, owner_id, source, src, title, at, created) VALUES (?, ?, ?, ?, ?, ?, ?)",
                          (chat_id, me_row["id"], body.source, src, body.title.strip()[:120], time.time(), int(time.time()))).lastrowid
        return await post_card(con, chat_id, me_row["id"], "watch", wid, body.client_id)


@app.get("/watch/{wid}")
def get_watch(wid: int, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        card_row(con, "watches", wid, me_row["id"])
        return watch_json(con, wid, me_row["id"])


@app.post("/watch/{wid}/end")
async def end_watch(wid: int, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        w = card_row(con, "watches", wid, me_row["id"])
        if w["owner_id"] != me_row["id"]:
            raise HTTPException(403, "Закончить может только тот, кто включил")
        con.execute("UPDATE watches SET ended = 1, playing = 0 WHERE id = ?", (wid,))
        watchers.pop(wid, None)
        await push_card(con, w["chat_id"], "watch", wid)
    return {"ok": True}


async def on_watch_event(me_id, ev):
    """play / pause / seek from anyone in the room moves everyone; join / leave keep the viewer list."""
    wid = int(ev.get("watch_id", 0))
    action = ev.get("action")
    with db() as con:
        w = con.execute("SELECT * FROM watches WHERE id = ?", (wid,)).fetchone()
        if not w or w["ended"] or me_id not in member_ids(con, w["chat_id"]):
            return
        if action in ("join", "leave"):
            room = watchers.setdefault(wid, set())
            before = set(room)
            room.add(me_id) if action == "join" else room.discard(me_id)
            if not room:
                # nobody watching: pause where it is, so the next one starts from there
                st = watch_state(w)
                con.execute("UPDATE watches SET playing = 0, pos = ?, at = ? WHERE id = ?", (st["pos"], time.time(), wid))
            if room != before:
                await push_card(con, w["chat_id"], "watch", wid)
            return
        if action not in ("play", "pause", "seek"):
            return
        pos = max(0.0, float(ev.get("pos", 0)))
        playing = {"play": 1, "pause": 0}.get(action, w["playing"])
        con.execute("UPDATE watches SET playing = ?, pos = ?, at = ? WHERE id = ?", (playing, pos, time.time(), wid))
        w = con.execute("SELECT * FROM watches WHERE id = ?", (wid,)).fetchone()
    out = {"type": "watch", "watch_id": wid, "action": action, "by": me_id, **watch_state(w)}
    await push(list(watchers.get(wid, ())), out)


PLAYER_PAGES = {
    # YouTube wants a real page as the referrer, so the phone opens this one instead of loading HTML from memory
    "yt": """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head>
<body><div id="p"></div><script>
var q=new URLSearchParams(location.search),player,ready=false;
function bridge(){return window.JT||{onReady:function(){},onState:function(){},onError:function(){}}}
function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:q.get('v'),playerVars:{playsinline:1,rel:0,modestbranding:1,origin:location.origin},
events:{onReady:function(){ready=true;bridge().onReady(player.getDuration()||0,player.getVideoData().title||'')},
onStateChange:function(e){bridge().onState(e.data,player.getCurrentTime())},onError:function(e){bridge().onError(''+e.data)}}})}
function cmd(a,t){if(!ready)return;var st=player.getPlayerState();
// seekTo on a player that never started would start it: cue it at that moment instead
if(a=='pause'){if(st==-1||st==5){if(t>1)player.cueVideoById({videoId:q.get('v'),startSeconds:t})}else{player.pauseVideo();if(t!=null)player.seekTo(t,true)}return}
if(t!=null)player.seekTo(t,true);if(a=='play')player.playVideo()}
function pos(){return ready?player.getCurrentTime():-1}
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>""",
    # VK Video embed with its JS API (js_api=1): play / pause / seek / getCurrentTime and events
    "vk": """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style></head>
<body><iframe id="f" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen></iframe>
<script src="https://vk.com/js/api/videoplayer.js"></script><script>
var q=new URLSearchParams(location.search),player,ready=false;
function bridge(){return window.JT||{onReady:function(){},onState:function(){},onError:function(){}}}
var f=document.getElementById('f');
f.src='https://vkvideo.ru/video_ext.php?oid='+q.get('oid')+'&id='+q.get('id')+(q.get('hash')?'&hash='+q.get('hash'):'')+'&hd=2&js_api=1';
f.onload=function(){try{player=VK.VideoPlayer(f);var E=VK.VideoPlayer.Events;
player.on(E.INITED,function(){ready=true;bridge().onReady(player.getDuration()||0,'')});
player.on(E.STARTED,function(){bridge().onState(1,player.getCurrentTime())});
player.on(E.RESUMED,function(){bridge().onState(1,player.getCurrentTime())});
player.on(E.PAUSED,function(){bridge().onState(2,player.getCurrentTime())});
player.on(E.ENDED,function(){bridge().onState(0,player.getCurrentTime())});
player.on(E.ERROR,function(){bridge().onError('vk')});}catch(e){bridge().onError(''+e)}};
function cmd(a,t){if(!player)return;try{if(t!=null&&(a=='seek'||a=='play'))player.seek(t);if(a=='play')player.play();if(a=='pause'){player.pause();if(t!=null)player.seek(t)}}catch(e){}}
function pos(){try{return player?player.getCurrentTime():-1}catch(e){return -1}}
</script></body></html>""",
}


@app.get("/player/{kind}")
def player_page(kind: str):
    page = PLAYER_PAGES.get(kind)
    if not page:
        raise HTTPException(404, "Нет такого плеера")
    return Response(page, media_type="text/html")


# ---------- voice calls ----------
# Signaling goes over the main socket ("call" events), audio over /call: Opus packets, the server only relays them.

RING_FOR = 45   # seconds a call rings before it counts as missed

# call id -> {"chat_id", "in": user ids talking now, "ringing": user ids not answered yet, "muted": set, "audio": {user id: ws}}
calls_live: dict[int, dict] = {}


def call_json(con, cid, viewer):
    c = con.execute("SELECT * FROM calls WHERE id = ?", (cid,)).fetchone()
    if not c:
        return None
    live = calls_live.get(cid)
    status = "active" if live else ("ended" if c["answered"] else "missed")
    return {"type": "call", "id": cid, "owner_id": c["owner_id"], "chat_id": c["chat_id"], "status": status, "started": c["started"],
            "answered": c["answered"], "duration": (c["ended"] - c["answered"]) if c["answered"] and c["ended"] else 0,
            "in": sorted(live["in"]) if live else [], "muted": sorted(live["muted"]) if live else [],
            "people": [int(x) for x in c["people"].split(",") if x]}


def call_in_chat(chat_id):
    return next((cid for cid, c in calls_live.items() if c["chat_id"] == chat_id), None)


async def push_call(con, cid):
    c = con.execute("SELECT chat_id FROM calls WHERE id = ?", (cid,)).fetchone()
    if c:
        await push_card(con, c["chat_id"], "call", cid)


async def end_call(cid):
    live = calls_live.pop(cid, None)
    if not live:
        return
    for ws in list(live["audio"].values()):
        try:
            await ws.close()
        except Exception:
            pass
    with db() as con:
        con.execute("UPDATE calls SET ended = ? WHERE id = ?", (int(time.time()), cid))
        await push_call(con, cid)
        await push(member_ids(con, live["chat_id"]), {"type": "call_end", "call_id": cid, "chat_id": live["chat_id"]})


async def ring_timeout(cid):
    import asyncio
    await asyncio.sleep(RING_FOR)
    live = calls_live.get(cid)
    if not live:
        return
    live["ringing"].clear()
    # nobody picked up: the caller is alone
    if len(live["in"]) <= 1:
        await end_call(cid)
    else:
        with db() as con:
            await push_call(con, cid)


async def on_call_event(me_id, ev):
    import asyncio
    kind = ev.get("type")
    with db() as con:
        if kind == "call_start":
            chat_id = int(ev.get("chat_id", 0))
            if me_id not in member_ids(con, chat_id) or dm_block_error(con, chat_id, me_id):
                return
            if con.execute("SELECT kind FROM chats WHERE id=?", (chat_id,)).fetchone()["kind"] == "channel":
                return
            cid = call_in_chat(chat_id)
            if cid is None:
                cid = con.execute("INSERT INTO calls (chat_id, owner_id, started, people) VALUES (?, ?, ?, ?)",
                                  (chat_id, me_id, int(time.time()), str(me_id))).lastrowid
                others = [u for u in member_ids(con, chat_id) if u != me_id]
                calls_live[cid] = {"chat_id": chat_id, "in": {me_id}, "ringing": set(others), "muted": set(), "audio": {}}
                await post_card(con, chat_id, me_id, "call", cid)
                me_name = con.execute("SELECT name FROM users WHERE id = ?", (me_id,)).fetchone()["name"]
                await push(others, {"type": "call_ring", "call_id": cid, "chat_id": chat_id, "from": me_id, "from_name": me_name})
                asyncio.create_task(ring_timeout(cid))
            else:
                await join_call(con, cid, me_id)
            await push([me_id], {"type": "call_joined", "call_id": cid, "chat_id": chat_id})
            return
        cid = int(ev.get("call_id", 0))
        live = calls_live.get(cid)
        if not live or me_id not in member_ids(con, live["chat_id"]):
            return
        if kind == "call_join":
            await join_call(con, cid, me_id)
            await push([me_id], {"type": "call_joined", "call_id": cid, "chat_id": live["chat_id"]})
        elif kind == "call_decline":
            live["ringing"].discard(me_id)
            # a dm call declined: over at once
            if not live["ringing"] and len(live["in"]) <= 1:
                await end_call(cid)
                return
            await push_call(con, cid)
        elif kind == "call_leave":
            live["in"].discard(me_id)
            live["muted"].discard(me_id)
            ws = live["audio"].pop(me_id, None)
            if ws:
                try:
                    await ws.close()
                except Exception:
                    pass
            chat = con.execute("SELECT kind FROM chats WHERE id = ?", (live["chat_id"],)).fetchone()
            # a dm ends when either side hangs up; a group when the last one leaves
            if not live["in"] or (chat["kind"] == "dm") or (len(live["in"]) == 1 and not live["ringing"]):
                await end_call(cid)
                return
            await push_call(con, cid)
        elif kind == "call_mute":
            (live["muted"].add if ev.get("muted") else live["muted"].discard)(me_id)
            await push_call(con, cid)


async def join_call(con, cid, me_id):
    live = calls_live[cid]
    live["ringing"].discard(me_id)
    live["in"].add(me_id)
    row = con.execute("SELECT * FROM calls WHERE id = ?", (cid,)).fetchone()
    people = {int(x) for x in row["people"].split(",") if x} | {me_id}
    con.execute("UPDATE calls SET people = ?, answered = CASE WHEN answered = 0 AND ? > 1 THEN ? ELSE answered END WHERE id = ?",
                (",".join(map(str, sorted(people))), len(live["in"]), int(time.time()), cid))
    await push_call(con, cid)
    # my other phones stop ringing
    await push([me_id], {"type": "call_taken", "call_id": cid})


@app.websocket("/call")
async def call_audio(ws: WebSocket, token: str = "", call_id: int = 0):
    """Binary frames in: one Opus packet. Out: 4-byte sender id + the packet, to everyone else in the call."""
    try:
        me_row = auth(token)
    except HTTPException:
        await ws.close(code=4401)
        return
    me_id = me_row["id"]
    live = calls_live.get(call_id)
    if not live or me_id not in live["in"]:
        await ws.close(code=4404)
        return
    await ws.accept()
    old = live["audio"].get(me_id)
    live["audio"][me_id] = ws
    if old:
        try:
            await old.close()
        except Exception:
            pass
    head = me_id.to_bytes(4, "big")
    try:
        while True:
            data = await ws.receive_bytes()
            if len(data) > 1500 or call_id not in calls_live:
                continue
            frame = head + data
            for uid, other in list(live["audio"].items()):
                if uid != me_id:
                    try:
                        await other.send_bytes(frame)
                    except Exception:
                        live["audio"].pop(uid, None)
    except (WebSocketDisconnect, RuntimeError, KeyError):
        pass
    finally:
        if live["audio"].get(me_id) is ws:
            live["audio"].pop(me_id, None)
            import asyncio
            asyncio.create_task(drop_if_gone(call_id, me_id))


async def drop_if_gone(cid, uid):
    """Lost network in a call: 20 s to reconnect the audio, then he is out (a dead phone must not keep the call open)."""
    import asyncio
    await asyncio.sleep(20)
    live = calls_live.get(cid)
    if live and uid in live["in"] and uid not in live["audio"]:
        await on_call_event(uid, {"type": "call_leave", "call_id": cid})


# ---------- admin (the Mac panel, through admin.py on the server PC) ----------

ADMIN_TOKEN_PATH = BASE / "admin_token.txt"
if not ADMIN_TOKEN_PATH.exists():
    ADMIN_TOKEN_PATH.write_text(secrets.token_hex(24))


def check_admin(x_admin):
    # the tunnel also arrives from localhost, so a secret file is the guard, not the address
    if not secrets.compare_digest(x_admin, ADMIN_TOKEN_PATH.read_text().strip()):
        raise HTTPException(403, "no")


@app.get("/admin/online")
def admin_online(x_admin: str = Header("")):
    check_admin(x_admin)
    # ids = app open on screen; bg = only the background connection (notifications)
    on = {uid for uid, socks in online.items() if socks & active}
    return {"ids": sorted(on), "bg": sorted(uid for uid, socks in online.items() if socks and uid not in on)}


# The panel away from home: the same admin.py commands, but through the tunnel instead of ssh.
ADMIN_SCRIPT = BASE.parent / "admin.py"
ADMIN_COMMANDS = {"status", "users", "chats", "log", "set_invite", "delete_user", "delete_chat", "reset_password"}
# a SYSTEM task that runs "admin.py restart": stopping JustTalkServer from inside this process would kill the caller
RESTART_TASK = "JustTalkRestart"


class AdminRun(BaseModel):
    cmd: str
    args: list[str] = []


def run_admin(*args):
    # sync endpoints run in a thread pool, so admin.py can still call /admin/online on this same server
    r = subprocess.run([sys.executable, str(ADMIN_SCRIPT), *args], capture_output=True, timeout=60,
                       env={**os.environ, "JT_BASE": str(BASE)})
    return r.stdout.strip() or json.dumps({"ok": False, "error": r.stderr.decode("utf-8", "replace")[-300:]}).encode()


@app.post("/admin/run")
def admin_run(body: AdminRun, x_admin: str = Header("")):
    check_admin(x_admin)
    if body.cmd not in ADMIN_COMMANDS:
        raise HTTPException(400, "unknown command")
    return Response(run_admin(body.cmd, *body.args), media_type="application/json")


@app.post("/admin/restart")
def admin_restart(x_admin: str = Header("")):
    check_admin(x_admin)
    if os.name != "nt":
        return {"ok": False, "error": "restart works only on the server PC"}
    r = subprocess.run(["schtasks", "/Run", "/TN", RESTART_TASK], capture_output=True)
    if r.returncode != 0:
        return {"ok": False, "error": f"no {RESTART_TASK} task on the PC"}
    return {"ok": True}


@app.get("/admin/backup")
def admin_backup(x_admin: str = Header("")):
    check_admin(x_admin)
    d = json.loads(run_admin("backup"))
    if not d.get("ok"):
        raise HTTPException(500, d.get("error", "backup failed"))
    path = Path(d["path"])
    return FileResponse(path, filename=path.name, media_type="application/octet-stream")


# ---------- websocket ----------

# user_id -> open sockets (one user can be online from several devices)
online: dict[int, set[WebSocket]] = {}
# sockets whose app is open on screen right now (the "presence" event, apps 0.9+)
active: set[WebSocket] = set()


async def push(user_ids, event):
    data = json.dumps(event, ensure_ascii=False)
    for uid in user_ids:
        for ws in list(online.get(uid, ())):
            try:
                await ws.send_text(data)
            except Exception:
                online.get(uid, set()).discard(ws)


def mark_seen(user_id):
    with db() as con:
        con.execute("UPDATE users SET last_seen = ? WHERE id = ?", (int(time.time()), user_id))


def is_active(user_id):
    return any(ws in active for ws in online.get(user_id, ()))


def contacts(con, user_id):
    # everyone I share a chat with: they see my "в сети"
    return [r["user_id"] for r in con.execute(
        "SELECT DISTINCT user_id FROM members WHERE user_id != ? AND chat_id IN (SELECT chat_id FROM members WHERE user_id = ?)",
        (user_id, user_id))]


async def push_presence(user_id):
    with db() as con:
        row = con.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
        ids = contacts(con, user_id)
    if row:
        u = user_json(row)
        await push(ids, {"type": "presence", "user_id": user_id, "online": u["online"], "last_seen": u["last_seen"]})


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, token: str = "", v: str = ""):
    try:
        me_row = auth(token)
    except HTTPException:
        await ws.close(code=4401)
        return
    me_id = me_row["id"]
    if len(online.get(me_id, ())) >= WS_PER_USER:
        await ws.close(code=4429)
        return
    await ws.accept()
    online.setdefault(me_id, set()).add(ws)
    # apps from 0.9 on send their version; older ones leave it empty
    with db() as con:
        if v:
            con.execute("UPDATE users SET app_version = ? WHERE id = ?", (v[:20], me_id))
    try:
        while True:
            ev = json.loads(await ws.receive_text())
            if not hit(("ws", id(ws)), WS_EVENTS_PER_10S, 10):
                continue
            if ev.get("type") == "presence":
                # "last seen" = the last moment the app was on screen, not the background connection
                was = is_active(me_id)
                if ev.get("active"):
                    active.add(ws)
                else:
                    active.discard(ws)
                mark_seen(me_id)
                show = 1 if ev.get("show", True) else 0
                with db() as con:
                    changed = con.execute("UPDATE users SET show_online = ? WHERE id = ? AND show_online != ?",
                                          (show, me_id, show)).rowcount
                if changed or was != is_active(me_id):
                    await push_presence(me_id)
                continue
            if ev.get("type") == "watch":
                await on_watch_event(me_id, ev)
                continue
            if str(ev.get("type", "")).startswith("call_"):
                await on_call_event(me_id, ev)
                continue
            chat_id = int(ev.get("chat_id", 0))
            with db() as con:
                ids = member_ids(con, chat_id)
                if me_id not in ids:
                    continue
                if ev["type"] in ("send", "edit", "delete", "typing"):
                    try:
                        check_channel_post(con, chat_id, me_id)
                    except HTTPException:
                        if ev["type"] == "send":
                            await push([me_id], {"type": "blocked", "chat_id": chat_id, "client_id": ev.get("client_id"), "reason": "В канале пишет только создатель"})
                        continue
                if ev["type"] == "send" and ev.get("forward"):
                    # a copy of a message from any chat I'm in; the copy remembers who wrote it first
                    src = con.execute("SELECT * FROM messages WHERE id = ? AND deleted = 0 AND sender_id != 0", (int(ev["forward"]),)).fetchone()
                    if not src or me_id not in member_ids(con, src["chat_id"]):
                        continue
                    if why := dm_block_error(con, chat_id, me_id):
                        await push([me_id], {"type": "blocked", "chat_id": chat_id, "client_id": ev.get("client_id"), "reason": why})
                        continue
                    fwd = src["fwd_from"] or src["sender_id"]
                    fwd_name = src["fwd_name"] or (con.execute("SELECT name FROM users WHERE id = ?", (fwd,)).fetchone() or {"name": "Кент"})["name"]
                    cur = con.execute(
                        """INSERT INTO messages (chat_id, sender_id, text, created, photo, photo_w, photo_h, media, media_kind, media_name,
                           media_size, duration, wave, thumb, fwd_from, fwd_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        (chat_id, me_id, src["text"], int(time.time()), src["photo"], src["photo_w"], src["photo_h"], src["media"],
                         src["media_kind"], src["media_name"], src["media_size"], src["duration"], src["wave"], src["thumb"], fwd, fwd_name))
                    con.execute("UPDATE members SET last_read = ? WHERE chat_id = ? AND user_id = ?", (cur.lastrowid, chat_id, me_id))
                    con.execute("UPDATE members SET hidden = 0 WHERE chat_id = ?", (chat_id,))
                    row = con.execute("SELECT * FROM messages WHERE id = ?", (cur.lastrowid,)).fetchone()
                    out = {"type": "message", "message": msg_json(row, con), "client_id": ev.get("client_id")}
                elif ev["type"] == "react":
                    mid = int(ev.get("message_id", 0))
                    if not con.execute("SELECT 1 FROM messages WHERE id = ? AND chat_id = ? AND deleted = 0", (mid, chat_id)).fetchone():
                        continue
                    emoji = str(ev.get("emoji", ""))[:16]
                    if emoji:
                        con.execute("INSERT OR REPLACE INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)", (mid, me_id, emoji))
                    else:
                        con.execute("DELETE FROM reactions WHERE message_id = ? AND user_id = ?", (mid, me_id))
                    out = {"type": "reactions", "chat_id": chat_id, "message_id": mid, "reactions": reactions_of(con, mid)}
                elif ev["type"] == "send":
                    text = str(ev.get("text", "")).strip()[:4000]
                    if not text:
                        continue
                    if why := dm_block_error(con, chat_id, me_id):
                        # only the sender hears about it; the phone drops the pending message
                        await push([me_id], {"type": "blocked", "chat_id": chat_id, "client_id": ev.get("client_id"), "reason": why})
                        continue
                    # reply only to a message from the same chat
                    reply_to = int(ev.get("reply_to") or 0)
                    if reply_to and not con.execute("SELECT 1 FROM messages WHERE id = ? AND chat_id = ?",
                                                    (reply_to, chat_id)).fetchone():
                        reply_to = 0
                    cur = con.execute("INSERT INTO messages (chat_id, sender_id, text, created, reply_to) VALUES (?, ?, ?, ?, ?)",
                                      (chat_id, me_id, text, int(time.time()), reply_to))
                    # sending also means I've read everything before it
                    con.execute("UPDATE members SET last_read = ? WHERE chat_id = ? AND user_id = ?",
                                (cur.lastrowid, chat_id, me_id))
                    # a new message brings a hidden chat back for everyone in it
                    con.execute("UPDATE members SET hidden = 0 WHERE chat_id = ?", (chat_id,))
                    row = con.execute("SELECT * FROM messages WHERE id = ?", (cur.lastrowid,)).fetchone()
                    out = {"type": "message", "message": msg_json(row, con), "client_id": ev.get("client_id")}
                elif ev["type"] == "edit":
                    row = own_message(con, chat_id, me_id, ev)
                    text = str(ev.get("text", "")).strip()[:4000]
                    if not row or not text:
                        continue
                    con.execute("UPDATE messages SET text = ?, edited = 1 WHERE id = ?", (text, row["id"]))
                    row = con.execute("SELECT * FROM messages WHERE id = ?", (row["id"],)).fetchone()
                    out = {"type": "edit", "message": msg_json(row, con)}
                elif ev["type"] == "delete":
                    row = own_message(con, chat_id, me_id, ev)
                    if not row:
                        continue
                    # soft delete: the row stays so replies to it can say "Сообщение удалено"
                    con.execute("UPDATE messages SET deleted = 1, text = '' WHERE id = ?", (row["id"],))
                    out = {"type": "delete", "chat_id": chat_id, "message_id": row["id"]}
                elif ev["type"] == "read":
                    mid = int(ev.get("message_id", 0))
                    con.execute("UPDATE members SET last_read = MAX(last_read, ?) WHERE chat_id = ? AND user_id = ?",
                                (mid, chat_id, me_id))
                    out = {"type": "read", "chat_id": chat_id, "user_id": me_id, "message_id": mid}
                elif ev["type"] == "typing":
                    if dm_block_error(con, chat_id, me_id):
                        continue
                    ids = [i for i in ids if i != me_id]
                    out = {"type": "typing", "chat_id": chat_id, "user_id": me_id}
                else:
                    continue
            await push(ids, out)
            if ev.get("type") == "send" and not ev.get("forward") and out.get("type") == "message":
                await bot.enqueue(out["message"]["id"])
    except (WebSocketDisconnect, RuntimeError, ValueError, KeyError):
        pass
    finally:
        online.get(me_id, set()).discard(ws)
        hits.pop(("ws", id(ws)), None)
        if not online.get(me_id):
            for wid in [w for w, room in watchers.items() if me_id in room]:
                await on_watch_event(me_id, {"watch_id": wid, "action": "leave"})
        if ws in active:
            active.discard(ws)
            mark_seen(me_id)
            if not is_active(me_id):
                await push_presence(me_id)


init_db()
from jt_bot import Bot
bot = Bot(globals())


@app.on_event("startup")
async def start_background():
    import asyncio
    asyncio.create_task(remind_gathers())
    await bot.startup()


if __name__ == "__main__":
    import uvicorn
    print(f"\n  Код приглашения: {invite_code()}\n", flush=True)
    uvicorn.run(app, host="0.0.0.0", port=8000)
