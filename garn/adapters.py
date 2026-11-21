"""Partner adapters.

The payload shapes remain integration templates: validate them against the real
partner contract and run signed end-to-end certification before enabling traffic.
"""
import base64
import hashlib
import hmac
import json
import os
import urllib.request
from decimal import Decimal, InvalidOperation

MAX_CENTS = 9_223_372_036_854_775_807


class NotConfigured(Exception):
    pass


class BadSignature(Exception):
    pass


def _env(name):
    value = os.environ.get(name)
    if not value:
        raise NotConfigured(f"{name} is not set")
    return value


def _event_id(value):
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ValueError("event_id must be a non-empty string or integer")
    result = str(value).strip()
    if not result or len(result) > 255:
        raise ValueError("event_id must contain 1 to 255 characters")
    return result


def _normalize(payload, allowed_kinds):
    if not isinstance(payload, dict):
        raise ValueError("payload must be a JSON object")
    try:
        aid = payload["asset_id"]
        kind = payload["type"]
        amount = payload["amount_cents"]
        ext_id = _event_id(payload["event_id"])
    except (KeyError, TypeError):
        raise ValueError("payload needs asset_id, type, amount_cents, event_id")
    if not isinstance(aid, str) or not aid or len(aid) > 128 or aid.strip() != aid:
        raise ValueError("asset_id must be a non-empty string of at most 128 characters")
    if not isinstance(kind, str) or kind not in allowed_kinds:
        raise ValueError("unsupported event type for this partner")
    if isinstance(amount, bool) or not isinstance(amount, int) or not 0 < amount <= MAX_CENTS:
        raise ValueError("amount_cents must be a positive whole number of cents")
    return {"aid": aid, "kind": kind, "amount_cents": amount, "ext_id": ext_id, "meta": {}}


class SignedBank:
    """HMAC-SHA256 raw-body signature template for Cross River and Celtic."""

    def __init__(self, prefix):
        self.prefix = prefix

    def verify(self, headers, body):
        secret = _env(f"{self.prefix}_WEBHOOK_SECRET").encode()
        good = hmac.new(secret, body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(good, headers.get("X-Signature", "")):
            raise BadSignature()

    def normalize(self, payload, query):
        return _normalize(payload, {"disbursement", "payment"})


class Marqeta:
    """Basic-auth/card-event template. Confirm exact fields and units with Marqeta."""

    def verify(self, headers, body):
        want = base64.b64encode(
            f"{_env('MARQETA_WEBHOOK_USER')}:{_env('MARQETA_WEBHOOK_PASS')}".encode()
        ).decode()
        if not hmac.compare_digest(f"Basic {want}", headers.get("Authorization", "")):
            raise BadSignature()

    def normalize(self, payload, query):
        try:
            transactions = payload["transactions"]
            if not isinstance(transactions, list) or not transactions or not isinstance(transactions[0], dict):
                raise ValueError
            tx = transactions[0]
            raw_type = tx["type"]
            if not isinstance(raw_type, str):
                raise ValueError
            normalized_type = raw_type.lower()
            if "clearing" in normalized_type:
                kind = "card_settlement"
            elif "authorization" in normalized_type or normalized_type in {"auth", "authorization_advice"}:
                kind = "card_auth"
            else:
                raise ValueError
            raw_amount = tx["amount"]
            amount = Decimal(str(raw_amount))
            scaled = amount * 100
            if not amount.is_finite() or amount <= 0 or scaled != scaled.to_integral_value():
                raise ValueError
            cents = int(scaled)
            if cents > MAX_CENTS:
                raise ValueError
            aid = query.get("asset_id")
            ext_id = _event_id(tx.get("token"))
            if not isinstance(aid, str) or not aid or aid.strip() != aid or len(aid) > 128:
                raise ValueError
            return {"aid": aid, "kind": kind, "amount_cents": cents, "ext_id": ext_id,
                    "meta": {"type": raw_type}}
        except (KeyError, IndexError, TypeError, InvalidOperation, ValueError):
            raise ValueError("unrecognized Marqeta payload, invalid amount/type, or missing ?asset_id=")


WEBHOOKS = {"cross-river": SignedBank("CROSS_RIVER"), "celtic": SignedBank("CELTIC"), "marqeta": Marqeta()}
SIMULATED_SOURCES = {"Cross River", "Celtic", "Visa", "Mastercard", "Marqeta", "Polygon"}


def polygon_receipt(tx_hash):
    """Read-only Polygon receipt lookup. Minting/signing is not implemented here."""
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "eth_getTransactionReceipt", "params": [tx_hash]}).encode()
    req = urllib.request.Request(_env("POLYGON_RPC_URL"), body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as response:
        result = json.load(response)
    if not isinstance(result, dict) or result.get("error"):
        raise ValueError("Polygon RPC returned an invalid response")
    return result.get("result")
