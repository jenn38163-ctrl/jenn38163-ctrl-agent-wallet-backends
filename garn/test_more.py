import base64, os, unittest
from app import create_app
from testutil import make_store

NOTE = "PROMISSORY NOTE. Borrower promises to pay the principal amount of $250,000.00 at 6.5% interest, dated 2026-01-15. State of New York."
DEED = "DEED OF TRUST. The grantor conveys the property in New York to the trustee. Recorded 2026-01-20."

class M(unittest.TestCase):
    def setUp(self):
        os.environ.update(ADMIN_USER="admin", ADMIN_PASSWORD="admin-password-1")
        self.store = make_store(); self.c = create_app(self.store, "x" * 40).test_client(); self.h = {}
        for u, p, r in [("admin", "admin-password-1", None), ("ops", "operator-pass-12", "operator"), ("aud", "auditor-pass-12", "auditor")]:
            if r: self.c.post("/users", json={"username": u, "password": p, "role": r}, headers=self.h["admin"])
            self.h[u] = {"Authorization": "Bearer " + self.c.post("/auth/login", json={"username": u, "password": p}).get_json()["token"]}
    def post(self, path, who="ops", **j): return self.c.post(path, headers=self.h[who], json=j)
    def get(self, path): return self.c.get(path, headers=self.h["aud"]).get_json()

    def test_investments_fifo_and_marks(self):
        self.post("/investments/buy", symbol="abc", qty=100, unit_cents=1000, period="2026-01")
        self.post("/investments/buy", symbol="abc", qty=100, unit_cents=1200, period="2026-01")
        self.assertEqual(self.post("/investments/mark", symbol="abc", unit_cents=1300, period="2026-01").get_json()["unrealized_change_cents"], 40000)
        s = self.post("/investments/sell", symbol="abc", qty=150, unit_cents=1300, period="2026-02").get_json()
        self.assertEqual((s["cost_cents"], s["proceeds_cents"], s["realized_gain_cents"]), (160000, 195000, 35000))
        p = self.get("/investments")[0]
        self.assertEqual((p["qty"], p["cost_cents"], p["adj_cents"], p["fair_value_cents"]), (50, 60000, 5000, 65000))
        self.assertEqual(self.post("/investments/sell", symbol="abc", qty=99, unit_cents=1, period="2026-02").status_code, 409)
        self.post("/investments/income", symbol="abc", amount_cents=700, period="2026-02")
        self.assertEqual(self.get("/reports/income-statement?period=2026-01")["net_income_cents"], 40000)
        self.assertTrue(self.get("/reports/balance-sheet?period=2026-02")["balanced"])
        total = sum(self.get(f"/reports/income-statement?period=2026-0{k}")["net_income_cents"] for k in (1, 2))
        self.assertEqual(total, 195000 + 65000 - 220000 + 700)  # sale proceeds + remaining fair value - cost + income

    def test_software_capitalization(self):
        for invalid_name in ("", "   ", "x" * 201):
            self.assertEqual(self.post("/software/projects", name=invalid_name, useful_life_months=12).status_code, 400)
        for invalid_life in (0, -1, 1201, 1.5, True):
            self.assertEqual(self.post("/software/projects", name="Invalid", useful_life_months=invalid_life).status_code, 400)
        pid = self.post("/software/projects", name="Ledger", useful_life_months=12).get_json()["project_id"]
        self.assertFalse(self.post(f"/software/projects/{pid}/costs", phase="preliminary", amount_cents=10000, period="2026-01").get_json()["capitalized"])
        self.assertTrue(self.post(f"/software/projects/{pid}/costs", phase="development", amount_cents=120000, period="2026-02").get_json()["capitalized"])
        self.assertEqual(self.post(f"/software/projects/{pid}/amortize", period="2026-03").status_code, 409)  # not in service
        self.post(f"/software/projects/{pid}/in-service", period="2026-03")
        self.assertEqual(self.post(f"/software/projects/{pid}/costs", phase="development", amount_cents=5, period="2026-03").status_code, 409)
        self.post(f"/software/projects/{pid}/costs", phase="post_implementation", amount_cents=5000, period="2026-03")
        periods = [f"2026-{m:02d}" for m in range(3, 13)] + ["2027-01", "2027-02"]
        for k, per in enumerate(periods):
            r = self.post(f"/software/projects/{pid}/amortize", period=per); self.assertEqual(r.get_json()["amortization_cents"], 10000)
            if k == 0: self.assertEqual(self.post(f"/software/projects/{pid}/amortize", period=per).status_code, 409)
        self.assertEqual(self.post(f"/software/projects/{pid}/amortize", period="2027-03").status_code, 409)  # fully amortized
        bs = self.get("/reports/balance-sheet?period=2027-02")
        accts = {a["code"]: a["cents"] for a in bs["assets"]}
        self.assertEqual((accts["1400"], accts["1410"]), (120000, -120000)); self.assertTrue(bs["balanced"])
        uneven = self.post("/software/projects", name="U", useful_life_months=7).get_json()["project_id"]
        self.post(f"/software/projects/{uneven}/costs", phase="development", amount_cents=100000, period="2026-01")
        self.post(f"/software/projects/{uneven}/in-service", period="2026-01")
        tot = sum(self.post(f"/software/projects/{uneven}/amortize", period=f"2026-{m:02d}").get_json()["amortization_cents"] for m in range(1, 8))
        self.assertEqual(tot, 100000)

    def test_documents_and_jurisdiction(self):
        ln = self.post("/loans", borrower="Acme", principal_cents=100000, rate_bps=1200, term_months=12, start_date="2026-01-15").get_json()["loan_id"]
        self.assertEqual(self.post("/jurisdictions", who="ops", code="US-NY", name="New York", required_docs=["note", "deed"]).status_code, 403)
        self.post("/jurisdictions", who="admin", code="US-NY", name="New York", max_rate_bps=1600, required_docs=["note", "deed"])
        self.assertEqual(self.post("/loans", borrower="B", principal_cents=1000, rate_bps=2000, term_months=6, start_date="2026-01-15", jurisdiction="US-NY").status_code, 409)
        ny = self.post("/loans", borrower="B", principal_cents=1000, rate_bps=1200, term_months=6, start_date="2026-01-15", jurisdiction="US-NY").get_json()["loan_id"]
        d = self.post("/documents", filename="note.txt", content_text=NOTE, loan_id=ny).get_json()
        self.assertEqual((d["category"], d["jurisdiction"], d["fields"]["amounts"], d["fields"]["rates"]), ("note", "US-NY", ["$250,000.00"], ["6.5%"]))
        self.assertTrue(self.post("/documents", filename="again.txt", content_text=NOTE).get_json()["duplicate"])
        c = self.get(f"/loans/{ny}/compliance"); self.assertEqual((c["missing"], c["compliant"]), (["deed"], False))
        self.post("/documents", filename="deed.txt", content_text=DEED, loan_id=ny)
        self.assertTrue(self.get(f"/loans/{ny}/compliance")["compliant"])
        self.assertTrue(self.get(f"/loans/{ln}/compliance")["compliant"] is False)  # DEFAULT needs a note
        b = self.post("/documents", filename="appraisal.pdf", content_b64=base64.b64encode(b"\x89PNG\xff\xfe\x00").decode()).get_json()
        self.assertEqual((b["category"], b["text_extracted"]), ("appraisal", False))
        self.assertEqual(self.post("/documents", filename="x", content_b64="!!!").status_code, 400)
        self.assertEqual(len(self.get("/documents?category=note")), 1)
        r = self.get("/jurisdictions/US-ZZ/route"); self.assertFalse(r["rule_found"]); self.assertEqual(r["required_docs"], ["note"])
        self.assertTrue(self.get("/ledger/verify")["valid"])

if __name__ == "__main__": unittest.main()
