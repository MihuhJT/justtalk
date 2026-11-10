"""Admin commands for the JustTalk panel on the Mac.

The panel runs them over ssh:  python admin.py <command> [args]
Every command prints one JSON line (ASCII only, so the Windows console code page can't break it).
"""
import hashlib
import json
import os
import secrets
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = Path(os.environ.get("JT_BASE") or Path(__file__).parent / "server")
DB_PATH = BASE / "justtalk.db"
MEDIA = BASE / "media"
LOG = BASE / "server.log"
BACKUPS = BASE.parent / "backups"
TASK = "JustTalkServer"
LOCAL = "http://127.0.0.1:8000"


def out(data):
    print(json.dumps(data))


def db():
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    return con


def admin_token():
    path = BASE / "admin_token.txt"
    return path.read_text().strip() if path.exists() else ""


def server_up():
    # any HTTP answer (401 without a token) means uvicorn is alive
    try:
        urllib.request.urlopen(LOCAL + "/me", timeout=3)
        return True
    except urllib.error.HTTPError:
        return True
    except Exception:
        return False


def presence():
    """(in the app now, only connected in the background) — user id lists from the running server."""
    req = urllib.request.Request(LOCAL + "/admin/online", headers={"X-Admin": admin_token()})
    try:
        d = json.loads(urllib.request.urlopen(req, timeout=3).read())
        return d["ids"], d.get("bg", [])
    except Exception:
        return [], []


def status():
    up = server_up()
    today = int(time.time()) - 24 * 3600
    with db() as con:
        one = lambda sql, *a: con.execute(sql, a).fetchone()[0]
        stats = {
            "users": one("SELECT COUNT(*) FROM users"),
            "dms": one("SELECT COUNT(*) FROM chats WHERE kind = 'dm'"),
            "groups": one("SELECT COUNT(*) FROM chats WHERE kind = 'group'"),
            "messages": one("SELECT COUNT(*) FROM messages WHERE deleted = 0 AND sender_id != 0"),
            "messages_day": one("SELECT COUNT(*) FROM messages WHERE deleted = 0 AND sender_id != 0 AND created > ?", today),
        }
    files = list(MEDIA.glob("*")) if MEDIA.exists() else []
    disk = shutil.disk_usage(BASE.anchor or "/")
    out({
        "up": up,
        "online": len(presence()[0]) if up else 0,
        "background": len(presence()[1]) if up else 0,
        **stats,
        "media_files": len(files),
        "media_mb": round(sum(f.stat().st_size for f in files) / 1e6, 1),
        "db_mb": round(DB_PATH.stat().st_size / 1e6, 2),
        "disk_free_gb": round(disk.free / 1e9, 1),
        "disk_total_gb": round(disk.total / 1e9, 1),
        "invite": (BASE / "invite.txt").read_text().strip() if (BASE / "invite.txt").exists() else "",
        "log_updated": int(LOG.stat().st_mtime) if LOG.exists() else 0,
    })


def users():
    owner = (BASE / "owner.txt").read_text().strip() if (BASE / "owner.txt").exists() else ""
    on, bg = map(set, presence())
    with db() as con:
        rows = con.execute("""
            SELECT u.*, (SELECT COUNT(*) FROM messages m WHERE m.sender_id = u.id AND m.deleted = 0) AS msgs,
                        (SELECT MAX(created) FROM messages m WHERE m.sender_id = u.id) AS last
            FROM users u ORDER BY u.id""").fetchall()
    out([{"id": r["id"], "name": r["name"], "username": r["username"], "about": r["about"],
          "created": r["created"], "msgs": r["msgs"], "last": r["last"] or 0,
          "avatar": bool(r["avatar"]), "birthday": r["birthday"], "gender": r["gender"],
          "owner": str(r["id"]) == owner, "online": r["id"] in on, "background": r["id"] in bg,
          "version": r["app_version"], "seen": r["last_seen"],
          "has_password": bool(r["pass_hash"]) if "pass_hash" in r.keys() else False} for r in rows])


def chats():
    with db() as con:
        rows = con.execute("SELECT * FROM chats ORDER BY id").fetchall()
        result = []
        for c in rows:
            names = [r["name"] for r in con.execute(
                "SELECT u.name FROM users u JOIN members m ON m.user_id = u.id WHERE m.chat_id = ?", (c["id"],))]
            n, last = con.execute("SELECT COUNT(*), MAX(created) FROM messages WHERE chat_id = ? AND deleted = 0 AND sender_id != 0",
                                  (c["id"],)).fetchone()
            result.append({"id": c["id"], "kind": c["kind"], "title": c["title"] or " / ".join(names),
                           "members": names, "msgs": n, "last": last or 0})
    out(result)


def drop_files(con, where, args):
    # a forwarded message shares the file with the original: delete only files nothing else points to
    gone = {r["id"] for r in con.execute(f"SELECT id FROM messages WHERE {where}", args)}
    for col in ("photo", "media", "thumb"):
        for r in con.execute(f"SELECT DISTINCT {col} AS f FROM messages WHERE {col} IS NOT NULL AND {col} != '' AND {where}", args):
            users = {x["id"] for x in con.execute(f"SELECT id FROM messages WHERE photo = ? OR media = ? OR thumb = ?", (r["f"],) * 3)}
            if users <= gone:
                (MEDIA / r["f"]).unlink(missing_ok=True)


