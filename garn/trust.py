"""Trust layer: credit engine, tokenization, KYC-gated holders, secondary transfers, asset-backed cards.
Real card numbers and authorization networks stay with your issuer processor (for example Marqeta);
this module is the credit-limit and ledger side. Advance rates are illustrative policy: set your own."""
import uuid
from flask import g, jsonify
from store import StoreError, now

ADVANCE_BPS = {"TB": 9500, "RE": 7500, "LR": 7000, "AR": 7000, "AU": 7000, "CM": 7000, "EQ": 6000, "CC": 6000, "DA": 5000, "IP": 4000}
TOKEN_TYPES = {"security_token", "trust_unit", "asset_backed_token"}
LIVE = ("Tokenized", "Lending", "Secondary")

def grade(ltv_bps):
    return "A" if ltv_bps <= 5000 else "B" if ltv_bps <= 7000 else "C" if ltv_bps <= 8500 else "D"

class Trust:
    def __init__(self, s): self.s = s

    def _credit(self, t, aid):
        a = self.s._asset(t, aid); coll = self.s._covered(t, aid)
        reserved = t.q("SELECT COALESCE(SUM(limit_cents),0) AS v FROM cards WHERE asset_id=? AND status='active'", (aid,))[0]["v"]
        eligible = coll * ADVANCE_BPS.get(aid.split("-")[1], 0) // 10000
        ltv = a["principal_cents"] * 10000 // coll if coll else None
        gr = grade(ltv) if ltv is not None else "D"
        avail = 0 if gr == "D" else max(eligible - a["principal_cents"] - reserved, 0)
        return {"asset_id": aid, "status": a["status"], "collateral_cents": coll, "eligible_cents": eligible, "ltv_bps": ltv,
                "risk_grade": gr, "reserved_cents": reserved, "available_credit_cents": avail}
    def credit(self, aid):
        with self.s.tx() as t: return self._credit(t, aid)

    def _party(self, t, pid):
        r = t.q("SELECT * FROM parties WHERE pid=?", (pid,))
        if not r: raise StoreError("party not found", 404)
        return r[0]
    def _verified(self, t, pid):
        if self._party(t, pid)["kyc_status"] != "verified": raise StoreError("party has not passed KYC/AML", 409)
    def _units(self, t, aid, pid):
        r = t.q("SELECT units FROM holdings WHERE asset_id=? AND pid=?", (aid, pid)); return r[0]["units"] if r else 0
    def _set_units(self, t, aid, pid, n):
        if t.q("SELECT 1 FROM holdings WHERE asset_id=? AND pid=?", (aid, pid)):
            t.q("UPDATE holdings SET units=? WHERE asset_id=? AND pid=?", (n, aid, pid))
        else: t.q("INSERT INTO holdings VALUES(?,?,?)", (aid, pid, n))

    def create_party(self, name, actor):
        if not isinstance(name, str) or not name.strip() or len(name) > 255:
            raise StoreError("party name must contain 1 to 255 characters")
        pid = str(uuid.uuid4())
        with self.s.tx() as t:
            self.s._lock(t); t.q("INSERT INTO parties VALUES(?,?,?,?)", (pid, name, "pending", now()))
            self.s._append(t, "PARTY", {"pid": pid, "actor": actor})
        return {"pid": pid, "kyc_status": "pending"}
    def set_kyc(self, pid, status, actor):
        if status not in ("pending", "verified", "rejected"): raise StoreError("invalid status")
        with self.s.tx() as t:
            self.s._lock(t); self._party(t, pid); t.q("UPDATE parties SET kyc_status=? WHERE pid=?", (status, pid))
            self.s._append(t, "KYC", {"pid": pid, "status": status, "actor": actor})
        return {"pid": pid, "kyc_status": status}

    def tokenize(self, aid, ttype, units, actor):
        if ttype not in TOKEN_TYPES or units <= 0: raise StoreError("valid token_type and units above zero required")
        with self.s.tx() as t:
            self.s._lock(t); a = self.s._asset(t, aid)
            if t.q("SELECT 1 FROM tokens WHERE asset_id=?", (aid,)): raise StoreError("asset is already tokenized", 409)
            if self.s._covered(t, aid) < a["principal_cents"]: raise StoreError("collateral coverage is below 100%", 409)
            t.q("INSERT INTO tokens VALUES(?,?,?)", (aid, ttype, units)); t.q("UPDATE assets SET status='Tokenized' WHERE id=?", (aid,))
            blk = self.s._append(t, "TOKENIZE", {"id": aid, "token_type": ttype, "units": units, "actor": actor})
        return {"id": aid, "status": "Tokenized", "units": units, "block": blk["idx"]}

    def issue(self, aid, pid, units, actor):
        if units <= 0: raise StoreError("units must be above zero")
        with self.s.tx() as t:
            self.s._lock(t); tok = t.q("SELECT total_units FROM tokens WHERE asset_id=?", (aid,))
            if not tok: raise StoreError("asset is not tokenized", 409)
            self._verified(t, pid)
            out = t.q("SELECT COALESCE(SUM(units),0) AS n FROM holdings WHERE asset_id=?", (aid,))[0]["n"]
            if out + units > tok[0]["total_units"]: raise StoreError("exceeds unissued supply", 409)
            self._set_units(t, aid, pid, self._units(t, aid, pid) + units)
            blk = self.s._append(t, "ISSUE", {"id": aid, "pid": pid, "units": units, "actor": actor})
        return {"issued": units, "block": blk["idx"]}

    def transfer(self, aid, src, dst, units, actor):  # secondary trading
        if units <= 0 or src == dst: raise StoreError("units above zero and two different parties required")
        with self.s.tx() as t:
            self.s._lock(t)
            if self.s._asset(t, aid)["status"] != "Secondary": raise StoreError("asset is not open to secondary markets", 409)
            self._verified(t, src); self._verified(t, dst)
            have = self._units(t, aid, src)
            if have < units: raise StoreError("insufficient units", 409)
            self._set_units(t, aid, src, have - units); self._set_units(t, aid, dst, self._units(t, aid, dst) + units)
            blk = self.s._append(t, "TRANSFER", {"id": aid, "from": src, "to": dst, "units": units, "actor": actor})
        return {"transferred": units, "block": blk["idx"]}
    def holdings(self, aid):
        with self.s.tx() as t: return t.q("SELECT pid, units FROM holdings WHERE asset_id=? AND units>0", (aid,))

    def open_card(self, aid, pid, limit, actor):
        if limit <= 0: raise StoreError("limit must be above zero")
        with self.s.tx() as t:
            self.s._lock(t); c = self._credit(t, aid); self._verified(t, pid)
            if c["status"] not in LIVE: raise StoreError("tokenize the asset before opening credit", 409)
            if c["risk_grade"] == "D": raise StoreError("risk grade D: no new credit", 409)
            if limit > c["available_credit_cents"]: raise StoreError(f"limit exceeds available credit of {c['available_credit_cents']}", 409)
            cid = str(uuid.uuid4()); t.q("INSERT INTO cards VALUES(?,?,?,?,?,?,?)", (cid, aid, pid, limit, 0, "active", now()))
            blk = self.s._append(t, "CARD_OPEN", {"id": aid, "card": cid, "pid": pid, "limit_cents": limit, "actor": actor})
        return {"card_id": cid, "limit_cents": limit, "block": blk["idx"]}

    def _card(self, t, cid):
        r = t.q("SELECT * FROM cards WHERE card_id=?", (cid,))
        if not r: raise StoreError("card not found", 404)
        return r[0]
    def card(self, cid):
        with self.s.tx() as t: return self._card(t, cid)

    def _card_event_key(self, cid, ext_id):
        if not isinstance(ext_id, str) or not ext_id.strip() or len(ext_id) > 255:
            raise StoreError("event_id is required and must contain 1 to 255 characters")
        return f"card:{cid}:{ext_id}"

    def _prior_card_event(self, t, c, key, action, amount):
        rows = t.q("SELECT asset_id, kind, amount_cents FROM events WHERE dedupe_key=?", (key,))
        if not rows:
            return None
        old = rows[0]
        if old["asset_id"] != c["asset_id"] or old["amount_cents"] != amount:
            raise StoreError("event_id was already used with a different payload", 409)
        if old["kind"] not in action:
            raise StoreError("event_id was already used for a different card action", 409)
        return old["kind"]

    def authorize(self, cid, amount, ext_id, actor):
        if amount <= 0: raise StoreError("amount must be above zero")
        with self.s.tx() as t:
            self.s._lock(t); c = self._card(t, cid)
            key = self._card_event_key(cid, ext_id)
            prior = self._prior_card_event(t, c, key, {"card_authorize_approved", "card_authorize_declined"}, amount)
            if prior:
                return {"duplicate": True, "approved": prior == "card_authorize_approved", "balance_cents": c["balance_cents"]}
            ok = c["status"] == "active" and c["balance_cents"] + amount <= c["limit_cents"]
            event_kind = "card_authorize_approved" if ok else "card_authorize_declined"
            t.q("INSERT INTO events VALUES(?,?,?,?,?,?,?)", (key, c["asset_id"], "card", event_kind, amount, False, now()))
            if ok:
                t.q("UPDATE cards SET balance_cents=? WHERE card_id=?", (c["balance_cents"] + amount, cid))
            self.s._append(t, "CARD_AUTH" if ok else "CARD_DECLINE", {"id": c["asset_id"], "card": cid, "amount_cents": amount, "actor": actor})
            return {"approved": ok, "balance_cents": c["balance_cents"] + (amount if ok else 0)}

    def repay(self, cid, amount, ext_id, actor):
        if amount <= 0: raise StoreError("amount must be above zero")
        with self.s.tx() as t:
            self.s._lock(t); c = self._card(t, cid)
            key = self._card_event_key(cid, ext_id)
            prior = self._prior_card_event(t, c, key, {"card_repay"}, amount)
            if prior:
                return {"duplicate": True, "balance_cents": c["balance_cents"]}
            if amount > c["balance_cents"]: raise StoreError("repayment exceeds balance", 409)
            t.q("INSERT INTO events VALUES(?,?,?,?,?,?,?)", (key, c["asset_id"], "card", "card_repay", amount, False, now()))
            t.q("UPDATE cards SET balance_cents=? WHERE card_id=?", (c["balance_cents"] - amount, cid))
            self.s._append(t, "CARD_REPAY", {"id": c["asset_id"], "card": cid, "amount_cents": amount, "actor": actor})
            return {"balance_cents": c["balance_cents"] - amount}

