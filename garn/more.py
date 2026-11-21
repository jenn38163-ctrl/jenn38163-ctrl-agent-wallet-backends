"""Investments subledger, software capitalization, document intake and jurisdiction routing.
Rules here are configurable defaults, not legal or accounting advice."""
import base64, binascii, hashlib, json, re, uuid
from flask import g, jsonify, request
from books import GL, period_ok, rule_for
from store import StoreError, now

class Invest:
    def __init__(self, s, gl): self.s, self.gl = s, gl
    def _pos(self, t, sym):
        r = t.q("SELECT * FROM positions WHERE symbol=?", (sym,)); return r[0] if r else None
    def buy(self, sym, qty, unit, period, actor):
        if qty <= 0 or unit <= 0: raise StoreError("qty and unit price must be above zero")
        amt = qty * unit
        with self.s.tx() as t:
            self.s._lock(t); self.gl.post(t, period, f"Buy {sym}", [("1300", amt, 0), ("1000", 0, amt)], "INV:" + sym)
            p = self._pos(t, sym)
            if p: t.q("UPDATE positions SET qty=?, cost_cents=? WHERE symbol=?", (p["qty"] + qty, p["cost_cents"] + amt, sym))
            else: t.q("INSERT INTO positions VALUES(?,?,?,?)", (sym, qty, amt, 0))
            seq = t.q("SELECT COALESCE(MAX(seq),0)+1 AS n FROM lots")[0]["n"]
            t.q("INSERT INTO lots VALUES(?,?,?,?,?,?)", (str(uuid.uuid4()), sym, qty, unit, period, seq))
            self.s._append(t, "INV_BUY", {"symbol": sym, "qty": qty, "unit_cents": unit, "actor": actor})
        return {"cost_cents": amt}
    def sell(self, sym, qty, unit, period, actor):  # FIFO lots
        if qty <= 0 or unit <= 0: raise StoreError("qty and unit price must be above zero")
        with self.s.tx() as t:
            self.s._lock(t); p = self._pos(t, sym)
            if not p or p["qty"] < qty: raise StoreError("insufficient position", 409)
            need, cost = qty, 0
            for lot in t.q("SELECT * FROM lots WHERE symbol=? AND qty_remaining>0 ORDER BY seq", (sym,)):
                take = min(need, lot["qty_remaining"]); cost += take * lot["unit_cost_cents"]; need -= take
                t.q("UPDATE lots SET qty_remaining=? WHERE lot_id=?", (lot["qty_remaining"] - take, lot["lot_id"]))
                if not need: break
            # mark the whole position to the sale price first, so the adjustment released equals the sold units' unrealized gain
            proceeds, gain = qty * unit, qty * unit - cost
            mark_delta, rel = p["qty"] * unit - p["cost_cents"] - p["adj_cents"], proceeds - cost
            u = mark_delta - rel
            lines = [("1000", proceeds, 0), ("1300", 0, cost), ("4300", max(-gain, 0), max(gain, 0)),
                     ("1310", max(u, 0), max(-u, 0)), ("4310", max(-u, 0), max(u, 0))]
            self.gl.post(t, period, f"Sell {sym}", lines, "INV:" + sym)
            t.q("UPDATE positions SET qty=?, cost_cents=?, adj_cents=? WHERE symbol=?", (p["qty"] - qty, p["cost_cents"] - cost, p["adj_cents"] + mark_delta - rel, sym))
            self.s._append(t, "INV_SELL", {"symbol": sym, "qty": qty, "proceeds_cents": proceeds, "realized_cents": gain, "actor": actor})
        return {"proceeds_cents": proceeds, "cost_cents": cost, "realized_gain_cents": gain}
    def mark(self, sym, unit, period, actor):
        with self.s.tx() as t:
            self.s._lock(t); p = self._pos(t, sym)
            if not p or p["qty"] == 0: raise StoreError("no open position to mark", 409)
            delta = p["qty"] * unit - p["cost_cents"] - p["adj_cents"]
            if delta: self.gl.post(t, period, f"Mark {sym}", [("1310", max(delta, 0), max(-delta, 0)), ("4310", max(-delta, 0), max(delta, 0))], "INV:" + sym)
            t.q("UPDATE positions SET adj_cents=? WHERE symbol=?", (p["adj_cents"] + delta, sym))
            self.s._append(t, "INV_MARK", {"symbol": sym, "unit_cents": unit, "change_cents": delta, "actor": actor})
        return {"unrealized_change_cents": delta}
    def income(self, sym, amount, period, actor):
        if amount <= 0: raise StoreError("amount must be above zero")
        with self.s.tx() as t:
            self.s._lock(t); self.gl.post(t, period, f"Income {sym}", [("1000", amount, 0), ("4200", 0, amount)], "INV:" + sym)
            self.s._append(t, "INV_INCOME", {"symbol": sym, "amount_cents": amount, "actor": actor})
        return {"income_cents": amount}
    def positions(self):
        with self.s.tx() as t:
            rows = t.q("SELECT * FROM positions ORDER BY symbol")
        for r in rows: r["fair_value_cents"] = r["cost_cents"] + r["adj_cents"]
        return rows