def delete_chat(chat_id):
    chat_id = int(chat_id)
    with db() as con:
        drop_files(con, "chat_id = ?", (chat_id,))
        con.execute("DELETE FROM messages WHERE chat_id = ?", (chat_id,))
        con.execute("DELETE FROM members WHERE chat_id = ?", (chat_id,))
        con.execute("DELETE FROM chats WHERE id = ?", (chat_id,))
    out({"ok": True})


def delete_user(user_id):
    """The account, its dms (both sides), its messages in groups and its avatar. The phone gets "bad token" and logs out."""
    user_id = int(user_id)
    with db() as con:
        row = con.execute("SELECT avatar FROM users WHERE id = ?", (user_id,)).fetchone()
        if not row:
            return out({"ok": False, "error": "no such user"})
        dms = [r["chat_id"] for r in con.execute(
            "SELECT m.chat_id FROM members m JOIN chats c ON c.id = m.chat_id WHERE m.user_id = ? AND c.kind = 'dm'", (user_id,))]
    with db() as con:
        for chat_id in dms:
            drop_files(con, "chat_id = ?", (chat_id,))
            con.execute("DELETE FROM messages WHERE chat_id = ?", (chat_id,))
            con.execute("DELETE FROM members WHERE chat_id = ?", (chat_id,))
            con.execute("DELETE FROM chats WHERE id = ?", (chat_id,))
        drop_files(con, "sender_id = ?", (user_id,))
        con.execute("UPDATE messages SET deleted = 1, text = '', photo = NULL, media = NULL, thumb = '' WHERE sender_id = ?", (user_id,))
        con.execute("DELETE FROM members WHERE user_id = ?", (user_id,))
        con.execute("DELETE FROM blocks WHERE user_id = ? OR blocked_id = ?", (user_id, user_id))
        con.execute("DELETE FROM removed WHERE user_id = ? OR other_id = ?", (user_id, user_id))
        con.execute("DELETE FROM friends WHERE user_id = ? OR other_id = ?", (user_id, user_id))
        con.execute("DELETE FROM spots WHERE owner_id = ?", (user_id,))
        con.execute("DELETE FROM users WHERE id = ?", (user_id,))
    if row["avatar"]:
        (MEDIA / row["avatar"]).unlink(missing_ok=True)
    out({"ok": True, "dms_deleted": len(dms)})


def log(lines="200"):
    if not LOG.exists():
        return out({"lines": []})
    text = LOG.read_bytes()[-200_000:].decode("utf-8", "replace")
    out({"lines": text.splitlines()[-int(lines):]})


def restart():
    subprocess.run(["schtasks", "/End", "/TN", TASK], capture_output=True)
    time.sleep(2)
    subprocess.run(["schtasks", "/Run", "/TN", TASK], capture_output=True)
    for _ in range(20):
        time.sleep(1)
        if server_up():
            return out({"ok": True})
    out({"ok": False, "error": "server did not come back in 20 s"})


def set_invite(code):
    if not (len(code) == 9 and code.isdigit()):
        return out({"ok": False, "error": "need 9 digits"})
    (BASE / "invite.txt").write_text(code)
    out({"ok": True})


def backup():
    # sqlite's own backup: a consistent copy even while the server writes
    BACKUPS.mkdir(exist_ok=True)
    path = BACKUPS / time.strftime("justtalk-%Y%m%d-%H%M%S.db")
    with db() as src, sqlite3.connect(path) as dst:
        src.backup(dst)
    out({"ok": True, "path": str(path)})


def reset_password(user_id):
    """A kent forgot the password: sets a fresh temporary one that the panel shows, to pass on to him."""
    user_id = int(user_id)
    temp = "".join(secrets.choice("abcdefghjkmnpqrstuvwxyz23456789") for _ in range(8))
    # same format as hash_password() in main.py
    salt = secrets.token_bytes(16)
    n = 200_000
    stored = f"pbkdf2${n}${salt.hex()}${hashlib.pbkdf2_hmac('sha256', temp.encode(), salt, n).hex()}"
    with db() as con:
        if not con.execute("SELECT 1 FROM users WHERE id = ?", (user_id,)).fetchone():
            return out({"ok": False, "error": "no such user"})
        con.execute("UPDATE users SET pass_hash = ?, login_fails = 0, locked_until = 0 WHERE id = ?", (stored, user_id))
    out({"ok": True, "password": temp})


COMMANDS = {"status": status, "users": users, "chats": chats, "delete_chat": delete_chat, "delete_user": delete_user,
            "log": log, "restart": restart, "set_invite": set_invite, "backup": backup,
            "reset_password": reset_password}

if __name__ == "__main__":
    try:
        COMMANDS[sys.argv[1]](*sys.argv[2:])
    except Exception as e:
        out({"ok": False, "error": f"{type(e).__name__}: {e}"})
