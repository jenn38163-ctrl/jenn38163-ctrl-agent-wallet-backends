import { sql } from "drizzle-orm";
import { db } from "./db.js";

export async function migrate() {
  const q = [
    `CREATE EXTENSION IF NOT EXISTS pgcrypto`,
    `CREATE TABLE IF NOT EXISTS wallets(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),name varchar(160) NOT NULL,organization varchar(160) NOT NULL,monthly_limit numeric(30,6) NOT NULL CHECK(monthly_limit>=0),per_request_limit numeric(30,6) NOT NULL CHECK(per_request_limit>=0),monthly_spent numeric(30,6) NOT NULL DEFAULT 0 CHECK(monthly_spent>=0),status varchar(32) NOT NULL DEFAULT 'ACTIVE',created_at timestamptz NOT NULL DEFAULT now())`,
    `CREATE TABLE IF NOT EXISTS accounts(id varchar(220) PRIMARY KEY,wallet_id uuid REFERENCES wallets(id),account_type varchar(32) NOT NULL,currency varchar(16) NOT NULL DEFAULT 'USDC',created_at timestamptz NOT NULL DEFAULT now())`,
    `CREATE TABLE IF NOT EXISTS journal_entries(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),idempotency_key varchar(255) NOT NULL UNIQUE,wallet_id uuid NOT NULL REFERENCES wallets(id),reference varchar(255) NOT NULL,protocol varchar(64) NOT NULL,status varchar(32) NOT NULL,reversal_of uuid UNIQUE REFERENCES journal_entries(id),created_at timestamptz NOT NULL DEFAULT now())`,
    `CREATE TABLE IF NOT EXISTS journal_lines(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),journal_entry_id uuid NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,account_id varchar(220) NOT NULL REFERENCES accounts(id),side varchar(2) NOT NULL CHECK(side IN('DR','CR')),amount numeric(30,6) NOT NULL CHECK(amount>0),currency varchar(16) NOT NULL DEFAULT 'USDC')`,
    `CREATE INDEX IF NOT EXISTS journal_lines_entry_idx ON journal_lines(journal_entry_id)`,
    `CREATE TABLE IF NOT EXISTS audit_events(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),seq SERIAL UNIQUE,event_type varchar(100) NOT NULL,actor varchar(255) NOT NULL,payload text NOT NULL,previous_hash varchar(64) NOT NULL,event_hash varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`,
    `CREATE TABLE IF NOT EXISTS idempotency_keys(key varchar(255) PRIMARY KEY,request_hash varchar(64) NOT NULL,response_status integer NOT NULL,response_body text NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`,
    
    // NEW: Facility Loans (Credit Layer)
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
    
    // NEW: CARS / Treasury Reporting Outbox (General Fund Adapter)
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

    // NEW: Settlement Intents
    `CREATE TABLE IF NOT EXISTS settlement_intents(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      journal_entry_id uuid NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
      provider varchar(64) NOT NULL,
      amount numeric(30,6) NOT NULL,
      currency varchar(16) NOT NULL,
      status varchar(32) NOT NULL DEFAULT 'PENDING',
      provider_ref varchar(255),
      created_at timestamptz NOT NULL DEFAULT now()
    )`,

    // NEW: Credentials Table
    `CREATE TABLE IF NOT EXISTS credentials(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      organization_id varchar(160) NOT NULL,
      token_hash varchar(64) NOT NULL UNIQUE,
      role varchar(32) NOT NULL,
      scopes varchar(255),
      status varchar(32) NOT NULL DEFAULT 'ACTIVE',
      created_at timestamptz NOT NULL DEFAULT now()
    )`,

    // NEW: Webhook Events (Idempotency)
    `CREATE TABLE IF NOT EXISTS webhook_events(
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
      provider varchar(64) NOT NULL,
      event_id varchar(255) NOT NULL,
      intent_id uuid REFERENCES settlement_intents(id),
      payload text NOT NULL,
      processed_at timestamptz NOT NULL DEFAULT now(),
      UNIQUE(provider, event_id)
    )`
  ];
  for (const x of q) await db.execute(sql.raw(x));
}

// Seed initial credential based on config
import { config } from "./config.js";
import { createHash } from "node:crypto";

export async function seedCredentials() {
  if (config.AGENT_WALLET_API_KEY) {
    const tokenHash = createHash("sha256").update(config.AGENT_WALLET_API_KEY).digest("hex");
    await db.execute({
      text: `INSERT INTO credentials(organization_id, token_hash, role, scopes) 
             VALUES('AI Corp Enterprises', $1, 'AGENT', 'all')
             ON CONFLICT DO NOTHING`,
      values: [tokenHash]
    } as any);
  }
}
