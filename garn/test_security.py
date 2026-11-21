import base64
import hashlib
import hmac
import json
import unittest

from security import issue_token, verify_token


class TokenSecurityTests(unittest.TestCase):
    def setUp(self):
        self.key = b"a-test-signing-key-with-more-than-32-bytes"

    def test_token_contains_no_authorization_role(self):
        token = issue_token(self.key, "operator", now=1000)
        claims = verify_token(self.key, token, now=1001)
        self.assertEqual(claims, {"sub": "operator", "iat": 1000, "exp": 4600})

    def test_expired_token_is_rejected(self):
        token = issue_token(self.key, "operator", now=1000)
        self.assertIsNone(verify_token(self.key, token, now=4600))

    def test_token_signed_with_a_different_key_is_rejected(self):
        token = issue_token(self.key, "operator", now=1000)
        self.assertIsNone(verify_token(b"a-different-signing-key-with-32-bytes", token, now=1001))

    def test_wrong_algorithm_header_is_rejected_even_with_valid_signature(self):
        header = base64.urlsafe_b64encode(b'{"alg":"none","typ":"JWT"}').rstrip(b"=").decode()
        payload = base64.urlsafe_b64encode(json.dumps({"sub": "admin", "iat": 1000, "exp": 2000}, separators=(",", ":")).encode()).rstrip(b"=").decode()
        msg = f"{header}.{payload}".encode()
        signature = base64.urlsafe_b64encode(hmac.new(self.key, msg, hashlib.sha256).digest()).rstrip(b"=").decode()
        self.assertIsNone(verify_token(self.key, f"{header}.{payload}.{signature}", now=1001))

    def test_excessive_lifetime_is_rejected(self):
        with self.assertRaises(ValueError):
            issue_token(self.key, "operator", ttl=3601, now=1000)


if __name__ == "__main__":
    unittest.main()
