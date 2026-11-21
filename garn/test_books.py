import os, unittest
from app import create_app
from books import schedule
from testutil import make_store

class B(unittest.TestCase):
    def setUp(self):
        os.environ.update(ADMIN_USER="admin", ADMIN_PASSWORD="admin-password-1")
        self.store = make_store(); self.c = create_app(self.store, "x" * 40).test_client()
        self.h = {}
        for u, p, r in [("admin", "admin-password-1", None), ("ops", "operator-pass-12", "operator"), ("aud", "auditor-pass-12", "auditor")]:
            if r: self.c.post("/users", json={"username": u, "password": p, "role": r}, headers=self.h["admin"])
            self.h[u] = {"Authorization": "Bearer " + self.c.post("/auth/login", json={"username": u, "password": p}).get_json()["token"]}
    def post(self, path, who="ops", **j): return self.c.post(path, headers=self.h[who], json=j)
    def get(self, path): return self.c.get(path, headers=self.h["aud"]).get_json()

    def test_schedule_math(self):
        s = schedule(120000, 1200, 12, "2026-01-15")
        self.assertEqual(sum(r["principal_cents"] for r in s), 120000); self.assertEqual(s[-1]["balance_cents"], 0); self.assertEqual(s[0]["due"], "2026-02")

    def test_full_cycle(self):
        pool = self.post("/pools", name="Pool A", period="2026-01", tranches=[
            {"name": "A", "balance_cents": 700000, "coupon_bps": 400}, {"name": "B", "balance_cents": 200000, "coupon_bps": 600},
            {"name": "C", "balance_cents": 100000, "coupon_bps": 800}]).get_json()["pool_id"]
        ln = self.post("/loans", borrower="Acme", principal_cents=1000000, rate_bps=600, term_months=12, start_date="2026-01-15", pool_id=pool).get_json()["loan_id"]
        self.assertEqual(self.post(f"/loans/{ln}/accrue", period="2026-01").get_json()["accrued_cents"], 5000)
        self.assertEqual(self.post(f"/loans/{ln}/accrue", period="2026-01").status_code, 409)
        pay = self.post(f"/loans/{ln}/payments", amount_cents=90000, period="2026-01", event_id="p1").get_json()
        self.assertEqual((pay["interest_cents"], pay["principal_cents"], pay["balance_cents"]), (5000, 85000, 915000))
        self.assertTrue(self.post(f"/loans/{ln}/payments", amount_cents=90000, period="2026-01", event_id="p1").get_json()["duplicate"])
        w = self.post(f"/pools/{pool}/waterfall", period="2026-01").get_json()
        self.assertEqual([x["balance_cents"] for x in w["tranches"]], [615000, 200000, 100000])
        self.assertEqual((w["tranches"][0]["interest_paid"], w["residual_cents"]), (2333, 1000))
        self.assertEqual(self.post(f"/pools/{pool}/waterfall", period="2026-01").status_code, 409)
        tb = self.get("/reports/trial-balance?period=2026-01"); self.assertTrue(tb["balanced"])
        inc = self.get("/reports/income-statement?period=2026-01"); self.assertEqual(inc["net_income_cents"], 1000)
        bs = self.get("/reports/balance-sheet?period=2026-01")
        self.assertTrue(bs["balanced"]); self.assertEqual((bs["total_assets_cents"], bs["unclosed_earnings_cents"]), (915000, 1000))
        self.assertEqual(self.post("/periods/2026-01/close", "ops").status_code, 403)
        self.assertEqual(self.post("/periods/2026-01/close", "admin").get_json()["net_income_cents"], 1000)
        self.assertEqual(self.post("/periods/2026-01/close", "admin").status_code, 409)
        self.assertEqual(self.post(f"/loans/{ln}/payments", amount_cents=10, period="2026-01", event_id="closed-period").status_code, 409)  # closed
        bs = self.get("/reports/balance-sheet?period=2026-01"); self.assertTrue(bs["balanced"]); self.assertEqual(bs["unclosed_earnings_cents"], 0)
        self.assertEqual(self.get("/reports/income-statement?period=2026-01")["net_income_cents"], 1000)  # close entry excluded
        self.assertTrue(self.get("/ledger/verify")["valid"])

    def test_loan_inputs_are_validated(self):
        for values in (
            {"borrower": "", "principal_cents": 10000, "rate_bps": 500, "term_months": 12, "start_date": "2026-01-01"},
            {"borrower": "B", "principal_cents": 10000, "rate_bps": -1, "term_months": 12, "start_date": "2026-01-01"},
            {"borrower": "B", "principal_cents": 10000, "rate_bps": 100001, "term_months": 12, "start_date": "2026-01-01"},
            {"borrower": "B", "principal_cents": 10000, "rate_bps": 500, "term_months": 1201, "start_date": "2026-01-01"},
            {"borrower": "B", "principal_cents": 10000, "rate_bps": 500, "term_months": 12, "start_date": "2026-1-1"},
            {"borrower": "B" * 256, "principal_cents": 10000, "rate_bps": 500, "term_months": 12, "start_date": "2026-01-01"},
        ):
            self.assertEqual(self.post("/loans", **values).status_code, 400)

    def test_paid_loan_payment_retry_is_idempotent(self):
        ln = self.post("/loans", borrower="Payoff", principal_cents=10000, rate_bps=0, term_months=1, start_date="2026-01-01").get_json()["loan_id"]
        first = self.post(f"/loans/{ln}/payments", amount_cents=10000, period="2026-01", event_id="payoff-1")
        self.assertEqual(first.status_code, 200)
        replay = self.post(f"/loans/{ln}/payments", amount_cents=10000, period="2026-01", event_id="payoff-1")
        self.assertTrue(replay.get_json()["duplicate"])
        changed = self.post(f"/loans/{ln}/payments", amount_cents=9999, period="2026-01", event_id="payoff-1")
        self.assertEqual(changed.status_code, 409)

    def test_journal_rejects_fractional_and_backdated_values(self):
        fractional = self.post("/journal", period="2026-01", memo="fractional", lines=[
            {"account": "1000", "debit_cents": 1.5}, {"account": "3100", "credit_cents": 1.5}
        ])
        self.assertEqual(fractional.status_code, 400)
        self.post("/journal", period="2026-03", memo="march", lines=[
            {"account": "1000", "debit_cents": 5}, {"account": "3100", "credit_cents": 5}
        ])
        self.assertEqual(self.post("/periods/2026-03/close", "admin").status_code, 200)
        backdated = self.post("/journal", period="2026-02", memo="late february", lines=[
            {"account": "1000", "debit_cents": 5}, {"account": "3100", "credit_cents": 5}
        ])
        self.assertEqual(backdated.status_code, 409)

    def test_losses_and_close_order(self):
        pool = self.post("/pools", name="P", period="2026-01", tranches=[{"name": "S", "balance_cents": 80000, "coupon_bps": 500}, {"name": "J", "balance_cents": 20000, "coupon_bps": 900}]).get_json()["pool_id"]
        ln = self.post("/loans", borrower="X", principal_cents=100000, rate_bps=1200, term_months=6, start_date="2026-01-10", pool_id=pool).get_json()["loan_id"]
        self.post(f"/loans/{ln}/accrue", period="2026-02")
        self.assertEqual(self.post(f"/loans/{ln}/payments", amount_cents=999999999, period="2026-02", event_id="too-big").status_code, 409)
        self.assertEqual(self.post(f"/loans/{ln}/writeoff", period="2026-02").get_json()["loss_cents"], 101000)
        w = self.post(f"/pools/{pool}/waterfall", period="2026-02").get_json()
        self.assertEqual([x["writedown"] for x in w["tranches"]], [80000, 20000])  # 100,000 loss wipes both tranches, junior first
        bs_after_loss = self.get("/reports/balance-sheet?period=2026-02")
        self.assertTrue(bs_after_loss["balanced"])
        self.assertEqual({x["code"]: x["cents"] for x in bs_after_loss["liabilities"]}.get("2000", 0), 0)
        self.assertEqual(w["certificate_writedown_cents"], 100000)
        self.assertEqual(self.post(f"/loans/{ln}/payments", amount_cents=1, period="2026-02", event_id="after-writeoff").status_code, 409)  # written off
        self.post("/journal", period="2026-03", memo="adj", lines=[{"account": "1000", "debit_cents": 5}, {"account": "3100", "credit_cents": 5}])
        self.assertEqual(self.post("/journal", period="2026-03", memo="bad", lines=[{"account": "1000", "debit_cents": 5}]).status_code, 400)
        self.assertEqual(self.post("/periods/2026-03/close", "admin").status_code, 409)  # earlier periods open
        for p in ("2026-01", "2026-02", "2026-03"): self.assertEqual(self.post(f"/periods/{p}/close", "admin").status_code, 200)

if __name__ == "__main__": unittest.main()