PHASES = {"preliminary": "5300", "development": "1400", "post_implementation": "5300"}
class Software:
    """Costs in the application-development phase are capitalized; preliminary and post-implementation costs are expensed.
    Amortization is straight-line from the in-service period."""
    def __init__(self, s, gl): self.s, self.gl = s, gl
    def _p(self, t, pid):
        r = t.q("SELECT * FROM sw_projects WHERE project_id=?", (pid,))
        if not r: raise StoreError("project not found", 404)
        return r[0]
    def create(self, name, life, actor):
        if not isinstance(name, str) or not name.strip() or len(name.strip()) > 200:
            raise StoreError("project name must be 1 to 200 characters")
        if isinstance(life, bool) or not isinstance(life, int) or not 1 <= life <= 1200:
            raise StoreError("useful life must be a whole number from 1 to 1200 months")
        name = name.strip()
        pid = "SW-" + uuid.uuid4().hex[:8].upper()
        with self.s.tx() as t:
            self.s._lock(t); t.q("INSERT INTO sw_projects VALUES(?,?,?,?,?,?,?,?)", (pid, name, life, "development", 0, 0, None, 0))
            self.s._append(t, "SW_CREATE", {"project": pid, "life_months": life, "actor": actor})
        return {"project_id": pid}
    def list(self):
        with self.s.tx() as t: return t.q("SELECT * FROM sw_projects ORDER BY project_id")
    def cost(self, pid, phase, amount, period, desc, actor):
        if phase not in PHASES or amount <= 0: raise StoreError("phase must be preliminary, development or post_implementation; amount above zero")
        with self.s.tx() as t:
            self.s._lock(t); p = self._p(t, pid)
            if phase == "development" and p["status"] != "development": raise StoreError("project is in service: development costs can no longer be capitalized", 409)
            self.gl.post(t, period, f"{phase} cost {pid}", [(PHASES[phase], amount, 0), ("1000", 0, amount)], "SW:" + pid)
            t.q("INSERT INTO sw_costs VALUES(?,?,?,?,?,?)", (str(uuid.uuid4()), pid, phase, amount, period, desc))
            if phase == "development": t.q("UPDATE sw_projects SET capitalized_cents=? WHERE project_id=?", (p["capitalized_cents"] + amount, pid))
            self.s._append(t, "SW_COST", {"project": pid, "phase": phase, "amount_cents": amount, "capitalized": phase == "development", "actor": actor})
        return {"capitalized": phase == "development"}
    def in_service(self, pid, period, actor):
        period_ok(period)
        with self.s.tx() as t:
            self.s._lock(t); p = self._p(t, pid)
            if p["status"] != "development" or p["capitalized_cents"] <= 0: raise StoreError("needs capitalized development costs and development status", 409)
            t.q("UPDATE sw_projects SET status='in_service', in_service_period=? WHERE project_id=?", (period, pid))
            self.s._append(t, "SW_IN_SERVICE", {"project": pid, "period": period, "actor": actor})
        return {"status": "in_service"}
    def amortize(self, pid, period, actor):
        period_ok(period)
        with self.s.tx() as t:
            self.s._lock(t); p = self._p(t, pid)
            if p["status"] != "in_service" or period < p["in_service_period"]: raise StoreError("project is not in service for this period", 409)
            if p["months_amortized"] >= p["life_months"]: raise StoreError("project is fully amortized", 409)
            if t.q("SELECT 1 FROM sw_amort WHERE project_id=? AND period=?", (pid, period)): raise StoreError("already amortized for this period", 409)
            last = p["months_amortized"] == p["life_months"] - 1
            amt = p["capitalized_cents"] - p["amortized_cents"] if last else p["capitalized_cents"] // p["life_months"]
            if amt: self.gl.post(t, period, f"Amortize {pid}", [("5200", amt, 0), ("1410", 0, amt)], "SW:" + pid)
            t.q("INSERT INTO sw_amort VALUES(?,?)", (pid, period))
            t.q("UPDATE sw_projects SET amortized_cents=?, months_amortized=? WHERE project_id=?", (p["amortized_cents"] + amt, p["months_amortized"] + 1, pid))
            self.s._append(t, "SW_AMORTIZE", {"project": pid, "period": period, "amount_cents": amt, "actor": actor})
        return {"amortization_cents": amt}

