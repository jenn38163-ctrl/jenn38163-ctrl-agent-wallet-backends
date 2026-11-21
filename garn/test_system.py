import hashlib, hmac, json, os, unittest
import adapters
from app import create_app
from testutil import make_store

SECRET = "x" * 40
def sig(secret, raw): return hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()

class T(unittest.TestCase):
    def setUp(self):
        os.environ.update(APP_ENV="development", ADMIN_USER="admin", ADMIN_PASSWORD="admin-password-1", CELTIC_WEBHOOK_SECRET="s3cret")
        self.store = make_store(); self.app = create_app(self.store, SECRET); self.c = self.app.test_client()
        self.admin = self.login("admin", "admin-password-1")
        self.c.post("/users", json={"username": "ops", "password": "operator-pass-12", "role": "operator"}, headers=self.admin)
        self.c.post("/users", json={"username": "aud", "password": "auditor-pass-12", "role": "auditor"}, headers=self.admin)
        self.ops, self.aud = self.login("ops", "operator-pass-12"), self.login("aud", "auditor-pass-12")
    def login(self, u, p):
        return {"Authorization": "Bearer " + self.c.post("/auth/login", json={"username": u, "password": p}).get_json()["token"]}
    def asset(self, principal=1000000):
        return self.c.post("/assets", headers=self.ops, json=dict(issuer="JMG", asset_class="RE", vehicle="TRUST", series="A",
            year=2026, pool="C1", name="Pool", principal_cents=principal)).get_json()["id"]

    def test_auth_and_roles(self):
        self.assertEqual(self.c.get("/assets").status_code, 401)
        self.assertEqual(self.c.get("/assets", headers=self.aud).status_code, 200)
        self.assertEqual(self.c.post("/assets", headers=self.aud, json={}).status_code, 403)
        self.assertEqual(self.c.post("/users", headers=self.ops, json={}).status_code, 403)
        self.assertEqual(self.c.post("/auth/login", json={"username": "ops", "password": "nope"}).status_code, 401)
        # Existing access tokens must reflect the current database role, not a stale JWT claim.
        self.store.conn.execute("UPDATE users SET role='auditor' WHERE username='ops'")
        self.assertEqual(self.c.post("/assets", headers=self.ops, json={}).status_code, 403)

    def test_simulated_events_are_disabled_in_production(self):
        previous = os.environ.get("APP_ENV")
        os.environ["APP_ENV"] = "production"
        try:
            production = create_app(self.store, SECRET).test_client()
            response = production.post("/events", headers=self.ops, json={})
            self.assertEqual(response.status_code, 404)
        finally:
            if previous is None: os.environ.pop("APP_ENV", None)
            else: os.environ["APP_ENV"] = previous
    def test_sqlite_enforces_foreign_keys(self):
        self.assertEqual(self.store.conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)
        with self.assertRaises(Exception):
            self.store.conn.execute(
                "INSERT INTO collateral VALUES(?,?,?,?,?)",
                ("orphan", "missing-asset", "invalid", 1, "2026-01-01T00:00:00+00:00"),
            )

    def test_ids_increment(self):
        self.assertEqual(self.asset(), "JMG-RE-TRUST-A-2026-C1-000001"); self.assertEqual(self.asset(), "JMG-RE-TRUST-A-2026-C1-000002")
    def test_channel_rules(self):
        a = self.asset(); ch = lambda n: self.c.post(f"/assets/{a}/channel", headers=self.ops, json={"channel": n})
        self.assertEqual(ch("Lending").status_code, 409)
        self.c.post(f"/assets/{a}/collateral", headers=self.ops, json={"description": "deeds", "value_cents": 1200000})
        self.assertEqual(ch("Secondary Markets").status_code, 409)
        self.assertEqual(self.c.post(f"/assets/{a}/tokenize", headers=self.ops, json={"token_type": "trust_unit", "units": 100}).status_code, 200)
        self.assertEqual(ch("Secondary Markets").status_code, 200)
    def test_events_and_dedupe(self):
        a = self.asset(); ev = dict(asset_id=a, source="Celtic", kind="payment", amount_cents=250000, event_id="e1")
        self.assertEqual(self.c.post("/events", headers=self.ops, json=ev).get_json()["principal_cents"], 750000)
        self.assertTrue(self.c.post("/events", headers=self.ops, json=ev).get_json()["duplicate"])
        self.assertEqual(self.c.get(f"/assets/{a}", headers=self.aud).get_json()["principal_cents"], 750000)
        big = dict(ev, event_id="e2", amount_cents=10**9)
        self.assertEqual(self.c.post("/events", headers=self.ops, json=big).status_code, 409)
        overflow = dict(asset_id=a, source="Celtic", kind="disbursement", amount_cents=2**63 - 1, event_id="overflow")
        self.assertEqual(self.c.post("/events", headers=self.ops, json=overflow).status_code, 409)
        altered_retry = dict(ev, amount_cents=200000)
        self.assertEqual(self.c.post("/events", headers=self.ops, json=altered_retry).status_code, 409)
    def test_webhook_signature(self):
        a = self.asset(); raw = json.dumps({"event_id": "w1", "asset_id": a, "type": "payment", "amount_cents": 100}).encode()
        bad = self.c.post("/webhooks/celtic", data=raw, headers={"X-Signature": "00"})
        self.assertEqual(bad.status_code, 401)
        ok = self.c.post("/webhooks/celtic", data=raw, headers={"X-Signature": sig("s3cret", raw)})
        self.assertEqual(ok.status_code, 200); self.assertEqual(ok.get_json()["principal_cents"], 999900)
        self.assertEqual(self.c.post("/webhooks/cross-river", data=raw).status_code, 503)  # secret not configured
    def test_anchor(self):
        a = self.asset(); h = "0x" + "ab" * 32
        adapters.polygon_receipt = lambda tx: {"status": "0x1", "blockNumber": "0x10", "transactionHash": tx}
        self.assertEqual(self.c.post(f"/assets/{a}/anchor", headers=self.ops, json={"tx_hash": h}).status_code, 200)
        self.assertEqual(self.c.post(f"/assets/{a}/anchor", headers=self.ops, json={"tx_hash": "bad"}).status_code, 400)
    def test_ledger_integrity(self):
        a = self.asset(); self.c.post(f"/assets/{a}/collateral", headers=self.ops, json={"description": "d", "value_cents": 5})
        v = self.c.get("/ledger/verify", headers=self.aud).get_json(); self.assertTrue(v["valid"]); self.assertEqual(v["blocks"], 2)
        if self.store.pg:  # the database trigger itself must refuse the edit
            with self.assertRaises(Exception), self.store._pg.connect(self.store.url) as c: c.execute("UPDATE ledger SET data='{}' WHERE idx=0")
            return
        self.store.conn.execute("UPDATE ledger SET data=replace(data,'1000000','1') WHERE idx=0")
        v = self.c.get("/ledger/verify", headers=self.aud).get_json(); self.assertFalse(v["valid"]); self.assertEqual(v["broken_at"], 0)


    def post(self, path, who, **j): return self.c.post(path, headers=who, json=j)
    def test_trust_flow(self):
        a = self.asset(); self.post(f"/assets/{a}/collateral", self.ops, description="deeds", value_cents=4000000)
        cr = self.c.get(f"/assets/{a}/credit", headers=self.aud).get_json()
        self.assertEqual((cr["eligible_cents"], cr["risk_grade"], cr["available_credit_cents"]), (3000000, "A", 2000000))
        self.assertEqual(self.post(f"/assets/{a}/tokenize", self.ops, token_type="bogus", units=10).status_code, 400)
        self.post(f"/assets/{a}/tokenize", self.ops, token_type="trust_unit", units=1000)
        self.assertEqual(self.post(f"/assets/{a}/tokenize", self.ops, token_type="trust_unit", units=5).status_code, 409)
        p1 = self.post("/parties", self.ops, name="Investor One").get_json()["pid"]
        p2 = self.post("/parties", self.ops, name="Investor Two").get_json()["pid"]
        self.assertEqual(self.post(f"/assets/{a}/issue", self.ops, party_id=p1, units=10).status_code, 409)  # no KYC
        self.assertEqual(self.post(f"/parties/{p1}/kyc", self.ops, status="verified").status_code, 403)    # admin only
        self.post(f"/parties/{p1}/kyc", self.admin, status="verified")
        self.assertEqual(self.post(f"/assets/{a}/issue", self.ops, party_id=p1, units=600).status_code, 200)
        self.assertEqual(self.post(f"/assets/{a}/issue", self.ops, party_id=p1, units=500).status_code, 409)
        card = self.post("/cards", self.ops, asset_id=a, party_id=p1, limit_cents=1500000).get_json()["card_id"]
        self.assertEqual(self.post("/cards", self.ops, asset_id=a, party_id=p1, limit_cents=600000).status_code, 409)
        au = lambda n, e: self.post(f"/cards/{card}/authorize", self.ops, amount_cents=n, event_id=e).get_json()
        self.assertTrue(au(1000000, "a1")["approved"]); self.assertTrue(au(1000000, "a1")["duplicate"])
        self.assertFalse(au(600000, "a2")["approved"])
        self.assertEqual(self.post(f"/cards/{card}/repay", self.ops, amount_cents=400000, event_id="r1").get_json()["balance_cents"], 600000)
        self.assertTrue(self.post(f"/cards/{card}/repay", self.ops, amount_cents=400000, event_id="r1").get_json()["duplicate"])
        self.assertEqual(self.post(f"/cards/{card}/repay", self.ops, amount_cents=400001, event_id="r1").status_code, 409)
        self.assertEqual(self.post(f"/cards/{card}/repay", self.ops, amount_cents=9000000, event_id="r2").status_code, 409)
        self.post(f"/assets/{a}/channel", self.ops, channel="Secondary Markets")
        self.assertEqual(self.post("/transfers", self.ops, asset_id=a, from_party=p1, to_party=p2, units=100).status_code, 409)  # p2 no KYC
        self.post(f"/parties/{p2}/kyc", self.admin, status="verified")
        self.assertEqual(self.post("/transfers", self.ops, asset_id=a, from_party=p1, to_party=p2, units=100).status_code, 200)
        h = {x["pid"]: x["units"] for x in self.c.get(f"/assets/{a}/holdings", headers=self.aud).get_json()}
        self.assertEqual(h, {p1: 500, p2: 100})
        self.assertTrue(self.c.get("/ledger/verify", headers=self.aud).get_json()["valid"])
    def test_grade_d_blocks_credit(self):
        a = self.asset(); self.post(f"/assets/{a}/collateral", self.ops, description="x", value_cents=1100000)  # LTV ~91%
        self.assertEqual(self.c.get(f"/assets/{a}/credit", headers=self.aud).get_json()["risk_grade"], "D")

if __name__ == "__main__": unittest.main()
