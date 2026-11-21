"""REST API for the Global Asset Registry Network. Roles: auditor (read), operator (write), admin (users)."""
import hashlib, hmac, os, re, secrets
from functools import wraps
from flask import Flask, g, jsonify, request, send_from_directory
import adapters
from store import Store, StoreError
from security import issue_token, verify_token

RANK = {"auditor": 1, "operator": 2, "admin": 3}
def hash_pw(pw, salt=None):
    salt = salt or secrets.token_bytes(16)
    return salt.hex() + "$" + hashlib.scrypt(pw.encode(), salt=salt, n=2**14, r=8, p=1).hex()
def check_pw(pw, stored):
    salt, h = stored.split("$"); return hmac.compare_digest(hash_pw(pw, bytes.fromhex(salt)).split("$")[1], h)

def create_app(store=None, secret=None):
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 2 * 1024 * 1024  # supports a 1 MB document encoded as JSON/base64
    app.config["APP_ENV"] = os.environ.get("APP_ENV", "development").strip().lower()
    store = store or Store(os.environ.get("DATABASE_URL", "sqlite:///registry.db"))
    key = (secret or os.environ.get("JWT_SECRET") or "").encode()
    if len(key) < 32:
        raise RuntimeError("JWT_SECRET must be set to at least 32 characters")
    if not store.count_users():
        initial_password = os.environ.get("ADMIN_PASSWORD", "")
        if not isinstance(initial_password, str) or len(initial_password) < 12 or len(initial_password) > 1024:
            raise RuntimeError("ADMIN_PASSWORD of 12 to 1024 characters is required to bootstrap the first admin")
        initial_username = os.environ.get("ADMIN_USER", "admin")
        if not isinstance(initial_username, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{3,64}", initial_username):
            raise RuntimeError("ADMIN_USER must be 3 to 64 letters, numbers, dots, underscores, or hyphens")
        store.create_user(initial_username, hash_pw(initial_password), "admin")
    app.store = store

    def sign(sub, role=None, ttl=3600):
        # Keep role out of the token: authorization always uses the current DB role.
        return issue_token(key, sub, ttl=ttl)

    def read(token):
        return verify_token(key, token)

    def need(role):
        def deco(f):
            @wraps(f)
            def w(*a, **k):
                header = request.headers.get("Authorization", "")
                parts = header.split(None, 1)
                if len(parts) != 2 or parts[0].lower() != "bearer":
                    return jsonify(error="authentication required"), 401
                claims = read(parts[1])
                if not claims:
                    return jsonify(error="authentication required"), 401
                user = store.get_user(claims["sub"])
                if not user:
                    return jsonify(error="authentication required"), 401
                if RANK.get(user["role"], 0) < RANK[role]:
                    return jsonify(error=f"{role} role required"), 403
                g.user = user["username"]
                return f(*a, **k)
            return w
        return deco

    def body(*fields):
        d = request.get_json(silent=True)
        if not isinstance(d, dict):
            raise StoreError("request body must be a JSON object")
        miss = [f for f in fields if f not in d]
        if miss:
            raise StoreError("missing: " + ", ".join(miss))
        return d

    def cents(v):
        if isinstance(v, bool) or not isinstance(v, int) or v < 0 or v > 9_223_372_036_854_775_807:
            raise StoreError("amounts must be whole cents (integer) within the database range")
        return v

    from trust import mount
    from books import mount_books
    from more import mount_more
    from agent_wallet import mount_wallet
    mount(app, store, need, body, cents)
    mount_books(app, store, need, body, cents)
    mount_more(app, store, need, body, cents)
    mount_wallet(app, store, need, body, cents)

    @app.errorhandler(StoreError)
    def _e(e):
        return jsonify(error=str(e)), e.status

    @app.errorhandler(413)
    def too_large(_e):
        return jsonify(error="request body exceeds the 2 MB limit"), 413

    @app.get("/")
    def ui(): return send_from_directory(os.path.join(os.path.dirname(__file__), "static"), "index.html")

    @app.get("/health")
    def health():
        try:
            with store.tx() as t:
                t.q("SELECT 1 AS ok")
            return jsonify(ok=True, database=True)
        except Exception:
            app.logger.exception("health check failed")
            return jsonify(ok=False, database=False), 503

    @app.post("/auth/login")
    def login():
        d = body("username", "password")
        username, password = d["username"], d["password"]
        if not isinstance(username, str) or not isinstance(password, str) or len(password) > 1024:
            return jsonify(error="invalid credentials"), 401
        u = store.get_user(username)
        if not u or not check_pw(password, u["pw_hash"]):
            return jsonify(error="invalid credentials"), 401
        return jsonify(token=sign(u["username"]), role=u["role"])

    @app.post("/users")
    @need("admin")
    def users():
        d = body("username", "password", "role")
        username, password, role = d["username"], d["password"], d["role"]
        if (not isinstance(username, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{3,64}", username)
                or not isinstance(password, str) or not 12 <= len(password) <= 1024
                or role not in RANK):
            raise StoreError("username must be 3-64 safe characters, password 12-1024 characters, and role valid")
        store.create_user(username, hash_pw(password), role)
        return jsonify(ok=True), 201

    @app.get("/assets")
    @need("auditor")
    def assets(): return jsonify(store.list_assets())

    @app.post("/assets")
    @need("operator")
    def new_asset():
        d = body("issuer", "asset_class", "vehicle", "series", "year", "pool", "name", "principal_cents")
        return jsonify(store.create_asset(d["issuer"], d["asset_class"], d["vehicle"], d["series"], d["year"], d["pool"],
                                          d["name"], cents(d["principal_cents"]), g.user)), 201

    @app.get("/assets/<aid>")
    @need("auditor")
    def asset(aid): return jsonify(store.get_asset(aid))

    @app.post("/assets/<aid>/collateral")
    @need("operator")
    def collateral(aid):
        d = body("description", "value_cents"); v = cents(d["value_cents"])
        if v == 0: raise StoreError("value must be above zero")
        return jsonify(store.add_collateral(aid, d["description"], v, g.user)), 201

    @app.post("/assets/<aid>/channel")
    @need("operator")
    def channel(aid): return jsonify(store.set_channel(aid, body("channel")["channel"], g.user))

    @app.post("/assets/<aid>/anchor")
    @need("operator")
    def anchor(aid):
        tx = body("tx_hash")["tx_hash"]
        if not re.fullmatch(r"0x[0-9a-fA-F]{64}", tx): raise StoreError("tx_hash must be 0x + 64 hex characters")
        try: rec = adapters.polygon_receipt(tx)
        except adapters.NotConfigured as e: return jsonify(error=str(e)), 503
        if not isinstance(rec, dict) or rec.get("status") != "0x1":
            raise StoreError("transaction not found or failed on chain", 409)
        receipt_hash = rec.get("transactionHash")
        if not isinstance(receipt_hash, str) or not hmac.compare_digest(receipt_hash.lower(), tx.lower()):
            raise StoreError("RPC receipt transaction hash does not match the requested transaction", 409)
        return jsonify(store.post_event(aid, "Polygon", "token_anchor", 0, tx, False, g.user, {"tx_hash": tx, "block": rec.get("blockNumber")}))

    @app.post("/events")  # simulated partner events for development only
    @need("operator")
    def sim_event():
        if app.config["APP_ENV"] in {"prod", "production"}:
            return jsonify(error="simulated events are disabled in production"), 404
        d = body("asset_id", "source", "kind", "amount_cents", "event_id")
        if d["source"] not in adapters.SIMULATED_SOURCES:
            raise StoreError("unknown source")
        event_id = d["event_id"]
        if not isinstance(event_id, (str, int)) or isinstance(event_id, bool) or not str(event_id).strip():
            raise StoreError("event_id is required for retry-safe event posting")
        return jsonify(store.post_event(d["asset_id"], d["source"], d["kind"], cents(d["amount_cents"]), str(event_id), True, g.user))

    @app.post("/webhooks/<source>")  # live partner events, authenticated by the sender's own scheme
    def webhook(source):
        ad = adapters.WEBHOOKS.get(source)
        if not ad:
            return jsonify(error="unknown source"), 404
        raw = request.get_data(cache=True)
        try:
            ad.verify(request.headers, raw)
        except adapters.NotConfigured as e:
            return jsonify(error=str(e)), 503
        except adapters.BadSignature:
            return jsonify(error="bad signature"), 401
        if not request.is_json:
            return jsonify(error="application/json is required"), 400
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict):
            return jsonify(error="payload must be a JSON object"), 400
        try:
            ev = ad.normalize(payload, request.args)
        except ValueError as e:
            return jsonify(error=str(e)), 400
        return jsonify(store.post_event(ev["aid"], source, ev["kind"], ev["amount_cents"], ev["ext_id"], False, "webhook:" + source, ev["meta"]))

    @app.get("/ledger")
    @need("auditor")
    def ledger():
        try:
            limit = int(request.args.get("limit", "100"))
        except (TypeError, ValueError):
            raise StoreError("limit must be an integer from 1 to 1000")
        if not 1 <= limit <= 1000:
            raise StoreError("limit must be an integer from 1 to 1000")
        return jsonify(store.ledger(limit))

    @app.get("/ledger/verify")
    @need("auditor")
    def verify(): return jsonify(store.verify())

    return app

if __name__ == "__main__":
    create_app().run(host="0.0.0.0", port=int(os.environ.get("PORT", 8000)))
