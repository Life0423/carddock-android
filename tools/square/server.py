"""卡坞广场测试服务。用户名密码登录，与发布、下载记录共用 SQLite。"""
import argparse
import getpass
import hashlib
import secrets
import hmac
import json
import os
import re
import sqlite3
import unicodedata
import uuid
from pathlib import Path
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

FIELD_KEYS = {"cainiao-empty": {"status"}, "meituan-history": {"status", "orderTime"},
              "meituan-status": {"status"}, "jd-a": {"sku", "title", "variant"}, "jd-b": {"sku", "title", "variant"}}
LISTING_FIELDS = """id, author, version, title, description, capability, display, theme,
    (SELECT count(*) FROM downloads WHERE listing_id = listings.id) AS downloads"""
SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    name TEXT PRIMARY KEY, id TEXT UNIQUE NOT NULL,
    salt BLOB, password_hash BLOB, legacy INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash BLOB PRIMARY KEY, account TEXT NOT NULL, expires INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS retired_invites (token_hash BLOB PRIMARY KEY);
CREATE TABLE IF NOT EXISTS listings (
    id TEXT PRIMARY KEY, author TEXT NOT NULL, local_id TEXT NOT NULL,
    version INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL,
    capability TEXT NOT NULL, active INTEGER NOT NULL, published_at INTEGER NOT NULL,
    UNIQUE(author, local_id)
);
CREATE TABLE IF NOT EXISTS downloads (
    account TEXT NOT NULL, listing_id TEXT NOT NULL, PRIMARY KEY(account, listing_id)
);
"""


class RequestError(Exception):
    def __init__(self, status, message):
        self.status = status
        self.message = message


def listing(row, show_theme):
    """向读者返回公开展示布局；旧版空布局维持原有响应格式。"""
    result = dict(row)
    if not show_theme or result.get("theme") is None:
        result.pop("theme", None)
    display = json.loads(result.pop("display"))
    if display:
        result["display"] = display
    return result


def publication(body):
    """入口只接受公开资料和能力代号。可执行规则由客户端从受支持的能力重建。"""
    if not isinstance(body, dict) or body.keys() - {"display","theme"} != {"localId", "version", "title", "description", "capability"}:
        raise RequestError(400, "发布数据格式无效。")
    local_id, title, description, capability = (body[key] for key in ("localId", "title", "description", "capability"))
    version = body["version"]
    if (not isinstance(local_id, str) or not re.fullmatch(r"[a-zA-Z0-9-]{1,64}", local_id)
            or type(version) is not int or not 1 <= version <= 2_147_483_647
            or not isinstance(capability, str) or capability not in FIELD_KEYS
            or not isinstance(title, str) or not 1 <= len(title.strip()) <= 40
            or not isinstance(description, str) or len(description) > 160):
        raise RequestError(400, "模板名称、说明或能力无效。")
    fields = body.get("display", [])
    if (not isinstance(fields, list) or len(fields) > len(FIELD_KEYS[capability])
            or any(not isinstance(f, dict) or f.keys() != {"key", "label"} or not isinstance(f["key"], str)
                   or f["key"] not in FIELD_KEYS[capability] or not isinstance(f["label"], str)
                   or f["label"] != f["label"].strip() or not f["label"]
                   or len(f["label"].encode("utf-16-le", "surrogatepass")) > 32 for f in fields)
            or len({f["key"] for f in fields}) != len(fields)):
        raise RequestError(400, "展示字段无效。")
    public_text = "".join(char for char in "\n".join([title, description] + [f["label"] for f in fields])
                          if unicodedata.category(char) != "Cf")
    if re.search(r"(?<!\d)(?:\d[\s\-]*){15,}", public_text):
        raise RequestError(400, "请从公开名称或说明中移除疑似订单号码。")
    theme = body.get("theme")
    if theme is not None and (not isinstance(theme, str) or theme not in {
            "FOOD","PARCEL","ORDER","SHOPPING","DIGITAL","PRICE","BILL","FILE","CALENDAR","REMINDER",
            "TRAVEL","TICKET","STUDY","WORK","SPORT","OTHER"}):
        raise RequestError(400, "无法识别图标主题。")
    return local_id, version, title.strip(), description.strip(), capability, json.dumps(fields, ensure_ascii=False, sort_keys=True), theme


def hash_password(password, salt):
    """使用随机盐派生口令摘要，数据库只保存盐与摘要。"""
    return hashlib.scrypt(password.encode("utf-8"), salt=salt, n=32768, r=8, p=1, maxmem=64 << 20)


def check_password_format(password):
    if not isinstance(password, str) or not 8 <= len(password) <= 128 or not password.strip():
        raise RequestError(400, "密码须为 8 至 128 个字符。")
    return password


def issue_session(db, user):
    token = secrets.token_urlsafe(32)
    db.execute("DELETE FROM sessions WHERE expires <= CAST(strftime('%s','now') AS INTEGER)")
    db.execute("INSERT INTO sessions VALUES (?, ?, CAST(strftime('%s','now') AS INTEGER) + 2592000)",
               (hashlib.sha256(token.encode()).digest(), user["name"]))
    return {"name": user["name"], "id": user["id"], "legacy": bool(user["legacy"]), "token": token}


class SquareHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.handle_api()

    def do_POST(self):
        self.handle_api()

    def handle_api(self):
        try:
            with closing(sqlite3.connect(self.server.database)) as db, db:
                db.row_factory = sqlite3.Row
                data = self.dispatch(db)
            self.reply(200, data)
        except RequestError as error:
            self.reply(error.status, {"message": error.message})

    def read_body(self):
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 1 <= size <= 4096:
                raise ValueError()
            value = json.loads(self.rfile.read(size))
            if not isinstance(value, dict):
                raise ValueError()
            return value
        except (ValueError, UnicodeDecodeError):
            raise RequestError(400, "请求内容格式无效。") from None

    def dispatch(self, db):
        endpoint = urlsplit(self.path)
        if self.command == "POST" and endpoint.path in ("/account/register", "/account/login"):
            body = self.read_body()
            name, password = body.get("name"), check_password_format(body.get("password"))
            if not isinstance(name, str) or not re.fullmatch(r"[\w-]{1,32}", name):
                raise RequestError(400, "用户名限 1 至 32 个汉字、字母、数字、横线或下划线。")
            db.execute("BEGIN IMMEDIATE")
            user = db.execute("SELECT * FROM accounts WHERE name = ?", (name,)).fetchone()
            if endpoint.path.endswith("/register"):
                if user:
                    raise RequestError(409, "此用户名已被使用。")
                salt = secrets.token_bytes(16)
                db.execute("INSERT INTO accounts(name,id,salt,password_hash) VALUES (?,?,?,?)",
                           (name, str(uuid.uuid4()), salt, hash_password(password, salt)))
                user = db.execute("SELECT * FROM accounts WHERE name = ?", (name,)).fetchone()
            elif not user or not user["salt"] or not hmac.compare_digest(
                    hash_password(password, user["salt"]), user["password_hash"]):
                raise RequestError(401, "用户名或密码错误。")
            return issue_session(db, user)

        credential = self.headers.get("Authorization", "").removeprefix("Bearer ")
        digest = hashlib.sha256(credential.encode()).digest()
        if self.command == "POST":
            body = self.read_body()
            # 先取得写事务再认证，注销返回后，旧会话不能再提交发布或下载。
            db.execute("BEGIN IMMEDIATE")
        user = db.execute("""SELECT accounts.* FROM accounts JOIN sessions ON accounts.name=sessions.account
                             WHERE token_hash=? AND expires>CAST(strftime('%s','now') AS INTEGER)""", (digest,)).fetchone()
        account = user["name"] if user else None
        # 旧邀请码仅供旧客户端过渡，管理员迁移该账号后立即失效。
        if not account and credential:
            legacy = next((name for name, token in self.server.accounts.items()
                           if hmac.compare_digest(credential.encode(), token.encode())), None)
            if legacy and not db.execute("SELECT 1 FROM retired_invites WHERE token_hash=?", (digest,)).fetchone() and not db.execute("SELECT 1 FROM accounts WHERE name=? AND salt IS NOT NULL",
                                         (legacy,)).fetchone():
                account = legacy
        # 旧客户端严格解析 JSON，仅返回可按旧协议完整呈现的条目。
        show_theme = self.headers.get("X-CardDock-Theme") == "1"
        supports_display = self.headers.get("X-CardDock-Display") == "1"
        visible = "(display = '[]' OR ?)"
        if self.command == "GET" and endpoint.path == "/templates":
            params = parse_qs(endpoint.query)
            query = params.get("q", [""])[0][:80]
            order = ("published_at DESC, rowid DESC" if params.get("sort") == ["latest"]
                     else "downloads DESC, published_at DESC, rowid DESC")
            rows = db.execute(f"""
                SELECT {LISTING_FIELDS}
                FROM listings
                WHERE active = 1 AND {visible} AND (instr(lower(title), lower(?)) OR instr(lower(description), lower(?)))
                ORDER BY {order} LIMIT 100
            """, (supports_display, query, query))
            return [listing(row, show_theme) for row in rows]

        listing_path = re.fullmatch(r"/templates/([a-f0-9-]{36})", endpoint.path)
        if self.command == "GET" and listing_path:
            row = db.execute(f"SELECT {LISTING_FIELDS} FROM listings WHERE active = 1 AND {visible} AND id = ?",
                             (supports_display, listing_path[1])).fetchone()
            if not row:
                raise RequestError(404, "此模板已下架。")
            return listing(row, show_theme)

        if account is None:
            raise RequestError(401, "请在「我的」登录账号。")
        if self.command == "GET" and endpoint.path == "/account":
            return {"name": account}
        if self.command == "POST" and endpoint.path == "/account/logout":
            db.execute("DELETE FROM sessions WHERE token_hash=?", (digest,))
            return {}
        if self.command == "POST" and endpoint.path in ("/account/password", "/account/delete"):
            current = body.get("current")
            if not user or not isinstance(current, str) or not 1 <= len(current) <= 128 or not hmac.compare_digest(
                    hash_password(current, user["salt"]), user["password_hash"]):
                raise RequestError(403, "当前密码错误。")
            if endpoint.path == "/account/delete":
                invite = self.server.accounts.get(account)
                if invite:
                    db.execute("INSERT OR IGNORE INTO retired_invites VALUES (?)", (hashlib.sha256(invite.encode()).digest(),))
                db.execute("DELETE FROM downloads WHERE account=? OR listing_id IN (SELECT id FROM listings WHERE author=?)", (account, account))
                db.execute("DELETE FROM listings WHERE author=?", (account,))
                db.execute("DELETE FROM sessions WHERE account=?", (account,))
                db.execute("DELETE FROM accounts WHERE name=?", (account,))
                return {}
            password = check_password_format(body.get("password"))
            salt = secrets.token_bytes(16)
            db.execute("UPDATE accounts SET salt=?, password_hash=? WHERE name=?",
                       (salt, hash_password(password, salt), account))
            db.execute("DELETE FROM sessions WHERE account=?", (account,))
            return issue_session(db, user)
        if self.command == "GET" and endpoint.path == "/account/templates":
            rows = db.execute(f"SELECT {LISTING_FIELDS} FROM listings WHERE active=1 AND author=? ORDER BY published_at DESC, rowid DESC", (account,))
            return [listing(row, show_theme) for row in rows]
        if self.command == "POST" and endpoint.path == "/templates":
            local_id, version, title, description, capability, display, theme = publication(body)
            old = db.execute("SELECT * FROM listings WHERE author = ? AND local_id = ?", (account, local_id)).fetchone()
            if old and (version < old["version"] or version == old["version"] and
                        (title, description, capability, display, theme) != (old["title"], old["description"], old["capability"], old["display"], old["theme"])):
                raise RequestError(409, "已有相同或更新版本，请先更新个人模板。")
            listing_id = old["id"] if old else str(uuid.uuid4())
            if not old:
                db.execute("INSERT INTO listings (id,author,local_id,version,title,description,capability,active,published_at,display,theme) VALUES (?, ?, ?, ?, ?, ?, ?, 1, CAST(strftime('%s','now') AS INTEGER), ?, ?)",
                           (listing_id, account, local_id, version, title, description, capability, display, theme))
            elif not old["active"] or version > old["version"]:
                db.execute("""UPDATE listings SET version = ?, title = ?, description = ?, capability = ?, display = ?, theme = ?,
                            active = 1, published_at = CAST(strftime('%s','now') AS INTEGER) WHERE id = ?""",
                           (version, title, description, capability, display, theme, listing_id))
            return {"id": listing_id}

        match = re.fullmatch(r"/templates/([a-f0-9-]{36})/(download|unpublish)", endpoint.path)
        if self.command != "POST" or not match:
            raise RequestError(404, "找不到该操作。")
        listing_id, action = match.groups()
        if action == "unpublish":
            if not db.execute("UPDATE listings SET active = 0 WHERE id = ? AND author = ?", (listing_id, account)).rowcount:
                raise RequestError(404, "找不到你发布的模板。")
            return {}

        # 与下架互斥。只有仍在架的条目才允许登记，并返回同一事务读取的版本。
        if not db.execute(f"SELECT 1 FROM listings WHERE id = ? AND active = 1 AND {visible}", (listing_id, supports_display)).fetchone():
            raise RequestError(404, "此模板已下架。")
        db.execute("INSERT OR IGNORE INTO downloads VALUES (?, ?)", (account, listing_id))
        row = db.execute(f"SELECT {LISTING_FIELDS} FROM listings WHERE id = ?", (listing_id,)).fetchone()
        return listing(row, show_theme)

    def reply(self, status, data):
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def initialize_database(connection, legacy_names=()):
    """老广场条目补空布局，沿用发布版本和下载计数。"""
    connection.executescript(SCHEMA)
    if isinstance(legacy_names, dict):
        retired = {r[0] for r in connection.execute("SELECT token_hash FROM retired_invites")}
        legacy_names = [name for name, token in legacy_names.items() if hashlib.sha256(token.encode()).digest() not in retired]
    reserved = set(legacy_names) | {r[0] for r in connection.execute("SELECT author FROM listings UNION SELECT account FROM downloads")}
    connection.executemany("INSERT OR IGNORE INTO accounts(name,id,legacy) VALUES (?,?,1)",
                           [(name, str(uuid.uuid4())) for name in reserved])
    if "theme" not in {r[1] for r in connection.execute("PRAGMA table_info(listings)")} :
        connection.execute("ALTER TABLE listings ADD COLUMN theme TEXT")
    if "display" not in {r[1] for r in connection.execute("PRAGMA table_info(listings)")}:
        connection.execute("ALTER TABLE listings ADD COLUMN display TEXT NOT NULL DEFAULT '[]'")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--backup-to", type=Path, metavar="NEW_DB", help="复制在线数据库到新文件并核验；还原时以备份作为 --db")
    parser.add_argument("--migrate-user", metavar="NAME", help="为已确认身份的旧账号设置初始密码后退出")
    options = parser.parse_args()
    if options.backup_to:
        # 只读打开来源；拒绝覆盖已有数据库，恢复演练和日常备份共用 SQLite 的一致性快照。
        with closing(sqlite3.connect(Path(options.db).resolve().as_uri() + "?mode=ro", uri=True)) as source:
            with options.backup_to.open("xb"):
                pass
            try:
                with closing(sqlite3.connect(options.backup_to)) as target:
                    source.backup(target)
                    if target.execute("PRAGMA quick_check").fetchone() != ("ok",):
                        raise RuntimeError("备份校验失败")
            except Exception:
                options.backup_to.unlink()
                raise
        raise SystemExit(0)
    accounts = json.loads(os.environ.get("CARDDOCK_SQUARE_ACCOUNTS", "{}"))
    if (not isinstance(accounts, dict)
            or any(not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", name) or not isinstance(token, str)
                   or not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", token)
                   for name, token in accounts.items()) or len(set(accounts.values())) != len(accounts)):
        parser.error("需要为每个测试账号设置独立的邀请码")
    with closing(sqlite3.connect(options.db)) as connection, connection:
        initialize_database(connection, accounts)
        connection.commit()
        if options.migrate_user:
            if not connection.execute("SELECT 1 FROM accounts WHERE name=? AND legacy=1 AND salt IS NULL", (options.migrate_user,)).fetchone():
                parser.error("账号不存在或已启用密码")
            password = check_password_format(getpass.getpass("初始密码: "))
            if password != getpass.getpass("确认密码: "):
                parser.error("两次密码不一致")
            salt = secrets.token_bytes(16)
            if not connection.execute("UPDATE accounts SET salt=?, password_hash=? WHERE name=? AND legacy=1 AND salt IS NULL",
                                      (salt, hash_password(password, salt), options.migrate_user)).rowcount:
                parser.error("此账号已迁移")
    if options.migrate_user:
        raise SystemExit(0)
    with ThreadingHTTPServer(("127.0.0.1", options.port), SquareHandler) as server:
        server.database = options.db
        server.accounts = accounts
        server.serve_forever()
