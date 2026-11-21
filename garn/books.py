"""Books: double-entry general ledger with period close, loan subledger, REMIC waterfall.
Simplified on purpose: have your accountant and tax counsel review treatment before relying on it."""
import json, re, uuid
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
from flask import g, jsonify, request
from store import StoreError, now

CHART = [("1000", "Cash", "asset"), ("1100", "Loans Receivable", "asset"), ("1200", "Accrued Interest Receivable", "asset"),
         ("2000", "REMIC Certificates Payable", "liability"), ("3000", "Retained Earnings", "equity"),
         ("3100", "Residual Interest", "equity"), ("3200", "Residual Distributions", "equity"),
         ("4000", "Interest Income", "income"), ("5000", "Interest Expense", "expense"), ("5100", "Credit Loss Expense", "expense"),
         ("1300", "Investments at Cost", "asset"), ("1310", "Unrealized FV Adjustment", "asset"), ("1400", "Capitalized Software", "asset"),
         ("1410", "Accumulated Amortization", "asset"), ("4200", "Investment Income", "income"), ("4300", "Realized Gain (Loss)", "income"),
         ("4310", "Unrealized Gain (Loss)", "income"), ("4400", "REMIC Principal Loss Recovery", "income"),
         ("5200", "Amortization Expense", "expense"), ("5300", "Software Operating Expense", "expense")]
PERIOD_RX = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")
DEBIT_NATURE = ("asset", "expense")

def period_ok(p):
    if not isinstance(p, str) or not PERIOD_RX.match(p): raise StoreError("period must look like 2026-01")
    return p
def rnd(x): return int(Decimal(x).quantize(Decimal(1), ROUND_HALF_UP))
def monthly_interest(balance, bps): return (balance * bps + 60000) // 120000

