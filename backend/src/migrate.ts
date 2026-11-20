import { sql } from "drizzle-orm";
import { db } from "./db.js";
import { config } from "./config.js";
import { createHash } from "node:crypto";

export async function migrate() {
  const q = [
    `CREATE EXTENSION IF NOT EXISTS pgcrypto`,

    // Wallets table (with real balance and policy constraints)
    `CREATE TABLE IF NOT EXISTS wallets(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      name varchar(160) NOT NULL,
      organization varchar(160) NOT NULL,
      balance numeric(30,6) NOT NULL DEFAULT 0 CHECK(balance>=0),
      currency varchar(16) NOT NULL DEFAULT 'USD',
      monthly_limit numeric(30,6) NOT NULL CHECK(monthly_limit>=0),
      per_request_limit numeric(30,6) NOT NULL CHECK(per_request_limit>=0),
      monthly_spent numeric(30,6) NOT NULL DEFAULT 0 CHECK(monthly_spent>=0),
      allowed_vendors text NOT NULL DEFAULT '*',
      allowed_chains text NOT NULL DEFAULT 'PAYU',
      require_approval boolean NOT NULL DEFAULT false,
      did_identity varchar(255),
      status varchar(32) NOT NULL DEFAULT 'ACTIVE',
      created_at timestamptz NOT NULL DEFAULT now()
    )`,
    `ALTER TABLE wallets ADD COLUMN IF NOT EXISTS balance numeric(30,6) NOT NULL DEFAULT 0 CHECK(balance>=0)`,
    `ALTER TABLE wallets ADD COLUMN IF NOT EXISTS currency varchar(16) NOT NULL DEFAULT 'USD'`,
    `ALTER TABLE wallets ALTER COLUMN currency SET DEFAULT 'USD'`,
    `ALTER TABLE wallets ADD COLUMN IF NOT EXISTS allowed_vendors text NOT NULL DEFAULT '*'`,
    `ALTER TABLE wallets ADD COLUMN IF NOT EXISTS allowed_chains text NOT NULL DEFAULT 'PAYU'`,
    `ALTER TABLE wallets ADD COLUMN IF NOT EXISTS require_approval boolean NOT NULL DEFAULT false`,
    `ALTER TABLE wallets ADD COLUMN IF NOT EXISTS did_identity varchar(255)`,
    `CREATE INDEX IF NOT EXISTS wallets_org_idx ON wallets(organization, created_at DESC)`,

    // Accounts
    `CREATE TABLE IF NOT EXISTS accounts(
      id varchar(220) PRIMARY KEY,
      wallet_id uuid REFERENCES wallets(id),
      account_type varchar(32) NOT NULL,
      currency varchar(16) NOT NULL DEFAULT 'USDC',
      created_at timestamptz NOT NULL DEFAULT now()
    )`,

    // Journal Entries (with tenant scoping on idempotency_key)
    `CREATE TABLE IF NOT EXISTS journal_entries(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      organization_id varchar(160) NOT NULL DEFAULT 'AI Corp Enterprises',
      idempotency_key varchar(255) NOT NULL,
      wallet_id uuid NOT NULL REFERENCES wallets(id),
      reference varchar(255) NOT NULL,
      protocol varchar(64) NOT NULL,
      status varchar(32) NOT NULL,
      reversal_of uuid UNIQUE REFERENCES journal_entries(id),
      created_at timestamptz NOT NULL DEFAULT now()
    )`,
    `ALTER TABLE journal_entries ADD COLUMN IF NOT EXISTS organization_id varchar(160) NOT NULL DEFAULT 'AI Corp Enterprises'`,
    // Safely replace global unique constraint on idempotency_key with tenant-scoped unique index
    `ALTER TABLE journal_entries DROP CONSTRAINT IF EXISTS journal_entries_idempotency_key_key`,
    `CREATE UNIQUE INDEX IF NOT EXISTS journal_entries_org_idem_uidx ON journal_entries(organization_id, idempotency_key)`,
    `CREATE INDEX IF NOT EXISTS journal_entries_wallet_idx ON journal_entries(wallet_id, created_at DESC)`,

    // Journal Lines (append-only, double-entry)
    `CREATE TABLE IF NOT EXISTS journal_lines(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      journal_entry_id uuid NOT NULL REFERENCES journal_entries(id) ON DELETE RESTRICT,
      account_id varchar(220) NOT NULL REFERENCES accounts(id),
      side varchar(2) NOT NULL CHECK(side IN('DR','CR')),
      amount numeric(30,6) NOT NULL CHECK(amount>0),
      currency varchar(16) NOT NULL DEFAULT 'USDC'
    )`,
    `CREATE INDEX IF NOT EXISTS journal_lines_entry_idx ON journal_lines(journal_entry_id)`,

    // Audit Events (append-only, hash-chained, tenant-scoped)
    `CREATE TABLE IF NOT EXISTS audit_events(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      organization_id varchar(160) NOT NULL DEFAULT 'system',
      seq SERIAL UNIQUE,
      event_type varchar(100) NOT NULL,
      actor varchar(255) NOT NULL,
      payload text NOT NULL,
      previous_hash varchar(64) NOT NULL,
      event_hash varchar(64) NOT NULL,
      created_at timestamptz NOT NULL DEFAULT now()
    )`,
    `ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS organization_id varchar(160) NOT NULL DEFAULT 'system'`,
    `CREATE INDEX IF NOT EXISTS audit_events_org_seq_idx ON audit_events(organization_id, seq DESC)`,

    // Tenant-scoped Idempotency Keys
    `CREATE TABLE IF NOT EXISTS idempotency_keys(
      organization_id varchar(160) NOT NULL,
      key varchar(255) NOT NULL,
      request_hash varchar(64) NOT NULL,
      response_status integer NOT NULL,
      response_body text NOT NULL,
      created_at timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY (organization_id, key)
    )`,
    `ALTER TABLE idempotency_keys ADD COLUMN IF NOT EXISTS organization_id varchar(160) NOT NULL DEFAULT 'AI Corp Enterprises'`,
    `DO $$
    BEGIN
      IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'idempotency_keys'::regclass
          AND contype = 'p'
          AND array_length(conkey, 1) = 1
      ) THEN
        ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
        ALTER TABLE idempotency_keys ADD PRIMARY KEY (organization_id, key);
      END IF;
    END $$`,

    // Facility Loans (Credit Layer)
    `CREATE TABLE IF NOT EXISTS facilities(
      id varchar(100) PRIMARY KEY,
      organization_id varchar(160) NOT NULL,
      borrower_id varchar(160) NOT NULL,
      agent_id varchar(160) NOT NULL,
      commitment numeric(30,6) NOT NULL CHECK(commitment>=0),
      drawn numeric(30,6) NOT NULL DEFAULT 0 CHECK(drawn>=0),
      currency varchar(16) NOT NULL DEFAULT 'USD',
      status varchar(32) NOT NULL DEFAULT 'ACTIVE',
      created_at timestamptz NOT NULL DEFAULT now()
    )`,

    // CARS / Treasury Reporting Outbox
    `CREATE TABLE IF NOT EXISTS cars_outbox(
      transaction_id uuid PRIMARY KEY REFERENCES journal_entries(id),
      tas varchar(64) NOT NULL,
      betc varchar(32) NOT NULL,
      alc varchar(32) NOT NULL,
      ussgl varchar(32) NOT NULL,
      cars_status varchar(32) NOT NULL DEFAULT 'PENDING',
      retry_count int DEFAULT 0,
      external_id varchar(255),
      reported_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now()
    )`,

    // Settlement Intents (Transactional Outbox + Lock/Retry tracking)
    `CREATE TABLE IF NOT EXISTS settlement_intents(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      journal_entry_id uuid NOT NULL REFERENCES journal_entries(id) ON DELETE RESTRICT,
      organization_id varchar(160) NOT NULL DEFAULT 'AI Corp Enterprises',
      provider varchar(64) NOT NULL,
      amount numeric(30,6) NOT NULL CHECK(amount>0),
      currency varchar(16) NOT NULL,
      target_address varchar(255),
      status varchar(32) NOT NULL DEFAULT 'PENDING',
      provider_ref varchar(255),
      attempt_count integer NOT NULL DEFAULT 0,
      next_retry_at timestamptz NOT NULL DEFAULT now(),
      locked_at timestamptz,
      locked_by varchar(128),
      last_error text,
      updated_at timestamptz NOT NULL DEFAULT now(),
      created_at timestamptz NOT NULL DEFAULT now()
    )`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS organization_id varchar(160) NOT NULL DEFAULT 'AI Corp Enterprises'`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS target_address varchar(255)`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS next_retry_at timestamptz NOT NULL DEFAULT now()`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS locked_at timestamptz`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS locked_by varchar(128)`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS last_error text`,
    `ALTER TABLE settlement_intents ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now()`,
    `CREATE UNIQUE INDEX IF NOT EXISTS settlement_intents_entry_uidx ON settlement_intents(journal_entry_id)`,
    `CREATE INDEX IF NOT EXISTS settlement_intents_dispatch_idx ON settlement_intents(status, next_retry_at, created_at)`,
    `CREATE INDEX IF NOT EXISTS settlement_intents_provider_ref_idx ON settlement_intents(provider_ref)`,

    // Scoped API Credentials Table
    `CREATE TABLE IF NOT EXISTS credentials(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      organization_id varchar(160) NOT NULL,
      token_hash varchar(64) NOT NULL UNIQUE,
      role varchar(32) NOT NULL,
      scopes varchar(512) NOT NULL,
      status varchar(32) NOT NULL DEFAULT 'ACTIVE',
      expires_at timestamptz,
      revoked_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now()
    )`,
    `ALTER TABLE credentials ADD COLUMN IF NOT EXISTS expires_at timestamptz`,
    `ALTER TABLE credentials ADD COLUMN IF NOT EXISTS revoked_at timestamptz`,

    // Firebase User -> Explicit Tenant/Role Provisioning Table (zero privileges until approved)
    `CREATE TABLE IF NOT EXISTS tenant_users(
      firebase_uid varchar(128) PRIMARY KEY,
      email varchar(255),
      organization_id varchar(160) NOT NULL DEFAULT 'unassigned',
      role varchar(32) NOT NULL DEFAULT 'PENDING_USER',
      scopes text NOT NULL DEFAULT '',
      status varchar(32) NOT NULL DEFAULT 'PENDING_APPROVAL',
      requested_organization varchar(160),
      approved_by varchar(160),
      approved_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now(),
      updated_at timestamptz NOT NULL DEFAULT now()
    )`,
    `ALTER TABLE tenant_users ADD COLUMN IF NOT EXISTS requested_organization varchar(160)`,
    `ALTER TABLE tenant_users ADD COLUMN IF NOT EXISTS approved_by varchar(160)`,
    `ALTER TABLE tenant_users ADD COLUMN IF NOT EXISTS approved_at timestamptz`,
    `ALTER TABLE tenant_users ALTER COLUMN organization_id SET DEFAULT 'unassigned'`,
    `ALTER TABLE tenant_users ALTER COLUMN role SET DEFAULT 'PENDING_USER'`,
    `ALTER TABLE tenant_users ALTER COLUMN scopes SET DEFAULT ''`,
    `ALTER TABLE tenant_users ALTER COLUMN status SET DEFAULT 'PENDING_APPROVAL'`,

    // Webhook Events (Idempotency)
    `CREATE TABLE IF NOT EXISTS webhook_events(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      provider varchar(64) NOT NULL,
      event_id varchar(255) NOT NULL,
      intent_id uuid REFERENCES settlement_intents(id),
      processing_status varchar(32) NOT NULL DEFAULT 'PROCESSED',
      payload text NOT NULL,
      processed_at timestamptz NOT NULL DEFAULT now(),
      UNIQUE(provider, event_id)
    )`,
    `ALTER TABLE webhook_events ADD COLUMN IF NOT EXISTS processing_status varchar(32) NOT NULL DEFAULT 'PROCESSED'`,

    // --- Ledger Immutability & Double-Entry Conservation Triggers ---
    `CREATE OR REPLACE FUNCTION prevent_immutable_mutation() RETURNS trigger AS $$
    BEGIN
      RAISE EXCEPTION 'IMMUTABLE_RECORD: % records cannot be %', TG_TABLE_NAME, TG_OP;
    END;
    $$ LANGUAGE plpgsql`,

    `DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_journal_lines_immutable' AND NOT tgisinternal
      ) THEN
        CREATE TRIGGER trg_journal_lines_immutable
          BEFORE UPDATE OR DELETE ON journal_lines
          FOR EACH ROW EXECUTE FUNCTION prevent_immutable_mutation();
      END IF;
    END $$`,

    `DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_audit_events_immutable' AND NOT tgisinternal
      ) THEN
        CREATE TRIGGER trg_audit_events_immutable
          BEFORE UPDATE OR DELETE ON audit_events
          FOR EACH ROW EXECUTE FUNCTION prevent_immutable_mutation();
      END IF;
    END $$`,

    `DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_journal_entries_no_delete' AND NOT tgisinternal
      ) THEN
        CREATE TRIGGER trg_journal_entries_no_delete
          BEFORE DELETE ON journal_entries
          FOR EACH ROW EXECUTE FUNCTION prevent_immutable_mutation();
      END IF;
    END $$`,

    `CREATE OR REPLACE FUNCTION validate_journal_entry_update() RETURNS trigger AS $$
    BEGIN
      IF NEW.id <> OLD.id
         OR NEW.organization_id <> OLD.organization_id
         OR NEW.idempotency_key <> OLD.idempotency_key
         OR NEW.wallet_id <> OLD.wallet_id
         OR NEW.reference <> OLD.reference
         OR NEW.protocol <> OLD.protocol
         OR NEW.reversal_of IS DISTINCT FROM OLD.reversal_of THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: core journal_entries fields cannot be modified';
      END IF;
      IF OLD.status IN ('CONFIRMED', 'SETTLED', 'REVERSED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'INVALID_STATUS_TRANSITION: cannot move journal entry from % to %', OLD.status, NEW.status;
      END IF;
      RETURN NEW;
    END;
    $$ LANGUAGE plpgsql`,

    `DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_journal_entries_validate_update' AND NOT tgisinternal
      ) THEN
        CREATE TRIGGER trg_journal_entries_validate_update
          BEFORE UPDATE ON journal_entries
          FOR EACH ROW EXECUTE FUNCTION validate_journal_entry_update();
      END IF;
    END $$`,

    `CREATE OR REPLACE FUNCTION enforce_double_entry_balance() RETURNS trigger AS $$
    DECLARE
      target_entry uuid;
      net_balance numeric(30,6);
      line_count int;
    BEGIN
      target_entry := COALESCE(NEW.journal_entry_id, OLD.journal_entry_id);
      SELECT COALESCE(SUM(CASE WHEN side = 'DR' THEN amount ELSE -amount END), 0), COUNT(*)
      INTO net_balance, line_count
      FROM journal_lines
      WHERE journal_entry_id = target_entry;

      IF line_count < 2 OR net_balance <> 0 THEN
        RAISE EXCEPTION 'DOUBLE_ENTRY_VIOLATION: journal_entry % net=% lines=%', target_entry, net_balance, line_count;
      END IF;
      RETURN NULL;
    END;
    $$ LANGUAGE plpgsql`,

    `DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_trigger WHERE tgname = 'trg_enforce_double_entry_balance' AND NOT tgisinternal
      ) THEN
        CREATE CONSTRAINT TRIGGER trg_enforce_double_entry_balance
          AFTER INSERT OR UPDATE OR DELETE ON journal_lines
          DEFERRABLE INITIALLY DEFERRED
          FOR EACH ROW EXECUTE FUNCTION enforce_double_entry_balance();
      END IF;
    END $$`
  ];

  await db.transaction(async (tx) => {
    await tx.execute(sql`SELECT pg_advisory_xact_lock(88421001)`);
    for (const x of q) {
      await tx.execute(sql.raw(x));
    }
  });
}

export async function seedCredentials() {
  if (config.AGENT_WALLET_API_KEY) {
    const tokenHash = createHash("sha256").update(config.AGENT_WALLET_API_KEY).digest("hex");
    await db.execute({
      text: `INSERT INTO credentials(organization_id, token_hash, role, scopes)
             VALUES($1, $2, 'AGENT', $3)
             ON CONFLICT (token_hash) DO UPDATE SET scopes = EXCLUDED.scopes`,
      values: [config.SEED_ORGANIZATION_ID, tokenHash, config.AGENT_WALLET_API_SCOPES]
    } as any);
  }
}
