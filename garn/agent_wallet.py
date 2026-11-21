"""Bridge to Agent Wallet OS (the Node/Postgres wallet service). Each registry card gets a spending wallet whose
funding equals the card's available credit, so that wallet balance + card balance = card limit.
Agent spending reduces the wallet; /sync pulls that spend into the card; /repay pays the card down and refills the wallet.
The wallet service must run on its OWN database: it has its own `accounts` table that clashes with this one."""
import json, os, urllib.error, urllib.request, uuid
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from urllib.parse import urlsplit
from flask import g, jsonify
from store import StoreError, now
from trust import Trust

def to_str(cents): return f"{cents // 100}.{cents % 100:02d}"
def to_cents(s):
    try:
        amount = Decimal(str(s))
        if not amount.is_finite() or amount < 0:
            raise ValueError
        cents = amount * 100
        if cents != cents.to_integral_value() or cents > 9_223_372_036_854_775_807:
            raise ValueError
        return int(cents)
    except (InvalidOperation, TypeError, ValueError):
        raise StoreError("wallet service returned an invalid balance", 502)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the wallet bearer credential to a redirect target.
        return None


class Client:
    def __init__(self):
        self.base = os.environ.get("AGENT_WALLET_URL", "").rstrip("/")
        self.key = os.environ.get("AGENT_WALLET_API_KEY", "")
        if self.base:
            parsed = urlsplit(self.base)
            local_http = parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
            production = os.environ.get("APP_ENV", "development").strip().lower() in {"prod", "production"}
            if (parsed.scheme != "https" and (production or not local_http)) or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
                raise StoreError("AGENT_WALLET_URL must be a clean HTTPS URL (HTTP is allowed only for loopback development)", 503)

    def call(self, method, path, body=None, idem=None):
        if not self.base or not self.key:
            raise StoreError("AGENT_WALLET_URL and AGENT_WALLET_API_KEY are not set", 503)
        if not path.startswith("/") or ".." in path:
            raise StoreError("invalid wallet API path", 500)
        h = {"Authorization": "Bearer " + self.key, "Content-Type": "application/json"}
        if idem:
            h["Idempotency-Key"] = idem
        req = urllib.request.Request(self.base + path, json.dumps(body).encode() if body is not None else None, h, method=method)
        opener = urllib.request.build_opener(_NoRedirect())
        try:
            with opener.open(req, timeout=15) as response:
                result = json.load(response)
                if not isinstance(result, dict):
                    raise StoreError("wallet service returned an invalid response", 502)
                return result
        except urllib.error.HTTPError as e:
            raise StoreError(f"wallet service refused the request (HTTP {e.code})", 502)
        except urllib.error.URLError as e:
            raise StoreError("wallet service is unreachable", 502)
        except (TimeoutError, ValueError, json.JSONDecodeError) as e:
            raise StoreError("wallet service returned an invalid or incomplete response", 502)

