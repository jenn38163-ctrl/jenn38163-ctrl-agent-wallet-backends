-- PostgreSQL-only ledger guards. The application takes advisory lock 7001 before
-- every ledger write; this trigger independently enforces the append position/prev link.
CREATE OR REPLACE FUNCTION ledger_immutable() RETURNS trigger AS $$
DECLARE
  expected_idx BIGINT;
  expected_prev TEXT;
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT COALESCE(MAX(idx) + 1, 0) INTO expected_idx FROM ledger;
    SELECT hash INTO expected_prev FROM ledger ORDER BY idx DESC LIMIT 1;
    IF expected_prev IS NULL THEN expected_prev := repeat('0', 64); END IF;
    IF NEW.idx <> expected_idx OR NEW.prev <> expected_prev THEN
      RAISE EXCEPTION 'ledger insert must append to the current chain';
    END IF;
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'ledger is append-only';
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS ledger_no_change ON ledger;
DROP TRIGGER IF EXISTS ledger_append_guard ON ledger;
CREATE TRIGGER ledger_append_guard BEFORE INSERT OR UPDATE OR DELETE ON ledger
  FOR EACH ROW EXECUTE FUNCTION ledger_immutable();