STATES = ("AL Alabama;AK Alaska;AZ Arizona;AR Arkansas;CA California;CO Colorado;CT Connecticut;DE Delaware;FL Florida;GA Georgia;HI Hawaii;ID Idaho;"
          "IL Illinois;IN Indiana;IA Iowa;KS Kansas;KY Kentucky;LA Louisiana;ME Maine;MD Maryland;MA Massachusetts;MI Michigan;MN Minnesota;MS Mississippi;"
          "MO Missouri;MT Montana;NE Nebraska;NV Nevada;NH New Hampshire;NJ New Jersey;NM New Mexico;NY New York;NC North Carolina;ND North Dakota;OH Ohio;"
          "OK Oklahoma;OR Oregon;PA Pennsylvania;RI Rhode Island;SC South Carolina;SD South Dakota;TN Tennessee;TX Texas;UT Utah;VT Vermont;VA Virginia;"
          "WA Washington;WV West Virginia;WI Wisconsin;WY Wyoming;DC District of Columbia")
STATE_NAMES = {n.lower(): "US-" + c for c, n in (x.split(" ", 1) for x in STATES.split(";"))}
CATEGORIES = [("note", ["promissory note", "borrower promises", "principal amount"]), ("deed", ["deed of trust", "mortgage", "grantor"]),
              ("appraisal", ["appraisal", "appraised value"]), ("title", ["title commitment", "title insurance"]),
              ("insurance", ["certificate of insurance", "insurance policy", "policy number"]),
              ("statement", ["statement period", "account statement", "statement of account"]),
              ("kyc", ["passport", "driver license", "driver's license", "date of birth", "beneficial owner", "know your customer"])]

def classify(name, text):
    low = (name + " " + text).lower(); best, score = "other", 0
    for cat, kws in CATEGORIES:
        n = sum(low.count(k) for k in kws)
        if n > score: best, score = cat, n
    return best
def extract(text):
    return {"amounts": re.findall(r"\$\s?\d[\d,]*(?:\.\d{2})?", text)[:10],
            "dates": re.findall(r"\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}/\d{1,2}/\d{4}\b", text)[:10],
            "rates": re.findall(r"\b\d+(?:\.\d+)?\s?%", text)[:10]}
def find_jurisdiction(text):
    low = text.lower(); hits = [(low.count(n), c) for n, c in STATE_NAMES.items() if n in low]
    return max(hits)[1] if hits else None

