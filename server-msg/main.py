"""JustTalk server: HTTP for login and history, one WebSocket per client for live events.

Run:  .venv/bin/python main.py   (listens on 0.0.0.0:8000)
Android emulator reaches the Mac at http://10.0.2.2:8000, a real phone at http://<Mac LAN IP>:8000.
"""
import json
import random
import secrets
import sqlite3
import time
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel

BASE = Path(__file__).parent
DB_PATH = BASE / "justtalk.db"
INVITE_PATH = BASE / "invite.txt"

app = FastAPI(title="JustTalk")


# ---------- storage ----------

def db():
    con = sqlite3.connect(DB_PATH)
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
        # message actions: reply, edit, delete
        cols = [r["name"] for r in con.execute("PRAGMA table_info(messages)")]
        if "reply_to" not in cols:
            con.execute("ALTER TABLE messages ADD COLUMN reply_to INTEGER NOT NULL DEFAULT 0")
        if "edited" not in cols:
            con.execute("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        if "deleted" not in cols:
            con.execute("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")


def invite_code():
    # one shared 9-digit code for the whole friend group, made on first start
    if not INVITE_PATH.exists():
        INVITE_PATH.write_text("".join(random.choice("0123456789") for _ in range(9)))
    return INVITE_PATH.read_text().strip()


def user_json(row):
    return {"id": row["id"], "name": row["name"], "username": row["username"], "about": row["about"]}


def msg_json(row, con=None):
    out = {"id": row["id"], "chat_id": row["chat_id"], "sender_id": row["sender_id"],
           "text": row["text"], "created": row["created"],
           "reply_to": row["reply_to"], "edited": bool(row["edited"])}
    # short copy of the quoted message, for when the client hasn't loaded the original
    if con is not None and row["reply_to"]:
        q = con.execute("SELECT * FROM messages WHERE id = ?", (row["reply_to"],)).fetchone()
        if q:
            out["reply"] = {"id": q["id"], "sender_id": q["sender_id"], "text": q["text"][:120],
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
    last = con.execute("SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 ORDER BY id DESC LIMIT 1",
                       (chat_id,)).fetchone()
    my_read = con.execute("SELECT last_read FROM members WHERE chat_id = ? AND user_id = ?",
                          (chat_id, me_id)).fetchone()["last_read"]
    unread = con.execute("SELECT COUNT(*) FROM messages WHERE chat_id = ? AND id > ? AND sender_id NOT IN (?, 0) AND deleted = 0",
                         (chat_id, my_read, me_id)).fetchone()[0]
    # how far the others have read: drives the double tick on my messages
    others_read = con.execute("SELECT MIN(last_read) FROM members WHERE chat_id = ? AND user_id != ?",
                              (chat_id, me_id)).fetchone()[0] or 0
    if chat["kind"] == "dm":
        other = next((m for m in members if m["id"] != me_id), members[0])
        title = other["name"]
    else:
        title = chat["title"]
    return {"id": chat_id, "kind": chat["kind"], "title": title, "members": members,
            "last": msg_json(last, con) if last else None, "unread": unread, "others_read": others_read}


# ---------- http ----------

class RegisterIn(BaseModel):
    invite: str
    name: str
    username: str


def valid_username(u):
    return 3 <= len(u) <= 20 and all(c.isascii() and (c.isalnum() or c == "_") for c in u)


@app.post("/register")
def register(body: RegisterIn):
    if body.invite.strip() != invite_code():
        raise HTTPException(403, "Неверный код приглашения")
    name = body.name.strip()
    username = body.username.strip().lower().lstrip("@")
    if not name:
        raise HTTPException(400, "Нужно имя")
    if not valid_username(username):
        raise HTTPException(400, "Юзернейм: 3–20 символов, латиница, цифры и _")
    token = secrets.token_hex(24)
    try:
        with db() as con:
            cur = con.execute("INSERT INTO users (name, username, token, created) VALUES (?, ?, ?, ?)",
                              (name, username, token, int(time.time())))
            row = con.execute("SELECT * FROM users WHERE id = ?", (cur.lastrowid,)).fetchone()
    except sqlite3.IntegrityError:
        raise HTTPException(409, f"@{username} уже занят")
    return {"token": token, "user": user_json(row)}


@app.get("/me")
def me(authorization: str = Header("")):
    return user_json(auth(authorization))


# ---------- profile ----------

@app.get("/username/{username}")
def username_free(username: str, authorization: str = Header("")):
    me_row = auth(authorization)
    u = username.strip().lower().lstrip("@")
    with db() as con:
        row = con.execute("SELECT id FROM users WHERE username = ?", (u,)).fetchone()
    # my own current username counts as free
    return {"username": u, "valid": valid_username(u), "free": not row or row["id"] == me_row["id"]}


class ProfileIn(BaseModel):
    name: str
    username: str
    about: str = ""


@app.post("/me")
async def update_me(body: ProfileIn, authorization: str = Header("")):
    me_row = auth(authorization)
    name = body.name.strip()[:40]
    username = body.username.strip().lower().lstrip("@")
    about = body.about.strip()[:140]
    if not name:
        raise HTTPException(400, "Нужно имя")
    if not valid_username(username):
        raise HTTPException(400, "Юзернейм: 3–20 символов, латиница, цифры и _")
    try:
        with db() as con:
            con.execute("UPDATE users SET name = ?, username = ?, about = ? WHERE id = ?",
                        (name, username, about, me_row["id"]))
            row = con.execute("SELECT * FROM users WHERE id = ?", (me_row["id"],)).fetchone()
    except sqlite3.IntegrityError:
        raise HTTPException(409, f"@{username} уже занят")
    # everyone online reloads chats and users to see the new name
    await push(list(online), {"type": "chat"})
    return user_json(row)


@app.get("/users")
def users(authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        rows = con.execute("SELECT * FROM users WHERE id != ? ORDER BY name", (me_row["id"],)).fetchall()
    return [user_json(r) for r in rows]


@app.get("/chats")
def chats(authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        ids = [r["chat_id"] for r in con.execute("SELECT chat_id FROM members WHERE user_id = ?", (me_row["id"],))]
        result = [chat_json(con, i, me_row["id"]) for i in ids]
    # newest activity first; empty chats go last
    result.sort(key=lambda c: c["last"]["id"] if c["last"] else 0, reverse=True)
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
        return chat_json(con, chat_id, me_row["id"])


@app.get("/chats/{chat_id}/messages")
def messages(chat_id: int, before: int = 0, authorization: str = Header("")):
    me_row = auth(authorization)
    with db() as con:
        if me_row["id"] not in member_ids(con, chat_id):
            raise HTTPException(403, "Не твой чат")
        rows = con.execute(
            "SELECT * FROM messages WHERE chat_id = ? AND deleted = 0 AND (? = 0 OR id < ?) ORDER BY id DESC LIMIT 100",
            (chat_id, before, before)).fetchall()
        return [msg_json(r, con) for r in reversed(rows)]


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
    if chat["kind"] != "group":
        raise HTTPException(400, "Это не группа")


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
        check_member(con, chat_id, me_row["id"])
        con.execute("DELETE FROM members WHERE chat_id = ? AND user_id = ?", (chat_id, me_row["id"]))
        rest = member_ids(con, chat_id)
        msg = system_message(con, chat_id, f"{me_row['name']} вышел из группы")
    await push(rest + [me_row["id"]], {"type": "chat", "chat_id": chat_id})
    await push(rest, {"type": "message", "message": msg})
    return {"ok": True}


# ---------- websocket ----------

# user_id -> open sockets (one user can be online from several devices)
online: dict[int, set[WebSocket]] = {}


async def push(user_ids, event):
    data = json.dumps(event, ensure_ascii=False)
    for uid in user_ids:
        for ws in list(online.get(uid, ())):
            try:
                await ws.send_text(data)
            except Exception:
                online.get(uid, set()).discard(ws)


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, token: str = ""):
    try:
        me_row = auth(token)
    except HTTPException:
        await ws.close(code=4401)
        return
    me_id = me_row["id"]
    await ws.accept()
    online.setdefault(me_id, set()).add(ws)
    try:
        while True:
            ev = json.loads(await ws.receive_text())
            chat_id = int(ev.get("chat_id", 0))
            with db() as con:
                ids = member_ids(con, chat_id)
                if me_id not in ids:
                    continue
                if ev["type"] == "send":
                    text = str(ev.get("text", "")).strip()[:4000]
                    if not text:
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
                    ids = [i for i in ids if i != me_id]
                    out = {"type": "typing", "chat_id": chat_id, "user_id": me_id}
                else:
                    continue
            await push(ids, out)
    except (WebSocketDisconnect, RuntimeError, ValueError, KeyError):
        pass
    finally:
        online.get(me_id, set()).discard(ws)


init_db()

if __name__ == "__main__":
    import uvicorn
    print(f"\n  Код приглашения: {invite_code()}\n", flush=True)
    uvicorn.run(app, host="0.0.0.0", port=8001)