def mount(app, store, need, body, cents):
    T = Trust(store)
    @app.post("/parties")
    @need("operator")
    def p_new(): return jsonify(T.create_party(body("name")["name"], g.user)), 201
    @app.post("/parties/<pid>/kyc")
    @need("admin")
    def p_kyc(pid): return jsonify(T.set_kyc(pid, body("status")["status"], g.user))
    @app.get("/assets/<aid>/credit")
    @need("auditor")
    def a_credit(aid): return jsonify(T.credit(aid))
    @app.get("/assets/<aid>/holdings")
    @need("auditor")
    def a_hold(aid): return jsonify(T.holdings(aid))
    @app.post("/assets/<aid>/tokenize")
    @need("operator")
    def a_tok(aid):
        d = body("token_type", "units"); return jsonify(T.tokenize(aid, d["token_type"], cents(d["units"]), g.user))
    @app.post("/assets/<aid>/issue")
    @need("operator")
    def a_issue(aid):
        d = body("party_id", "units"); return jsonify(T.issue(aid, d["party_id"], cents(d["units"]), g.user))
    @app.post("/transfers")
    @need("operator")
    def tr():
        d = body("asset_id", "from_party", "to_party", "units")
        return jsonify(T.transfer(d["asset_id"], d["from_party"], d["to_party"], cents(d["units"]), g.user))
    @app.post("/cards")
    @need("operator")
    def c_new():
        d = body("asset_id", "party_id", "limit_cents")
        return jsonify(T.open_card(d["asset_id"], d["party_id"], cents(d["limit_cents"]), g.user)), 201
    @app.get("/cards/<cid>")
    @need("auditor")
    def c_get(cid): return jsonify(T.card(cid))
    @app.post("/cards/<cid>/authorize")
    @need("operator")
    def c_auth(cid):
        d = body("amount_cents", "event_id")
        eid = d["event_id"]
        if isinstance(eid, bool) or not isinstance(eid, (str, int)) or not str(eid).strip():
            raise StoreError("event_id is required")
        return jsonify(T.authorize(cid, cents(d["amount_cents"]), str(eid), g.user))
    @app.post("/cards/<cid>/repay")
    @need("operator")
    def c_pay(cid):
        d = body("amount_cents", "event_id")
        eid = d["event_id"]
        if isinstance(eid, bool) or not isinstance(eid, (str, int)) or not str(eid).strip():
            raise StoreError("event_id is required")
        return jsonify(T.repay(cid, cents(d["amount_cents"]), str(eid), g.user))
