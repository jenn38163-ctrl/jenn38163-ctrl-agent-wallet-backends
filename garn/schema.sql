-- Portable DDL: runs on PostgreSQL and SQLite. Money is integer cents.
-- Ledger data is stored as canonical JSON text so hashes stay reproducible.
CREATE TABLE IF NOT EXISTS users(
  username TEXT PRIMARY KEY, pw_hash TEXT NOT NULL,
  role TEXT NOT NULL CHECK(role IN ('admin','operator','auditor')));
CREATE TABLE IF NOT EXISTS assets(
  id TEXT PRIMARY KEY, name TEXT NOT NULL, vehicle TEXT NOT NULL,
  principal_cents BIGINT NOT NULL CHECK(principal_cents>=0),
  status TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS collateral(
  cid TEXT PRIMARY KEY, asset_id TEXT NOT NULL REFERENCES assets(id),
  description TEXT NOT NULL, value_cents BIGINT NOT NULL CHECK(value_cents>0),
  created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS events(
  dedupe_key TEXT PRIMARY KEY, asset_id TEXT NOT NULL REFERENCES assets(id),
  source TEXT NOT NULL, kind TEXT NOT NULL, amount_cents BIGINT NOT NULL,
  simulated BOOLEAN NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS ledger(
  idx BIGINT PRIMARY KEY, ts TEXT NOT NULL, type TEXT NOT NULL,
  data TEXT NOT NULL, prev TEXT NOT NULL, hash TEXT NOT NULL UNIQUE);
CREATE TABLE IF NOT EXISTS parties(
  pid TEXT PRIMARY KEY, name TEXT NOT NULL,
  kyc_status TEXT NOT NULL CHECK(kyc_status IN ('pending','verified','rejected')), created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tokens(
  asset_id TEXT PRIMARY KEY REFERENCES assets(id), token_type TEXT NOT NULL,
  total_units BIGINT NOT NULL CHECK(total_units>0));
CREATE TABLE IF NOT EXISTS holdings(
  asset_id TEXT NOT NULL REFERENCES assets(id), pid TEXT NOT NULL REFERENCES parties(pid),
  units BIGINT NOT NULL CHECK(units>=0), PRIMARY KEY(asset_id, pid));
CREATE TABLE IF NOT EXISTS cards(
  card_id TEXT PRIMARY KEY, asset_id TEXT NOT NULL REFERENCES assets(id), pid TEXT NOT NULL REFERENCES parties(pid),
  limit_cents BIGINT NOT NULL CHECK(limit_cents>0), balance_cents BIGINT NOT NULL CHECK(balance_cents>=0),
  status TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS accounts(
  code TEXT PRIMARY KEY, name TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('asset','liability','equity','income','expense')));
CREATE TABLE IF NOT EXISTS periods(period TEXT PRIMARY KEY, status TEXT NOT NULL, closed_at TEXT);
CREATE TABLE IF NOT EXISTS journal(
  jid TEXT PRIMARY KEY, period TEXT NOT NULL, memo TEXT NOT NULL, ref TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS journal_lines(
  jid TEXT NOT NULL REFERENCES journal(jid), line_no INTEGER NOT NULL, account TEXT NOT NULL REFERENCES accounts(code),
  debit_cents BIGINT NOT NULL CHECK(debit_cents>=0), credit_cents BIGINT NOT NULL CHECK(credit_cents>=0),
  PRIMARY KEY(jid, line_no));
CREATE TABLE IF NOT EXISTS pools(pool_id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tranches(
  pool_id TEXT NOT NULL REFERENCES pools(pool_id), name TEXT NOT NULL, seq INTEGER NOT NULL,
  balance_cents BIGINT NOT NULL CHECK(balance_cents>=0), coupon_bps INTEGER NOT NULL,
  shortfall_cents BIGINT NOT NULL, PRIMARY KEY(pool_id, name));
CREATE TABLE IF NOT EXISTS loans(
  loan_id TEXT PRIMARY KEY, asset_id TEXT REFERENCES assets(id), pool_id TEXT REFERENCES pools(pool_id),
  borrower TEXT NOT NULL, principal_cents BIGINT NOT NULL, rate_bps INTEGER NOT NULL, term_months INTEGER NOT NULL,
  start_date TEXT NOT NULL, balance_cents BIGINT NOT NULL CHECK(balance_cents>=0),
  accrued_cents BIGINT NOT NULL CHECK(accrued_cents>=0), status TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS loan_txns(
  tid TEXT PRIMARY KEY, loan_id TEXT NOT NULL REFERENCES loans(loan_id), kind TEXT NOT NULL, period TEXT NOT NULL,
  interest_cents BIGINT NOT NULL, principal_cents BIGINT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS waterfall_runs(
  pool_id TEXT NOT NULL REFERENCES pools(pool_id), period TEXT NOT NULL, detail TEXT NOT NULL, created_at TEXT NOT NULL,
  PRIMARY KEY(pool_id, period));
CREATE TABLE IF NOT EXISTS positions(
  symbol TEXT PRIMARY KEY, qty BIGINT NOT NULL CHECK(qty>=0), cost_cents BIGINT NOT NULL CHECK(cost_cents>=0), adj_cents BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS lots(
  lot_id TEXT PRIMARY KEY, symbol TEXT NOT NULL, qty_remaining BIGINT NOT NULL CHECK(qty_remaining>=0),
  unit_cost_cents BIGINT NOT NULL, opened TEXT NOT NULL, seq INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sw_projects(
  project_id TEXT PRIMARY KEY, name TEXT NOT NULL, life_months INTEGER NOT NULL, status TEXT NOT NULL,
  capitalized_cents BIGINT NOT NULL, amortized_cents BIGINT NOT NULL, in_service_period TEXT, months_amortized INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sw_costs(
  cid TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES sw_projects(project_id), phase TEXT NOT NULL,
  amount_cents BIGINT NOT NULL, period TEXT NOT NULL, description TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sw_amort(
  project_id TEXT NOT NULL REFERENCES sw_projects(project_id), period TEXT NOT NULL, PRIMARY KEY(project_id, period));
CREATE TABLE IF NOT EXISTS documents(
  doc_id TEXT PRIMARY KEY, filename TEXT NOT NULL, sha256 TEXT NOT NULL UNIQUE, category TEXT NOT NULL, jurisdiction TEXT,
  size_bytes BIGINT NOT NULL, content TEXT, fields TEXT NOT NULL, asset_id TEXT REFERENCES assets(id),
  loan_id TEXT REFERENCES loans(loan_id), created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS jurisdiction_rules(
  code TEXT PRIMARY KEY, name TEXT NOT NULL, max_rate_bps INTEGER, required_docs TEXT NOT NULL, kyc_level TEXT NOT NULL, notes TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS loan_meta(loan_id TEXT PRIMARY KEY REFERENCES loans(loan_id), jurisdiction TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS card_wallets(
  card_id TEXT PRIMARY KEY REFERENCES cards(card_id), wallet_id TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL);
