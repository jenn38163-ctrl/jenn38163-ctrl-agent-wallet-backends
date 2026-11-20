import { describe, it, expect, beforeAll } from "vitest";
import { db } from "../src/db.js";
import { sql } from "drizzle-orm";
import { migrate, seedCredentials } from "../src/migrate.js";
import { postPayment, revertPayment } from "../src/ledger.js";

const runPgTests = process.env.RUN_PG_TESTS === "true";

describe.runIf(runPgTests)("Financial Kernel PostgreSQL Integration Tests", () => {
  beforeAll(async () => {
    await migrate();
    await seedCredentials();
  });

  it("PAYU-01: Should debit balance atomically and restore on reversal", async () => {
    const wRes = await db.execute({
      text: "INSERT INTO wallets(name, organization, currency, balance, monthly_limit, per_request_limit) VALUES($1, $2, $3, $4, $5, $6) RETURNING id",
      values: ["Test Wallet", "AI Corp Enterprises", "USD", 1000, 10000, 500]
    } as any);
    const walletId = (wRes.rows[0] as any).id;

    const ledgerRes = await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId,
      amount: "100.50",
      currency: "USD",
      vendor: "VendorPG",
      protocol: "HTTP_402",
      provider: "PAYU",
      idempotencyKey: `test_key_${Date.now()}`
    });
    expect(ledgerRes.entryId).toBeDefined();

    const wCheck = await db.execute({
      text: "SELECT balance, monthly_spent FROM wallets WHERE id=$1",
      values: [walletId]
    } as any);
    expect(Number((wCheck.rows[0] as any).monthly_spent)).toBe(100.5);
    expect(Number((wCheck.rows[0] as any).balance)).toBe(899.5);

    await revertPayment(ledgerRes.entryId);

    const wCheck2 = await db.execute({
      text: "SELECT balance, monthly_spent FROM wallets WHERE id=$1",
      values: [walletId]
    } as any);
    expect(Number((wCheck2.rows[0] as any).monthly_spent)).toBe(0);
    expect(Number((wCheck2.rows[0] as any).balance)).toBe(1000);
  });

  it("PAYU-02: Should enforce double-entry conservation across all accounts", async () => {
    const violatingEntries = await db.execute(sql`
      SELECT journal_entry_id, sum(case when side='DR' then amount else -amount end) as net
      FROM journal_lines
      GROUP BY journal_entry_id
      HAVING sum(case when side='DR' then amount else -amount end) <> 0
    `);

    expect(violatingEntries.rows.length).toBe(0);
  });
});
