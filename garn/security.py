"""Small, dependency-free security helpers for the registry API."""
import base64
import hashlib
import hmac
import json
import time

TOKEN_TTL_SECONDS = 60 * 60
MAX_TOKEN_BYTES = 4096


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _b64decode(value: str) -> bytes:
    if not isinstance(value, str) or not value:
        raise ValueError("invalid token segment")
    raw = value.encode("ascii")
    return base64.b64decode(raw + b"=" * (-len(raw) % 4), altchars=b"-_", validate=True)


def issue_token(secret: bytes, subject: str, ttl: int = TOKEN_TTL_SECONDS, now=None) -> str:
    """Create a compact HS256 token. Authorization roles are deliberately not embedded."""
    if not isinstance(subject, str) or not subject or len(subject) > 128:
        raise ValueError("invalid token subject")
    if isinstance(ttl, bool) or not isinstance(ttl, int) or not 1 <= ttl <= TOKEN_TTL_SECONDS:
        raise ValueError("token lifetime must be between 1 and 3600 seconds")
    issued = int(time.time() if now is None else now)
    header = _b64encode(b'{"alg":"HS256","typ":"JWT"}')
    payload = _b64encode(json.dumps(
        {"sub": subject, "iat": issued, "exp": issued + ttl},
        separators=(",", ":"),
    ).encode("utf-8"))
    message = f"{header}.{payload}".encode("ascii")
    signature = _b64encode(hmac.new(secret, message, hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def verify_token(secret: bytes, token: str, now=None):
    """Verify signature, algorithm, shape, issue time and expiry; return claims or None."""
    try:
        if not isinstance(token, str) or len(token.encode("utf-8")) > MAX_TOKEN_BYTES:
            return None
        parts = token.split(".")
        if len(parts) != 3:
            return None
        head, payload, supplied = parts
        header = json.loads(_b64decode(head))
        if not isinstance(header, dict) or header != {"alg": "HS256", "typ": "JWT"}:
            return None
        message = f"{head}.{payload}".encode("ascii")
        expected = _b64encode(hmac.new(secret, message, hashlib.sha256).digest())
        if not hmac.compare_digest(expected, supplied):
            return None
        claims = json.loads(_b64decode(payload))
        if not isinstance(claims, dict) or set(claims) != {"sub", "iat", "exp"}:
            return None
        subject, issued, expires = claims["sub"], claims["iat"], claims["exp"]
        if not isinstance(subject, str) or not subject or len(subject) > 128:
            return None
        if isinstance(issued, bool) or not isinstance(issued, int):
            return None
        if isinstance(expires, bool) or not isinstance(expires, int):
            return None
        current = int(time.time() if now is None else now)
        if issued > current + 30 or expires <= current or expires <= issued:
            return None
        if expires - issued > TOKEN_TTL_SECONDS:
            return None
        return claims
    except (ValueError, TypeError, KeyError, UnicodeError, json.JSONDecodeError, OverflowError):
        return None
