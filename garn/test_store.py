import sqlite3
import unittest

from store import Store, StoreError


class StoreCoreTests(unittest.TestCase):
    def setUp(self):
        self.store = Store("sqlite:///:memory:")
        self.asset_id = self.store.create_asset(
            "JMGR", "RE", "TRUST", "A", 2026, "C1", "Core test asset", 100_000, "test"
        )["id"]

    def test_sqlite_enforces_foreign_keys(self):
        self.assertEqual(self.store.conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.conn.execute(
                "INSERT INTO collateral VALUES(?,?,?,?,?)",
                ("orphan", "missing-asset", "invalid", 1, "2026-01-01T00:00:00+00:00"),
            )

    def test_identical_event_replay_is_idempotent(self):
        first = self.store.post_event(
            self.asset_id, "Cross River", "payment", 1_000, "event-1", False, "test"
        )
        retry = self.store.post_event(
            self.asset_id, "Cross River", "payment", 1_000, "event-1", False, "test"
        )
        self.assertFalse(first["duplicate"])
        self.assertTrue(retry["duplicate"])

    def test_event_id_cannot_be_reused_with_changed_amount(self):
        self.store.post_event(self.asset_id, "Cross River", "payment", 1_000, "event-1", False, "test")
        with self.assertRaises(StoreError) as raised:
            self.store.post_event(self.asset_id, "Cross River", "payment", 2_000, "event-1", False, "test")
        self.assertEqual(raised.exception.status, 409)

    def test_disbursement_cannot_overflow_signed_database_principal(self):
        with self.assertRaises(StoreError) as raised:
            self.store.post_event(
                self.asset_id, "Cross River", "disbursement", 2**63 - 1, "overflow", False, "test"
            )
        self.assertEqual(raised.exception.status, 409)
        self.assertTrue(self.store.verify()["valid"])


if __name__ == "__main__":
    unittest.main()
