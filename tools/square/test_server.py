"""通过真实 SQLite 与 HTTP 校验展示字段的跨账号往返和旧数据迁移。"""
import json
import sqlite3
import tempfile
import threading
import subprocess
import sys
import unittest
from contextlib import closing
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from server import SCHEMA, SquareHandler, ThreadingHTTPServer, initialize_database


class AccountContractTest(unittest.TestCase):
    def test_registration_rotation_revocation_and_reserved_authors(self):
        with tempfile.TemporaryDirectory() as folder:
            path = str(Path(folder) / "accounts.db")
            with closing(sqlite3.connect(path)) as db, db:
                initialize_database(db, ["old-author"])
            with ThreadingHTTPServer(("127.0.0.1", 0), SquareHandler) as http:
                http.database = path
                http.accounts = {}
                threading.Thread(target=http.serve_forever, daemon=True).start()

                def call(route, body=None, token=None, status=200):
                    data = json.dumps(body).encode() if body is not None else None
                    headers = {"Authorization": "Bearer " + token} if token else {}
                    request = Request(f"http://127.0.0.1:{http.server_port}/account" + route, data=data, headers=headers)
                    try:
                        response = urlopen(request)
                    except HTTPError as error:
                        response = error
                    with response:
                        self.assertEqual(status, response.code)
                        return json.load(response)

                try:
                    alice = {"name": "alice", "password": "first-password"}
                    call("/register", dict(alice, name="old-author"), status=409)
                    first = call("/register", alice)
                    call("/register", alice, status=409)
                    call("/login", dict(alice, password="incorrect-pass"), status=401)
                    second = call("/login", alice)
                    call("/password", {"current": "wrong", "password": "next-password"}, second["token"], 403)
                    rotated = call("/password", {"current": alice["password"], "password": "next-password"}, second["token"])
                    self.assertEqual(first["id"], rotated["id"])
                    for old in [first, second]:
                        call("", token=old["token"], status=401)
                    call("/login", alice, status=401)
                    self.assertEqual([], call("/templates", token=rotated["token"]))
                    call("/logout", {}, rotated["token"])
                    call("", token=rotated["token"], status=401)
                    bob = call("/register", dict(alice, name="bob"))
                    self.assertNotEqual(first["id"], bob["id"])
                    with closing(sqlite3.connect(path)) as db:
                        self.assertNotEqual(bob["token"].encode(), db.execute("SELECT token_hash FROM sessions").fetchone()[0])
                    # 注销须重新确认密码；其它会话失效，同名重新注册使用新账号 ID。
                    second_bob = call("/login", dict(alice, name="bob"))
                    call("/delete", {"current": "wrong"}, bob["token"], 403)
                    call("", token=second_bob["token"])
                    call("/delete", {"current": alice["password"]}, bob["token"])
                    call("", token=second_bob["token"], status=401)
                    call("/login", dict(alice, name="bob"), status=401)
                    recreated = call("/register", dict(alice, name="bob"))
                    self.assertNotEqual(bob["id"], recreated["id"])
                finally:
                    http.shutdown()


class DisplayContractTest(unittest.TestCase):
    def test_online_backup_restores_published_template_without_overwriting_target(self):
        with tempfile.TemporaryDirectory() as folder:
            src, snap, restored = (Path(folder) / name for name in ("live.db", "backup.db", "restored.db"))
            with closing(sqlite3.connect(src)) as db, db:
                initialize_database(db, ["fixture"])
                db.execute("INSERT INTO listings(id,author,local_id,version,title,description,capability,active,published_at,display) VALUES ('one','fixture','local',1,'公开示例','','jd-a',1,0,'[]')")
            script = str(Path(__file__).with_name("server.py"))
            for source, target in [(src, snap), (snap, restored)]:
                subprocess.run([sys.executable, script, "--db", str(source), "--backup-to", str(target)], check=True)
            with closing(sqlite3.connect(restored)) as db:
                self.assertEqual(("公开示例",), db.execute("SELECT title FROM listings").fetchone())
            self.assertNotEqual(0, subprocess.run([sys.executable, script, "--db", str(src), "--backup-to", str(restored)],
                                 capture_output=True).returncode)

    def test_author_reader_and_legacy_listing(self):
        with tempfile.TemporaryDirectory() as folder:
            db_path = str(Path(folder) / "square.db")
            with closing(sqlite3.connect(db_path)) as db, db:
                db.executescript(SCHEMA)
                db.execute("INSERT INTO listings VALUES ('legacy','owner','old',1,'旧模板','','jd-a',1,0)")
                initialize_database(db)
            with ThreadingHTTPServer(("127.0.0.1", 0), SquareHandler) as http:
                http.database = db_path
                http.accounts = {"owner": "test-owner", "reader": "test-reader"}
                threading.Thread(target=http.serve_forever, daemon=True).start()
                def request(path, who="reader", body=None, layout=True, theme=True):
                    data = json.dumps(body).encode() if body is not None else None
                    req = Request(f"http://127.0.0.1:{http.server_port}" + path, data=data,
                                  headers={"Authorization": "Bearer " + http.accounts[who],
                                           "X-CardDock-Display": "1" if layout else "", "X-CardDock-Theme": "1" if theme else ""})
                    with urlopen(req) as response:
                        return json.load(response)
                try:
                    self.assertNotIn("display", request("/templates")[0])
                    fields = [{"key": "title", "label": "名称"}, {"key": "variant", "label": "款式"}]
                    content = dict(localId="local-a", version=1, title="京东 A", description="",
                                   capability="jd-a", display=fields, theme="PRICE")
                    public_id = request("/templates", "owner", content)["id"]
                    self.assertEqual(["旧模板"], [x["title"] for x in request("/templates", layout=False)])
                    for entry in (request("/templates/" + public_id),
                                  request("/templates/" + public_id + "/download", body={})):
                        self.assertEqual(fields, entry["display"])
                        self.assertEqual("PRICE", entry["theme"])
                        self.assertNotIn("localId", entry)
                    self.assertNotIn("theme", request("/templates/" + public_id, theme=False))
                    for invalid in ([{"key": "address", "label": "地址"}], fields[:1] * 2,
                                    [{"key": "title", "label": "123456789012345"}],
                                    [{"key": "title", "label": "🌿" * 16}],
                                    [{"key": "title", "label": "   " + "字" * 16 + "   "}]):
                        with self.assertRaises(HTTPError) as err:
                            request("/templates", "owner", dict(content, display=invalid))
                        self.assertEqual(400, err.exception.code)
                        err.exception.close()
                finally:
                    http.shutdown()


if __name__ == "__main__":
    unittest.main()