class Docs:
    MAX = 1_000_000
    def __init__(self, s): self.s = s
    def intake(self, filename, text, b64, asset_id, loan_id, jurisdiction, actor):
        try: raw = base64.b64decode(b64, validate=True) if b64 else (text or "").encode()
        except (binascii.Error, ValueError): raise StoreError("content_b64 is not valid base64")
        if not raw or len(raw) > self.MAX: raise StoreError("document must be 1 byte to 1 MB")
        try: body = raw.decode()
        except UnicodeDecodeError: body = None   # binary (PDF, image): classified by filename only; add OCR/PDF extraction here
        digest = hashlib.sha256(raw).hexdigest(); txt = body or ""
        cat, fields = classify(filename, txt), extract(txt)
        jur = (jurisdiction or find_jurisdiction(txt) or "").upper() or None
        with self.s.tx() as t:
            self.s._lock(t); dup = t.q("SELECT doc_id, category FROM documents WHERE sha256=?", (digest,))
            if dup: return {"duplicate": True, **dup[0]}
            if asset_id: self.s._asset(t, asset_id)
            if loan_id and not t.q("SELECT 1 FROM loans WHERE loan_id=?", (loan_id,)): raise StoreError("loan not found", 404)
            did = str(uuid.uuid4())
            t.q("INSERT INTO documents VALUES(?,?,?,?,?,?,?,?,?,?,?)", (did, filename, digest, cat, jur, len(raw), body, json.dumps(fields), asset_id, loan_id, now()))
            self.s._append(t, "DOC_INTAKE", {"doc": did, "sha256": digest, "category": cat, "actor": actor})
        return {"doc_id": did, "category": cat, "jurisdiction": jur, "sha256": digest, "fields": fields, "text_extracted": body is not None}
    def list(self, category=None, loan_id=None, asset_id=None):
        sql, args = "SELECT doc_id, filename, category, jurisdiction, size_bytes, asset_id, loan_id, created_at FROM documents WHERE 1=1", []
        for col, v in (("category", category), ("loan_id", loan_id), ("asset_id", asset_id)):
            if v: sql += f" AND {col}=?"; args.append(v)
        with self.s.tx() as t: return t.q(sql + " ORDER BY created_at", args)
    def get(self, did):
        with self.s.tx() as t: r = t.q("SELECT doc_id, filename, sha256, category, jurisdiction, size_bytes, fields, asset_id, loan_id FROM documents WHERE doc_id=?", (did,))
        if not r: raise StoreError("document not found", 404)
        return {**r[0], "fields": json.loads(r[0]["fields"])}

class Juris:
    def __init__(self, s):
        self.s = s
        with s.tx() as t:
            if not t.q("SELECT 1 FROM jurisdiction_rules WHERE code='DEFAULT'"):
                t.q("INSERT INTO jurisdiction_rules VALUES(?,?,?,?,?,?)", ("DEFAULT", "Default: no jurisdiction-specific rule configured", None, '["note"]', "standard", "Placeholder. Configure rules with counsel."))
    def set(self, code, name, max_rate_bps, docs, kyc, notes, actor):
        if not code or not isinstance(docs, list): raise StoreError("code and required_docs (list) are needed")
        code = code.upper()
        with self.s.tx() as t:
            self.s._lock(t); args = (name, max_rate_bps, json.dumps(docs), kyc, notes)
            if t.q("SELECT 1 FROM jurisdiction_rules WHERE code=?", (code,)):
                t.q("UPDATE jurisdiction_rules SET name=?, max_rate_bps=?, required_docs=?, kyc_level=?, notes=? WHERE code=?", args + (code,))
            else: t.q("INSERT INTO jurisdiction_rules VALUES(?,?,?,?,?,?)", (code,) + args)
            self.s._append(t, "JURISDICTION_RULE", {"code": code, "max_rate_bps": max_rate_bps, "actor": actor})
        return {"code": code}
    def list(self):
        with self.s.tx() as t: return t.q("SELECT code, name, max_rate_bps, kyc_level FROM jurisdiction_rules ORDER BY code")
    def route(self, code):
        with self.s.tx() as t: r = rule_for(t, (code or "DEFAULT").upper())
        return {"jurisdiction": (code or "DEFAULT").upper(), **r}
    def loan_compliance(self, lid):
        with self.s.tx() as t:
            m = t.q("SELECT jurisdiction FROM loan_meta WHERE loan_id=?", (lid,))
            if not t.q("SELECT 1 FROM loans WHERE loan_id=?", (lid,)): raise StoreError("loan not found", 404)
            code = m[0]["jurisdiction"] if m else "DEFAULT"; rule = rule_for(t, code)
            have = sorted({r["category"] for r in t.q("SELECT category FROM documents WHERE loan_id=?", (lid,))})
        missing = [d for d in rule["required_docs"] if d not in have]
        return {"loan_id": lid, "jurisdiction": code, "rule_found": rule["rule_found"], "required_docs": rule["required_docs"], "present": have, "missing": missing, "compliant": not missing}

