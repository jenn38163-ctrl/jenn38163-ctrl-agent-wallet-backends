import {sql} from "drizzle-orm";
import {db} from "./db.js";
export async function migrate(){
const q=[
`CREATE EXTENSION IF NOT EXISTS pgcrypto`,
`CREATE TABLE IF NOT EXISTS wallets(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),name varchar(160) NOT NULL,organization varchar(160) NOT NULL,monthly_limit numeric(30,6) NOT NULL CHECK(monthly_limit>=0),per_request_limit numeric(30,6) NOT NULL CHECK(per_request_limit>=0),monthly_spent numeric(30,6) NOT NULL DEFAULT 0 CHECK(monthly_spent>=0),status varchar(32) NOT NULL DEFAULT 'ACTIVE',created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS accounts(id varchar(220) PRIMARY KEY,wallet_id uuid REFERENCES wallets(id),account_type varchar(32) NOT NULL,currency varchar(16) NOT NULL DEFAULT 'USDC',created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS journal_entries(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),idempotency_key varchar(255) NOT NULL UNIQUE,wallet_id uuid NOT NULL REFERENCES wallets(id),reference varchar(255) NOT NULL,protocol varchar(64) NOT NULL,status varchar(32) NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS journal_lines(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),journal_entry_id uuid NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,account_id varchar(220) NOT NULL REFERENCES accounts(id),side varchar(2) NOT NULL CHECK(side IN('DR','CR')),amount numeric(30,6) NOT NULL CHECK(amount>0),currency varchar(16) NOT NULL DEFAULT 'USDC')`,
`CREATE INDEX IF NOT EXISTS journal_lines_entry_idx ON journal_lines(journal_entry_id)`,
`CREATE TABLE IF NOT EXISTS audit_events(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),event_type varchar(100) NOT NULL,actor varchar(255) NOT NULL,payload text NOT NULL,previous_hash varchar(64) NOT NULL,event_hash varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS idempotency_keys(key varchar(255) PRIMARY KEY,response_status integer NOT NULL,response_body text NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`
];
for(const x of q) await db.execute(sql.raw(x));
}