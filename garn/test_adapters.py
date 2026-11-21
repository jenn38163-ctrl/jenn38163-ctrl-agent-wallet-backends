import unittest
from decimal import Decimal

from adapters import Marqeta, SignedBank


class AdapterValidationTests(unittest.TestCase):
    def setUp(self):
        self.bank = SignedBank("NOT_USED")

    def test_bank_rejects_negative_amounts(self):
        with self.assertRaises(ValueError):
            self.bank.normalize({"asset_id": "A-1", "type": "payment", "amount_cents": -1, "event_id": "e1"}, {})

    def test_bank_rejects_float_cents(self):
        with self.assertRaises(ValueError):
            self.bank.normalize({"asset_id": "A-1", "type": "payment", "amount_cents": 12.5, "event_id": "e1"}, {})

    def test_bank_rejects_unapproved_event_kind(self):
        with self.assertRaises(ValueError):
            self.bank.normalize({"asset_id": "A-1", "type": "token_anchor", "amount_cents": 1, "event_id": "e1"}, {})

    def test_bank_requires_stable_event_id(self):
        with self.assertRaises(ValueError):
            self.bank.normalize({"asset_id": "A-1", "type": "payment", "amount_cents": 1, "event_id": " "}, {})

    def test_marqeta_uses_decimal_cents_without_float_rounding(self):
        normalized = Marqeta().normalize(
            {"transactions": [{"type": "authorization", "amount": "12.34", "token": "txn-1"}]},
            {"asset_id": "A-1"},
        )
        self.assertEqual(normalized["amount_cents"], 1234)
        self.assertEqual(normalized["kind"], "card_auth")

    def test_marqeta_rejects_subcent_amounts(self):
        with self.assertRaises(ValueError):
            Marqeta().normalize(
                {"transactions": [{"type": "authorization", "amount": "12.345", "token": "txn-1"}]},
                {"asset_id": "A-1"},
            )

    def test_marqeta_rejects_unknown_types(self):
        with self.assertRaises(ValueError):
            Marqeta().normalize(
                {"transactions": [{"type": "some-new-event", "amount": "12.00", "token": "txn-1"}]},
                {"asset_id": "A-1"},
            )


if __name__ == "__main__":
    unittest.main()
