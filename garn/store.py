"""Data layer: assets, collateral, events and the hash-chained ledger.
Runs on PostgreSQL (psycopg 3) or SQLite (stdlib, for dev and tests)."""
import hashlib, json, re, sqlite3, threading, uuid
from decimal import Decimal
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

ID_RX = re.compile(r"^([A-Z]{2,5})-([A-Z]{2})-([A-Z]+)-([A-Z])-(\d{4})-(C\d+)-(\d{6})$")
GENESIS = "0" * 64
CLASSES = {"RE", "LR", "TB", "CM", "EQ", "IP", "DA", "AR", "CC", "AU"}
KINDS = {"disbursement", "payment", "card_settlement", "card_auth", "token_anchor"}
CHANNELS = {"Lending": "Lending", "Secondary Markets": "Secondary"}  # tokenizing goes through Trust.tokenize
HERE = Path(__file__).parent

class StoreError(Exception):
    def __init__(self, msg, status=400):
        super().__init__(msg); self.status = status

def now(): return datetime.now(timezone.utc).isoformat()
def canon(o): return json.dumps(o, sort_keys=True, separators=(",", ":"))
def block_hash(prev, i, ts, typ, data):
    return hashlib.sha256(f"{prev}|{i}|{ts}|{typ}|{canon(data)}".encode()).hexdigest()

class Tx:
    def __init__(self, conn, pg): self.conn, self.pg = conn, pg
    def q(self, sql, args=()):
        cur = self.conn.cursor()
        cur.execute(sql.replace("?", "%s") if self.pg else sql, tuple(args))
        if cur.description:
            cols = [d[0] for d in cur.description]
            return [{c: (int(v) if isinstance(v, Decimal) else v) for c, v in zip(cols, r)} for r in cur.fetchall()]
        return []