def mount_more(app, store, need, body, cents):
    gl = GL(store); I = Invest(store, gl); W = Software(store, gl); D = Docs(store); J = Juris(store)
    @app.post("/investments/buy")
    @need("operator")
    def i_buy():
        d = body("symbol", "qty", "unit_cents", "period"); return jsonify(I.buy(d["symbol"].upper(), cents(d["qty"]), cents(d["unit_cents"]), d["period"], g.user)), 201
    @app.post("/investments/sell")
    @need("operator")
    def i_sell():
        d = body("symbol", "qty", "unit_cents", "period"); return jsonify(I.sell(d["symbol"].upper(), cents(d["qty"]), cents(d["unit_cents"]), d["period"], g.user))
    @app.post("/investments/mark")
    @need("operator")
    def i_mark():
        d = body("symbol", "unit_cents", "period"); return jsonify(I.mark(d["symbol"].upper(), cents(d["unit_cents"]), d["period"], g.user))
    @app.post("/investments/income")
    @need("operator")
    def i_inc():
        d = body("symbol", "amount_cents", "period"); return jsonify(I.income(d["symbol"].upper(), cents(d["amount_cents"]), d["period"], g.user))
    @app.get("/investments")
    @need("auditor")
    def i_list(): return jsonify(I.positions())
    @app.post("/software/projects")
    @need("operator")
    def s_new():
        d = body("name", "useful_life_months"); return jsonify(W.create(d["name"], cents(d["useful_life_months"]), g.user)), 201
    @app.get("/software/projects")
    @need("auditor")
    def s_list(): return jsonify(W.list())
    @app.post("/software/projects/<pid>/costs")
    @need("operator")
    def s_cost(pid):
        d = body("phase", "amount_cents", "period"); return jsonify(W.cost(pid, d["phase"], cents(d["amount_cents"]), d["period"], d.get("description", ""), g.user))
    @app.post("/software/projects/<pid>/in-service")
    @need("operator")
    def s_live(pid): return jsonify(W.in_service(pid, body("period")["period"], g.user))
    @app.post("/software/projects/<pid>/amortize")
    @need("operator")
    def s_am(pid): return jsonify(W.amortize(pid, body("period")["period"], g.user))
    @app.post("/documents")
    @need("operator")
    def d_new():
        d = body("filename")
        if not (d.get("content_text") or d.get("content_b64")): raise StoreError("send content_text or content_b64")
        return jsonify(D.intake(d["filename"], d.get("content_text"), d.get("content_b64"), d.get("asset_id") or None, d.get("loan_id") or None, d.get("jurisdiction"), g.user)), 201
    @app.get("/documents")
    @need("auditor")
    def d_list(): return jsonify(D.list(request.args.get("category"), request.args.get("loan_id"), request.args.get("asset_id")))
    @app.get("/documents/<did>")
    @need("auditor")
    def d_get(did): return jsonify(D.get(did))
    @app.post("/jurisdictions")
    @need("admin")
    def j_set():
        d = body("code", "name", "required_docs")
        mr = d.get("max_rate_bps"); mr = None if mr in (None, "") else cents(mr)
        return jsonify(J.set(d["code"], d["name"], mr, d["required_docs"], d.get("kyc_level", "standard"), d.get("notes", ""), g.user))
    @app.get("/jurisdictions")
    @need("auditor")
    def j_list(): return jsonify(J.list())
    @app.get("/jurisdictions/<code>/route")
    @need("auditor")
    def j_route(code): return jsonify(J.route(code))
    @app.get("/loans/<lid>/compliance")
    @need("auditor")
    def l_comp(lid): return jsonify(J.loan_compliance(lid))