class Bridge:
    def __init__(self, s): self.s, self.T = s, Trust(s)
    def _link(self, cid):
        with self.s.tx() as t: r = t.q("SELECT wallet_id FROM card_wallets WHERE card_id=?", (cid,))
        return r[0]["wallet_id"] if r else None

    def provision(self, cid, per_request_cents, actor):
        card = self.T.card(cid)
        if card["status"] != "active": raise StoreError("card is not active", 409)
        limit, c = card["limit_cents"], Client(); wid = self._link(cid)
        if not wid:
            w = c.call("POST", "/v1/wallets", {"name": f"Registry card {cid[:8]}", "monthlyLimit": to_str(limit),
                       "perRequestLimit": to_str(per_request_cents or limit), "initialBalance": "0", "currency": "USD", "requireApproval": False},
                       idem=f"create:card:{cid}")
            wid = w["id"]
            with self.s.tx() as t:
                self.s._lock(t); t.q("INSERT INTO card_wallets VALUES(?,?,?)", (cid, wid, now()))
                self.s._append(t, "WALLET_LINK", {"card": cid, "wallet": wid, "actor": actor})
        # stable idempotency key: retrying a failed provision never funds twice
        c.call("POST", f"/v1/wallets/{wid}/fund", {"amount": to_str(limit - card["balance_cents"]), "currency": "USD", "source": "registry_credit_line"},
               idem=f"fund:card:{cid}:init")
        return {"card_id": cid, "wallet_id": wid, "funded_cents": limit - card["balance_cents"]}

    def status(self, cid):
        wid = self._link(cid)
        if not wid: raise StoreError("no wallet linked to this card", 404)
        card, w = self.T.card(cid), Client().call("GET", f"/v1/wallets/{wid}")
        wb = to_cents(w["balance"]); drift = card["limit_cents"] - wb - card["balance_cents"]
        return {"card_id": cid, "wallet_id": wid, "wallet_status": w.get("status"), "wallet_balance_cents": wb,
                "card_balance_cents": card["balance_cents"], "limit_cents": card["limit_cents"], "drift_cents": drift, "in_sync": drift == 0}

    def sync(self, cid, actor):  # wallet spend -> card balance
        st = self.status(cid); spent = st["limit_cents"] - st["wallet_balance_cents"]; delta = spent - st["card_balance_cents"]
        if delta < 0: return {**st, "applied_cents": 0, "note": "wallet holds more than the registry expects: investigate before syncing"}
        if delta == 0: return {**st, "applied_cents": 0}
        r = self.T.authorize(cid, delta, f"wallet-sync:{spent}", actor)
        if not r.get("approved"):
            raise StoreError("card declined the wallet spend", 409)
        after = self.status(cid)
        if not after["in_sync"]:
            raise StoreError("wallet spend was not fully reflected in the registry card balance", 409)
        return {**after, "applied_cents": delta}

    def repay(self, cid, amount, ext_id, actor):  # registry repayment -> refill wallet
        if isinstance(ext_id, bool) or not isinstance(ext_id, (str, int)):
            raise StoreError("event_id must be a non-empty string or integer")
        ext_id = str(ext_id).strip()
        if not ext_id or len(ext_id) > 200:
            raise StoreError("event_id must contain 1 to 200 characters")
        wid = self._link(cid)
        if not wid: raise StoreError("no wallet linked to this card", 404)
        self.T.repay(cid, amount, "wallet-repay:" + ext_id, actor)   # duplicate-safe
        try: Client().call("POST", f"/v1/wallets/{wid}/fund", {"amount": to_str(amount), "currency": "USD", "source": "registry_repayment"}, idem=f"repay:{cid}:{ext_id}")
        except StoreError as e: raise StoreError("repayment recorded, wallet refill pending: retry with the same event_id. " + str(e), 502)
        return self.status(cid)

def mount_wallet(app, store, need, body, cents):
    B = Bridge(store)
    @app.post("/cards/<cid>/wallet")
    @need("operator")
    def w_new(cid):
        d = request_json(); pr = d.get("per_request_cents")
        return jsonify(B.provision(cid, cents(pr) if pr is not None else None, g.user)), 201
    @app.get("/cards/<cid>/wallet")
    @need("auditor")
    def w_get(cid): return jsonify(B.status(cid))
    @app.post("/cards/<cid>/wallet/sync")
    @need("operator")
    def w_sync(cid): return jsonify(B.sync(cid, g.user))
    @app.post("/cards/<cid>/wallet/repay")
    @need("operator")
    def w_repay(cid):
        d = body("amount_cents", "event_id")
        eid = d["event_id"]
        if isinstance(eid, bool) or not isinstance(eid, (str, int)) or not str(eid).strip() or len(str(eid).strip()) > 200:
            raise StoreError("event_id must contain 1 to 200 characters")
        return jsonify(B.repay(cid, cents(d["amount_cents"]), str(eid), g.user))

def request_json():
    from flask import request
    d = request.get_json(silent=True)
    if not isinstance(d, dict):
        raise StoreError("request body must be a JSON object")
    return d