class Store:
    def __init__(self, url="sqlite:///:memory:"):
        self.url, self.pg = url, url.startswith("postgres")
        if self.pg:
            import psycopg
            self._pg = psycopg
        else:
            self.lock = threading.RLock()
            self.conn = sqlite3.connect(url.replace("sqlite:///", ""), check_same_thread=False, isolation_level=None, timeout=15)
            self.conn.execute("PRAGMA foreign_keys = ON")
        self._init()

    def _init(self):
        ddl = (HERE / "schema.sql").read_text()
        if self.pg:
            with self._pg.connect(self.url) as c:
                c.execute(ddl); c.execute((HERE / "postgres_extra.sql").read_text())
        else:
            self.conn.executescript(ddl)

    @contextmanager
    def tx(self):
        if self.pg:
            with self._pg.connect(self.url) as conn:  # commits on success, rolls back on error
                yield Tx(conn, True)
        else:
            with self.lock:
                self.conn.execute("BEGIN IMMEDIATE")
                try:
                    yield Tx(self.conn, False); self.conn.execute("COMMIT")
                except BaseException:
                    self.conn.execute("ROLLBACK"); raise

    def _lock(self, t):  # serialises writers so ledger indexes never fork
        if self.pg: t.q("SELECT pg_advisory_xact_lock(7001)")

    def _append(self, t, typ, data):
        self._lock(t)
        last = t.q("SELECT idx, hash FROM ledger ORDER BY idx DESC LIMIT 1")
        idx = last[0]["idx"] + 1 if last else 0
        prev = last[0]["hash"] if last else GENESIS
        ts = now(); h = block_hash(prev, idx, ts, typ, data)
        t.q("INSERT INTO ledger(idx,ts,type,data,prev,hash) VALUES(?,?,?,?,?,?)", (idx, ts, typ, canon(data), prev, h))
        return {"idx": idx, "hash": h}

    # ---- users
    def create_user(self, username, pw_hash, role):
        with self.tx() as t:
            if t.q("SELECT 1 FROM users WHERE username=?", (username,)): raise StoreError("user exists", 409)
            t.q("INSERT INTO users VALUES(?,?,?)", (username, pw_hash, role))
    def get_user(self, username):
        with self.tx() as t:
            r = t.q("SELECT * FROM users WHERE username=?", (username,)); return r[0] if r else None
    def count_users(self):
        with self.tx() as t: return t.q("SELECT COUNT(*) AS n FROM users")[0]["n"]

    # ---- assets
    def _asset(self, t, aid):
        r = t.q("SELECT * FROM assets WHERE id=?", (aid,))
        if not r: raise StoreError("asset not found", 404)
        return r[0]
    def _covered(self, t, aid):
        return t.q("SELECT COALESCE(SUM(value_cents),0) AS v FROM collateral WHERE asset_id=?", (aid,))[0]["v"]

    def create_asset(self, issuer, cls, vehicle, series, year, pool, name, principal_cents, actor):
        if (not isinstance(issuer, str) or not re.fullmatch(r"[A-Z]{2,5}", issuer)
                or not isinstance(cls, str) or cls not in CLASSES
                or not isinstance(vehicle, str) or not re.fullmatch(r"[A-Z]+", vehicle)
                or not isinstance(series, str) or not re.fullmatch(r"[A-Z]", series)
                or isinstance(year, bool) or not isinstance(year, int) or not 1900 <= year <= 9999
                or not isinstance(pool, str) or not re.fullmatch(r"C\d+", pool)
                or not isinstance(name, str) or not name.strip() or len(name) > 255
                or isinstance(principal_cents, bool) or not isinstance(principal_cents, int)
                or not 0 <= principal_cents <= 9_223_372_036_854_775_807):
            raise StoreError("invalid asset identity, name, year, or principal amount")
        with self.tx() as t:
            self._lock(t)
            prefix = "-".join([issuer, cls, vehicle, series, str(year), pool]) + "-"
            n = t.q("SELECT COUNT(*) AS n FROM assets WHERE id LIKE ?", (prefix + "%",))[0]["n"] + 1
            aid = prefix + f"{n:06d}"
            if not ID_RX.fullmatch(aid): raise StoreError("invalid asset ID components")
            t.q("INSERT INTO assets VALUES(?,?,?,?,?,?)", (aid, name.strip(), vehicle, principal_cents, "Registered", now()))
            blk = self._append(t, "REGISTER", {"id": aid, "principal_cents": principal_cents, "actor": actor})
            return {"id": aid, "status": "Registered", "principal_cents": principal_cents, "block": blk["idx"]}

    def get_asset(self, aid):
        with self.tx() as t:
            a = self._asset(t, aid); a["collateral_cents"] = self._covered(t, aid)
            a["coverage"] = round(a["collateral_cents"] / a["principal_cents"], 4) if a["principal_cents"] else None
            a["collateral"] = t.q("SELECT cid, description, value_cents FROM collateral WHERE asset_id=?", (aid,))
            return a
    def list_assets(self):
        with self.tx() as t: return t.q("SELECT id, name, principal_cents, status FROM assets ORDER BY id")

    def add_collateral(self, aid, desc, value_cents, actor):
        if not isinstance(desc, str) or not desc.strip() or len(desc) > 500:
            raise StoreError("collateral description must contain 1 to 500 characters")
        if isinstance(value_cents, bool) or not isinstance(value_cents, int) or not 0 < value_cents <= 9_223_372_036_854_775_807:
            raise StoreError("collateral value must be positive whole cents within the database range")
        with self.tx() as t:
            self._lock(t); self._asset(t, aid); cid = str(uuid.uuid4())
            t.q("INSERT INTO collateral VALUES(?,?,?,?,?)", (cid, aid, desc, value_cents, now()))
            blk = self._append(t, "COLLATERAL", {"id": aid, "cid": cid, "value_cents": value_cents, "actor": actor})
            return {"cid": cid, "block": blk["idx"]}

    def set_channel(self, aid, channel, actor):
        if channel not in CHANNELS: raise StoreError("unknown channel")
        with self.tx() as t:
            self._lock(t); a = self._asset(t, aid)
            if channel == "Secondary Markets":
                if a["status"] != "Tokenized": raise StoreError("tokenize the asset first", 409)
            elif self._covered(t, aid) < a["principal_cents"]:
                raise StoreError("collateral coverage is below 100%", 409)
            t.q("UPDATE assets SET status=? WHERE id=?", (CHANNELS[channel], aid))
            blk = self._append(t, "ACCESS", {"id": aid, "channel": channel, "actor": actor})
            return {"id": aid, "status": CHANNELS[channel], "block": blk["idx"]}

    def post_event(self, aid, source, kind, amount_cents, ext_id, simulated, actor, meta=None):
        if kind not in KINDS:
            raise StoreError("unknown event kind")
        if not isinstance(source, str) or not source or len(source) > 64:
            raise StoreError("invalid event source")
        if not isinstance(ext_id, str) or not ext_id.strip() or len(ext_id) > 255:
            raise StoreError("event_id is required and must contain 1 to 255 characters")
        if isinstance(amount_cents, bool) or not isinstance(amount_cents, int) or not 0 <= amount_cents <= 9_223_372_036_854_775_807:
            raise StoreError("event amount must be whole cents within the database range")
        if (kind == "token_anchor" and amount_cents != 0) or (kind != "token_anchor" and amount_cents <= 0):
            raise StoreError("monetary events must be positive; token anchors must be zero")
        key = f"{source}:{ext_id}"
        with self.tx() as t:
            self._lock(t); a = self._asset(t, aid)
            existing = t.q("SELECT asset_id, source, kind, amount_cents, simulated FROM events WHERE dedupe_key=?", (key,))
            if existing:
                old = existing[0]
                same = (old["asset_id"] == aid and old["source"] == source and old["kind"] == kind
                        and old["amount_cents"] == amount_cents and bool(old["simulated"]) == bool(simulated))
                if not same:
                    raise StoreError("event_id was already used with a different payload", 409)
                return {"duplicate": True}
            p = a["principal_cents"] + (amount_cents if kind == "disbursement" else -amount_cents if kind == "payment" else 0)
            if p < 0: raise StoreError("payment exceeds outstanding principal", 409)
            if p > 9_223_372_036_854_775_807:
                raise StoreError("event would exceed the signed 64-bit principal limit", 409)
            t.q("INSERT INTO events VALUES(?,?,?,?,?,?,?)", (key, aid, source, kind, amount_cents, bool(simulated), now()))
            t.q("UPDATE assets SET principal_cents=? WHERE id=?", (p, aid))
            blk = self._append(t, "EVENT", {"id": aid, "source": source, "kind": kind, "amount_cents": amount_cents,
                                            "simulated": bool(simulated), "meta": meta or {}, "actor": actor})
            return {"duplicate": False, "principal_cents": p, "block": blk["idx"]}

    # ---- ledger
    def ledger(self, limit=100):
        with self.tx() as t:
            rows = t.q("SELECT * FROM ledger ORDER BY idx DESC LIMIT ?", (limit,))
            for r in rows: r["data"] = json.loads(r["data"])
            return rows
    def verify(self):
        with self.tx() as t: rows = t.q("SELECT * FROM ledger ORDER BY idx")
        prev = GENESIS
        for k, r in enumerate(rows):
            if r["idx"] != k or r["prev"] != prev or block_hash(prev, k, r["ts"], r["type"], json.loads(r["data"])) != r["hash"]:
                return {"valid": False, "broken_at": k, "blocks": len(rows)}
            prev = r["hash"]
        return {"valid": True, "blocks": len(rows)}
