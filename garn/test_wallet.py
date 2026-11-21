import json, os, threading, unittest
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from app import create_app
from testutil import make_store

class Fake(BaseHTTPRequestHandler):
    wallets, idem = {}, {}
    def log_message(self, *a): pass
    def _send(self, code, obj): b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(b)
    def _auth(self):
        if self.headers.get("Authorization") != "Bearer testkey": self._send(401, {"error": "INVALID_CREDENTIAL"}); return False
        return True
    def do_GET(self):
        if not self._auth(): return
        w = self.wallets.get(self.path.rsplit("/", 1)[-1]); self._send(200, w) if w else self._send(404, {"error": "NOT_FOUND"})
    def do_POST(self):
        if not self._auth(): return
        d = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/v1/wallets":
            wid = f"w{len(self.wallets)+1}"; self.wallets[wid] = {"id": wid, "balance": "0.000000", "monthly_limit": d["monthlyLimit"], "status": "ACTIVE"}; return self._send(201, self.wallets[wid])
        wid = self.path.split("/")[3]; key = self.headers.get("Idempotency-Key")
        if key in self.idem: return self._send(201, self.idem[key])
        w = self.wallets[wid]; w["balance"] = f"{Decimal(w['balance']) + Decimal(d['amount']):.6f}"; self.idem[key] = {"ok": True}; self._send(201, {"ok": True})

class W(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), Fake); threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
    @classmethod
    def tearDownClass(cls): cls.srv.shutdown()
    def setUp(self):
        Fake.wallets, Fake.idem = {}, {}
        os.environ.update(APP_ENV="development", ADMIN_USER="admin", ADMIN_PASSWORD="admin-password-1", AGENT_WALLET_URL=f"http://127.0.0.1:{self.srv.server_port}", AGENT_WALLET_API_KEY="testkey")
        self.store = make_store(); self.c = create_app(self.store, "x" * 40).test_client(); self.h = {}
        for u, p, r in [("admin", "admin-password-1", None), ("ops", "operator-pass-12", "operator"), ("aud", "auditor-pass-12", "auditor")]:
            if r: self.c.post("/users", json={"username": u, "password": p, "role": r}, headers=self.h["admin"])
            self.h[u] = {"Authorization": "Bearer " + self.c.post("/auth/login", json={"username": u, "password": p}).get_json()["token"]}
        post = lambda path, **j: self.c.post(path, headers=self.h["ops"], json=j)
        a = post("/assets", issuer="JMG", asset_class="RE", vehicle="TRUST", series="A", year=2026, pool="C1", name="P", principal_cents=1000000).get_json()["id"]
        post(f"/assets/{a}/collateral", description="deeds", value_cents=4000000); post(f"/assets/{a}/tokenize", token_type="trust_unit", units=100)
        p = post("/parties", name="Agent Owner").get_json()["pid"]; self.c.post(f"/parties/{p}/kyc", headers=self.h["admin"], json={"status": "verified"})
        self.card = post("/cards", asset_id=a, party_id=p, limit_cents=1500000).get_json()["card_id"]; self.post = post
    def test_lifecycle(self):
        r = self.post(f"/cards/{self.card}/wallet", per_request_cents=100000); self.assertEqual(r.status_code, 201)
        wid = r.get_json()["wallet_id"]; self.assertEqual(Fake.wallets[wid]["balance"], "15000.000000")
        self.assertEqual(self.post(f"/cards/{self.card}/wallet").status_code, 201); self.assertEqual(Fake.wallets[wid]["balance"], "15000.000000")  # no double funding
        self.assertEqual(len(Fake.wallets), 1)
        Fake.wallets[wid]["balance"] = "14000.000000"  # the agent spent $1,000 through the wallet service
        st = self.c.get(f"/cards/{self.card}/wallet", headers=self.h["aud"]).get_json(); self.assertEqual((st["drift_cents"], st["in_sync"]), (100000, False))
        s = self.post(f"/cards/{self.card}/wallet/sync").get_json(); self.assertEqual((s["applied_cents"], s["card_balance_cents"], s["in_sync"]), (100000, 100000, True))
        self.assertEqual(self.post(f"/cards/{self.card}/wallet/sync").get_json()["applied_cents"], 0)
        self.assertEqual(self.post(f"/cards/{self.card}/wallet/repay", amount_cents=40000).status_code, 400)  # event_id required
        self.assertEqual(self.post(f"/cards/{self.card}/wallet/repay", amount_cents=40000, event_id=True).status_code, 400)
        self.assertEqual(self.post(f"/cards/{self.card}/wallet/repay", amount_cents=40000, event_id="x" * 201).status_code, 400)
        r = self.post(f"/cards/{self.card}/wallet/repay", amount_cents=40000, event_id="r1").get_json()
        self.assertEqual((r["card_balance_cents"], r["wallet_balance_cents"], r["in_sync"]), (60000, 1440000, True))
        self.post(f"/cards/{self.card}/wallet/repay", amount_cents=40000, event_id="r1")  # retry: no double effect
        st = self.c.get(f"/cards/{self.card}/wallet", headers=self.h["aud"]).get_json(); self.assertEqual((st["card_balance_cents"], st["wallet_balance_cents"]), (60000, 1440000))
        self.assertTrue(self.c.get("/ledger/verify", headers=self.h["aud"]).get_json()["valid"])
    def test_errors(self):
        os.environ["AGENT_WALLET_API_KEY"] = "wrong"
        self.assertEqual(self.post(f"/cards/{self.card}/wallet").status_code, 502)
        os.environ["AGENT_WALLET_API_KEY"] = ""
        self.assertEqual(self.post(f"/cards/{self.card}/wallet").status_code, 503)
        os.environ["AGENT_WALLET_API_KEY"] = "testkey"
        self.assertEqual(self.c.get(f"/cards/{self.card}/wallet", headers=self.h["aud"]).status_code, 404)  # not linked yet
        self.assertEqual(self.c.post(f"/cards/{self.card}/wallet", headers=self.h["aud"], json={}).status_code, 403)

if __name__ == "__main__": unittest.main()