class GL:
    def __init__(self, s):
        self.s = s
        with s.tx() as t:
            for a in CHART:
                if not t.q("SELECT 1 FROM accounts WHERE code=?", (a[0],)): t.q("INSERT INTO accounts VALUES(?,?,?)", a)

    def post(self, t, period, memo, lines, ref):  # lines: (account, debit_cents, credit_cents)
        period_ok(period)
        if not isinstance(memo, str) or not memo.strip() or len(memo) > 500:
            raise StoreError("journal memo must contain 1 to 500 characters")
        if not isinstance(ref, str) or not ref:
            raise StoreError("journal reference is required")
        if not isinstance(lines, (list, tuple)) or not lines or len(lines) > 200:
            raise StoreError("journal needs 1 to 200 lines")
        checked = []
        for line in lines:
            if not isinstance(line, (list, tuple)) or len(line) != 3:
                raise StoreError("each journal line needs account, debit_cents, credit_cents")
            account, debit, credit = line
            if not isinstance(account, str) or not account.strip():
                raise StoreError("journal account code is required")
            for amount in (debit, credit):
                if isinstance(amount, bool) or not isinstance(amount, int) or not 0 <= amount <= 9_223_372_036_854_775_807:
                    raise StoreError("journal amounts must be whole non-negative cents within the database range")
            if debit and credit:
                raise StoreError("a journal line cannot contain both a debit and a credit")
            if debit or credit:
                checked.append((account, debit, credit))
        lines = checked
        if not lines:
            raise StoreError("journal must contain non-zero lines")

        # A closed later period is a hard backdating boundary, even when an earlier
        # month had no journals at the time it was closed.
        boundary = t.q("SELECT MAX(period) AS period FROM periods WHERE status='closed'")[0]["period"]
        if boundary and period <= boundary and not ref.startswith("CLOSE:"):
            raise StoreError(f"period {period} is closed or precedes the latest closed period {boundary}", 409)
        d, c = sum(l[1] for l in lines), sum(l[2] for l in lines)
        if d != c or d == 0: raise StoreError("journal must balance and be above zero")
        for account, _, _ in lines:
            if not t.q("SELECT 1 FROM accounts WHERE code=?", (account,)):
                raise StoreError(f"unknown account {account}")
        jid = str(uuid.uuid4())
        t.q("INSERT INTO journal VALUES(?,?,?,?,?)", (jid, period, memo, ref, now()))
        for k, (account, dr, cr) in enumerate(lines, 1):
            t.q("INSERT INTO journal_lines VALUES(?,?,?,?,?)", (jid, k, account, dr, cr))
        self.s._append(t, "JOURNAL", {"jid": jid, "period": period, "ref": ref, "total_cents": d})
        return jid

    def manual(self, period, memo, lines, actor):
        if not isinstance(lines, list) or not lines or len(lines) > 200:
            raise StoreError("journal needs 1 to 200 lines")
        try:
            ls = [(line["account"], line.get("debit_cents", 0), line.get("credit_cents", 0)) for line in lines]
        except (KeyError, TypeError, AttributeError):
            raise StoreError("lines need account, debit_cents, credit_cents")
        with self.s.tx() as t:
            self.s._lock(t)
            return {"jid": self.post(t, period, memo, ls, "MANUAL:" + actor)}

    def _bal(self, t, upto=None, only=None, no_close=False):
        sql = ("SELECT l.account AS code, a.name AS name, a.type AS type, SUM(l.debit_cents) AS d, SUM(l.credit_cents) AS c "
               "FROM journal_lines l JOIN journal j ON j.jid=l.jid JOIN accounts a ON a.code=l.account WHERE 1=1")
        args = []
        if upto: sql += " AND j.period<=?"; args.append(upto)
        if only: sql += " AND j.period=?"; args.append(only)
        if no_close: sql += " AND substr(j.ref,1,6)<>'CLOSE:'"
        rows = t.q(sql + " GROUP BY l.account, a.name, a.type ORDER BY l.account", args)
        for r in rows: r["balance_cents"] = r["d"] - r["c"] if r["type"] in DEBIT_NATURE else r["c"] - r["d"]
        return rows

    def trial_balance(self, period):
        with self.s.tx() as t: rows = self._bal(t, upto=period_ok(period))
        out = [{"code": r["code"], "name": r["name"], "debit_cents": max(r["d"] - r["c"], 0), "credit_cents": max(r["c"] - r["d"], 0)} for r in rows]
        td, tc = sum(r["debit_cents"] for r in out), sum(r["credit_cents"] for r in out)
        return {"period": period, "accounts": out, "total_debit_cents": td, "total_credit_cents": tc, "balanced": td == tc}

    def income_statement(self, period):
        with self.s.tx() as t: rows = self._bal(t, only=period_ok(period), no_close=True)
        pick = lambda ty: [{"code": r["code"], "name": r["name"], "cents": r["balance_cents"]} for r in rows if r["type"] == ty]
        inc, exp = pick("income"), pick("expense")
        return {"period": period, "income": inc, "expenses": exp, "net_income_cents": sum(x["cents"] for x in inc) - sum(x["cents"] for x in exp)}

    def balance_sheet(self, period):
        with self.s.tx() as t: rows = self._bal(t, upto=period_ok(period))
        pick = lambda ty: [{"code": r["code"], "name": r["name"], "cents": r["balance_cents"]} for r in rows if r["type"] == ty]
        a, l, e = pick("asset"), pick("liability"), pick("equity")
        earn = sum(x["cents"] for x in pick("income")) - sum(x["cents"] for x in pick("expense"))
        ta, tl, te = sum(x["cents"] for x in a), sum(x["cents"] for x in l), sum(x["cents"] for x in e) + earn
        return {"as_of": period, "assets": a, "liabilities": l, "equity": e, "unclosed_earnings_cents": earn,
                "total_assets_cents": ta, "total_liabilities_cents": tl, "total_equity_cents": te, "balanced": ta == tl + te}

    def close(self, period, actor):
        period_ok(period)
        with self.s.tx() as t:
            self.s._lock(t)
            st = t.q("SELECT status FROM periods WHERE period=?", (period,))
            if st and st[0]["status"] == "closed": raise StoreError("period already closed", 409)
            early = t.q("SELECT DISTINCT period FROM journal WHERE period<? AND period NOT IN (SELECT period FROM periods WHERE status='closed') ORDER BY period", (period,))
            if early: raise StoreError(f"close {early[0]['period']} first", 409)
            lines, net = [], 0
            for r in self._bal(t, only=period, no_close=True):
                b = r["balance_cents"]
                if r["type"] == "income" and b: lines.append((r["code"], max(b, 0), max(-b, 0))); net += b
                if r["type"] == "expense" and b: lines.append((r["code"], max(-b, 0), max(b, 0))); net -= b
            if net: lines.append(("3000", max(-net, 0), max(net, 0)))
            if lines: self.post(t, period, f"Close {period}", lines, f"CLOSE:{period}")
            if st: t.q("UPDATE periods SET status='closed', closed_at=? WHERE period=?", (now(), period))
            else: t.q("INSERT INTO periods VALUES(?,?,?)", (period, "closed", now()))
            self.s._append(t, "PERIOD_CLOSE", {"period": period, "net_income_cents": net, "actor": actor})
        return {"period": period, "status": "closed", "net_income_cents": net}

def schedule(principal, rate_bps, n, start):
    r = Decimal(rate_bps) / Decimal(120000)
    pay = rnd(Decimal(principal) / n if r == 0 else Decimal(principal) * r / (1 - (1 + r) ** (-n)))
    y, m, bal, rows = int(start[:4]), int(start[5:7]), principal, []
    for k in range(1, n + 1):
        i = rnd(Decimal(bal) * r); p = bal if k == n else pay - i
        m += 1
        if m > 12: m, y = 1, y + 1
        bal -= p
        rows.append({"n": k, "due": f"{y}-{m:02d}", "payment_cents": p + i, "interest_cents": i, "principal_cents": p, "balance_cents": bal})
    return rows

def rule_for(t, code):
    r = t.q("SELECT * FROM jurisdiction_rules WHERE code=?", (code,))
    found = bool(r); r = (r or t.q("SELECT * FROM jurisdiction_rules WHERE code='DEFAULT'"))[0]
    return {**r, "required_docs": json.loads(r["required_docs"]), "rule_found": found}

class Loans:
    def __init__(self, s, gl): self.s, self.gl = s, gl
    def _loan(self, t, lid):
        r = t.q("SELECT * FROM loans WHERE loan_id=?", (lid,))
        if not r: raise StoreError("loan not found", 404)
        return r[0]
    def _active(self, t, lid):
        l = self._loan(t, lid)
        if l["status"] != "active": raise StoreError(f"loan is {l['status']}", 409)
        return l
    def get(self, lid):
        with self.s.tx() as t: return self._loan(t, lid)
    def list(self):
        with self.s.tx() as t: return t.q("SELECT loan_id, borrower, balance_cents, accrued_cents, status, pool_id FROM loans ORDER BY start_date, loan_id")
    def schedule(self, lid):
        l = self.get(lid); return schedule(l["principal_cents"], l["rate_bps"], l["term_months"], l["start_date"])

    def originate(self, borrower, principal, rate_bps, term, start, asset_id, pool_id, actor, jurisdiction=None):
        if not isinstance(borrower, str) or not borrower.strip() or len(borrower.strip()) > 255:
            raise StoreError("borrower must contain 1 to 255 characters")
        borrower = borrower.strip()
        if isinstance(principal, bool) or not isinstance(principal, int) or not 0 < principal <= 9_223_372_036_854_775_807:
            raise StoreError("principal_cents must be positive whole cents within the database range")
        if isinstance(rate_bps, bool) or not isinstance(rate_bps, int) or not 0 <= rate_bps <= 100_000:
            raise StoreError("rate_bps must be a whole number from 0 to 100000")
        if isinstance(term, bool) or not isinstance(term, int) or not 1 <= term <= 1200:
            raise StoreError("term_months must be a whole number from 1 to 1200")
        if not isinstance(start, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", start):
            raise StoreError("start_date must be YYYY-MM-DD")
        try: datetime.strptime(start, "%Y-%m-%d")
        except ValueError: raise StoreError("start_date must be a valid calendar date")
        for value, label in ((asset_id, "asset_id"), (pool_id, "pool_id")):
            if value is not None and (not isinstance(value, str) or not value.strip() or len(value) > 128):
                raise StoreError(f"{label} must be a non-empty string when supplied")
        if jurisdiction is not None and (not isinstance(jurisdiction, str) or not re.fullmatch(r"[A-Za-z0-9-]{1,16}", jurisdiction.strip())):
            raise StoreError("jurisdiction must be a short alphanumeric jurisdiction code")
        with self.s.tx() as t:
            self.s._lock(t)
            if asset_id: self.s._asset(t, asset_id)
            if pool_id and not t.q("SELECT 1 FROM pools WHERE pool_id=?", (pool_id,)): raise StoreError("pool not found", 404)
            jc = (jurisdiction or "DEFAULT").upper(); rule = rule_for(t, jc)
            if rule["max_rate_bps"] is not None and rate_bps > rule["max_rate_bps"]: raise StoreError(f"rate exceeds the {rule['max_rate_bps']} bps cap configured for {jc}", 409)
            lid = str(uuid.uuid4())
            self.gl.post(t, start[:7], f"Fund loan {lid}", [("1100", principal, 0), ("1000", 0, principal)], "LOAN:" + lid)
            t.q("INSERT INTO loans VALUES(?,?,?,?,?,?,?,?,?,?,?)", (lid, asset_id, pool_id, borrower, principal, rate_bps, term, start, principal, 0, "active"))
            t.q("INSERT INTO loan_meta VALUES(?,?)", (lid, jc))
            self.s._append(t, "LOAN_ORIGINATE", {"loan": lid, "principal_cents": principal, "jurisdiction": jc, "actor": actor})
        return {"loan_id": lid}

    def _txn(self, t, lid, kind, period, i, p, key=None):
        tid = key or str(uuid.uuid4())
        if t.q("SELECT 1 FROM loan_txns WHERE tid=?", (tid,)): return False
        t.q("INSERT INTO loan_txns VALUES(?,?,?,?,?,?,?)", (tid, lid, kind, period, i, p, now())); return True

    def accrue(self, lid, period, actor):
        period_ok(period)
        with self.s.tx() as t:
            self.s._lock(t); l = self._active(t, lid)
            if not self._txn(t, lid, "accrual", period, 0, 0, f"accr:{lid}:{period}"): raise StoreError("interest already accrued for this period", 409)
            i = monthly_interest(l["balance_cents"], l["rate_bps"])
            if i: self.gl.post(t, period, f"Accrue interest {lid}", [("1200", i, 0), ("4000", 0, i)], "LOAN:" + lid)
            t.q("UPDATE loans SET accrued_cents=? WHERE loan_id=?", (l["accrued_cents"] + i, lid))
            t.q("UPDATE loan_txns SET interest_cents=? WHERE tid=?", (i, f"accr:{lid}:{period}"))
            self.s._append(t, "LOAN_ACCRUE", {"loan": lid, "period": period, "interest_cents": i, "actor": actor})
        return {"accrued_cents": i}

    def pay(self, lid, amount, period, ext_id, actor):
        period_ok(period)
        if amount <= 0: raise StoreError("amount must be above zero")
        if not isinstance(ext_id, str) or not ext_id.strip() or len(ext_id) > 255:
            raise StoreError("event_id is required and must contain 1 to 255 characters")
        with self.s.tx() as t:
            self.s._lock(t)
            l = self._loan(t, lid)
            tid = f"pay:{lid}:{ext_id}"
            prior = t.q("SELECT kind, period, interest_cents, principal_cents FROM loan_txns WHERE tid=?", (tid,))
            if prior:
                old = prior[0]
                if old["kind"] != "payment" or old["period"] != period or old["interest_cents"] + old["principal_cents"] != amount:
                    raise StoreError("event_id was already used with a different payment", 409)
                return {"duplicate": True, "balance_cents": l["balance_cents"], "accrued_cents": l["accrued_cents"]}
            if l["status"] != "active":
                raise StoreError(f"loan is {l['status']}", 409)
            i = min(amount, l["accrued_cents"]); p = amount - i
            if p > l["balance_cents"]: raise StoreError("payment exceeds balance plus accrued interest", 409)
            self._txn(t, lid, "payment", period, i, p, tid)
            self.gl.post(t, period, f"Payment {lid}", [("1000", amount, 0), ("1200", 0, i), ("1100", 0, p)], "LOAN:" + lid)
            bal, acc = l["balance_cents"] - p, l["accrued_cents"] - i
            t.q("UPDATE loans SET balance_cents=?, accrued_cents=?, status=? WHERE loan_id=?", (bal, acc, "paid" if bal == 0 and acc == 0 else "active", lid))
            self.s._append(t, "LOAN_PAYMENT", {"loan": lid, "interest_cents": i, "principal_cents": p, "actor": actor})
        return {"interest_cents": i, "principal_cents": p, "balance_cents": bal}

    def write_off(self, lid, period, actor):
        period_ok(period)
        with self.s.tx() as t:
            self.s._lock(t); l = self._active(t, lid); b, a = l["balance_cents"], l["accrued_cents"]
            self.gl.post(t, period, f"Write off {lid}", [("5100", b + a, 0), ("1100", 0, b), ("1200", 0, a)], "LOAN:" + lid)
            t.q("UPDATE loans SET balance_cents=0, accrued_cents=0, status='written_off' WHERE loan_id=?", (lid,))
            self._txn(t, lid, "writeoff", period, a, b)
            self.s._append(t, "LOAN_WRITEOFF", {"loan": lid, "loss_cents": b + a, "actor": actor})
        return {"loss_cents": b + a}

class Remic:
    """Sequential-pay waterfall: tranche interest by seniority, then losses to the most junior tranche,
    then principal to the most senior; leftover cash goes to the residual holder.
    This is a cash-flow calculator, not REMIC tax qualification."""
    def __init__(self, s, gl): self.s, self.gl = s, gl
    def create(self, name, period, tranches, actor):
        period_ok(period)
        if not isinstance(tranches, list) or not 1 <= len(tranches) <= 50:
            raise StoreError("provide 1 to 50 tranches")
        names = [x.get("name") if isinstance(x, dict) else None for x in tranches]
        if (not all(isinstance(n, str) and n.strip() and len(n) <= 100 for n in names)
                or len(set(names)) != len(names)):
            raise StoreError("tranches need unique names, most senior first")
        tr = []
        for item in tranches:
            balance, coupon = item.get("balance_cents"), item.get("coupon_bps")
            if (isinstance(balance, bool) or not isinstance(balance, int) or not 0 < balance <= 9_223_372_036_854_775_807
                    or isinstance(coupon, bool) or not isinstance(coupon, int) or not 0 <= coupon <= 100_000):
                raise StoreError("tranche balance must be positive whole cents and coupon_bps must be 0 to 100000")
            tr.append((item["name"], balance, coupon))
        pid, total = "POOL-" + uuid.uuid4().hex[:8].upper(), sum(b for _, b, _ in tr)
        if total > 9_223_372_036_854_775_807:
            raise StoreError("combined tranche balance exceeds the database range")
        if not isinstance(name, str) or not name.strip() or len(name) > 200:
            raise StoreError("pool name must contain 1 to 200 characters")
        with self.s.tx() as t:
            self.s._lock(t); t.q("INSERT INTO pools VALUES(?,?,?)", (pid, name, now()))
            for k, (n, b, c) in enumerate(tr): t.q("INSERT INTO tranches VALUES(?,?,?,?,?,?)", (pid, n, k, b, c, 0))
            self.gl.post(t, period, f"Issue certificates {pid}", [("1000", total, 0), ("2000", 0, total)], "POOL:" + pid)
            self.s._append(t, "POOL_CREATE", {"pool": pid, "total_cents": total, "actor": actor})
        return {"pool_id": pid, "total_cents": total}

    def get(self, pid):
        with self.s.tx() as t:
            if not t.q("SELECT 1 FROM pools WHERE pool_id=?", (pid,)): raise StoreError("pool not found", 404)
            runs = t.q("SELECT period, detail FROM waterfall_runs WHERE pool_id=? ORDER BY period", (pid,))
            return {"pool_id": pid, "tranches": t.q("SELECT name, seq, balance_cents, coupon_bps, shortfall_cents FROM tranches WHERE pool_id=? ORDER BY seq", (pid,)),
                    "runs": [{"period": r["period"], **json.loads(r["detail"])} for r in runs]}

    def run(self, pid, period, actor):
        period_ok(period)
        with self.s.tx() as t:
            self.s._lock(t)
            if not t.q("SELECT 1 FROM pools WHERE pool_id=?", (pid,)): raise StoreError("pool not found", 404)
            if t.q("SELECT 1 FROM waterfall_runs WHERE pool_id=? AND period=?", (pid, period)): raise StoreError("waterfall already run for this period", 409)
            q = lambda kind, col: t.q(f"SELECT COALESCE(SUM(x.{col}),0) AS v FROM loan_txns x JOIN loans l ON l.loan_id=x.loan_id "
                                      "WHERE l.pool_id=? AND x.period=? AND x.kind=?", (pid, period, kind))[0]["v"]
            avail_i, avail_p, loss = q("payment", "interest_cents"), q("payment", "principal_cents"), q("writeoff", "principal_cents")
            tr = t.q("SELECT * FROM tranches WHERE pool_id=? ORDER BY seq", (pid,)); out = []
            for x in tr:  # 1) interest by seniority, shortfalls carry forward
                due = monthly_interest(x["balance_cents"], x["coupon_bps"]) + x["shortfall_cents"]
                paid = min(due, avail_i); avail_i -= paid; x["interest_paid"], x["shortfall_cents"] = paid, due - paid
            residual_i, left = avail_i, loss
            for x in reversed(tr):  # 2) losses hit the most junior tranche first
                w = min(left, x["balance_cents"]); x["balance_cents"] -= w; left -= w; x["writedown"] = w
            for x in tr:  # 3) principal sequentially
                p = min(avail_p, x["balance_cents"]); x["balance_cents"] -= p; avail_p -= p; x["principal_paid"] = p
            residual_p = avail_p
            for x in tr:
                t.q("UPDATE tranches SET balance_cents=?, shortfall_cents=? WHERE pool_id=? AND name=?", (x["balance_cents"], x["shortfall_cents"], pid, x["name"]))
                out.append({k: x[k] for k in ("name", "interest_paid", "writedown", "principal_paid", "balance_cents", "shortfall_cents")})
            ti, tp = sum(x["interest_paid"] for x in tr), sum(x["principal_paid"] for x in tr)
            certificate_writedown = sum(x["writedown"] for x in tr)
            res = residual_i + residual_p
            detail = {"collected_interest_cents": ti + residual_i, "collected_principal_cents": tp + residual_p,
                      "loss_cents": loss, "certificate_writedown_cents": certificate_writedown,
                      "residual_cents": res, "tranches": out}
            t.q("INSERT INTO waterfall_runs VALUES(?,?,?,?)", (pid, period, json.dumps(detail), now()))
            journal_lines = []
            if ti + tp + res:
                journal_lines.extend([("5000", ti, 0), ("2000", tp, 0), ("3200", res, 0), ("1000", 0, ti + tp + res)])
            if certificate_writedown:
                # Losses allocated to certificate holders reduce the payable balance.
                journal_lines.extend([("2000", certificate_writedown, 0), ("4400", 0, certificate_writedown)])
            if journal_lines:
                self.gl.post(t, period, f"Waterfall {pid}", journal_lines, "POOL:" + pid)
            self.s._append(t, "WATERFALL", {"pool": pid, "period": period, "interest_cents": ti, "principal_cents": tp,
                                             "residual_cents": res, "loss_cents": loss,
                                             "certificate_writedown_cents": certificate_writedown, "actor": actor})
        return detail

def mount_books(app, store, need, body, cents):
    gl = GL(store); L = Loans(store, gl); R = Remic(store, gl)
    per = lambda: request.args.get("period", "")
    @app.post("/journal")
    @need("operator")
    def j_new():
        d = body("period", "memo", "lines"); return jsonify(gl.manual(d["period"], d["memo"], d["lines"], g.user)), 201
    @app.get("/reports/trial-balance")
    @need("auditor")
    def r_tb(): return jsonify(gl.trial_balance(per()))
    @app.get("/reports/income-statement")
    @need("auditor")
    def r_is(): return jsonify(gl.income_statement(per()))
    @app.get("/reports/balance-sheet")
    @need("auditor")
    def r_bs(): return jsonify(gl.balance_sheet(per()))
    @app.post("/periods/<period>/close")
    @need("admin")
    def p_close(period): return jsonify(gl.close(period, g.user))
    @app.post("/loans")
    @need("operator")
    def l_new():
        d = body("borrower", "principal_cents", "rate_bps", "term_months", "start_date")
        return jsonify(L.originate(d["borrower"], cents(d["principal_cents"]), cents(d["rate_bps"]), cents(d["term_months"]), d["start_date"], d.get("asset_id") or None, d.get("pool_id") or None, g.user, d.get("jurisdiction"))), 201
    @app.get("/loans")
    @need("auditor")
    def l_list(): return jsonify(L.list())
    @app.get("/loans/<lid>")
    @need("auditor")
    def l_get(lid): return jsonify(L.get(lid))
    @app.get("/loans/<lid>/schedule")
    @need("auditor")
    def l_sched(lid): return jsonify(L.schedule(lid))
    @app.post("/loans/<lid>/accrue")
    @need("operator")
    def l_acc(lid): return jsonify(L.accrue(lid, body("period")["period"], g.user))
    @app.post("/loans/<lid>/payments")
    @need("operator")
    def l_pay(lid):
        d = body("amount_cents", "period", "event_id")
        eid = d["event_id"]
        if isinstance(eid, bool) or not isinstance(eid, (str, int)) or not str(eid).strip():
            raise StoreError("event_id is required")
        return jsonify(L.pay(lid, cents(d["amount_cents"]), d["period"], str(eid), g.user))
    @app.post("/loans/<lid>/writeoff")
    @need("operator")
    def l_wo(lid): return jsonify(L.write_off(lid, body("period")["period"], g.user))
    @app.post("/pools")
    @need("operator")
    def pl_new():
        d = body("name", "period", "tranches"); return jsonify(R.create(d["name"], d["period"], d["tranches"], g.user)), 201
    @app.get("/pools/<pid>")
    @need("auditor")
    def pl_get(pid): return jsonify(R.get(pid))
    @app.post("/pools/<pid>/waterfall")
    @need("operator")
    def pl_run(pid): return jsonify(R.run(pid, body("period")["period"], g.user))
