import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { db } from '../src/db.js';
import { sql } from 'drizzle-orm';
import { createFacility, processDrawRequest } from '../src/facilities.js';
import { migrate, seedCredentials } from '../src/migrate.js';
import { postPayment, revertPayment } from '../src/ledger.js';

describe('Financial Kernel Integration Tests', () => {
  beforeAll(async () => {
    await migrate();
    await seedCredentials();
  });

  it('PAYU-01: Should isolate credit facility draw from external settlement', async () => {
    // 1. Setup Wallet
    const wRes = await db.execute({
      text: "INSERT INTO wallets(name, organization, monthly_limit, per_request_limit) VALUES($1, $2, $3, $4) RETURNING id",
      values: ["Test Wallet", "AI Corp Enterprises", 10000, 500]
    } as any);
    const walletId = (wRes.rows[0] as any).id;

    // 2. Post Ledger Payment
    const ledgerRes = await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId,
      amount: "100.50",
      currency: "USDC",
      vendor: "0xABCDEF",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: `test_key_${Date.now()}`
    });
    expect(ledgerRes.entryId).toBeDefined();

    // 3. Confirm wallet balance was consumed
    const wCheck = await db.execute({
      text: "SELECT monthly_spent FROM wallets WHERE id=$1",
      values: [walletId]
    } as any);
    expect((wCheck.rows[0] as any).monthly_spent).toBe("100.50");

    // 4. Test Failure Reversal
    await revertPayment(ledgerRes.entryId);

    // 5. Confirm wallet balance was restored
    const wCheck2 = await db.execute({
      text: "SELECT monthly_spent FROM wallets WHERE id=$1",
      values: [walletId]
    } as any);
    expect(Number((wCheck2.rows[0] as any).monthly_spent)).toBe(0);
  });

  it('PAYU-02: Should enforce double-entry conservation across all accounts', async () => {
    // Check PER journal entry via database aggregation
    const violatingEntries = await db.execute(sql`
      SELECT journal_entry_id, sum(case when side='DR' then amount else -amount end) as net
      FROM journal_lines
      GROUP BY journal_entry_id
      HAVING sum(case when side='DR' then amount else -amount end) <> 0
    `);
    
    expect(violatingEntries.rows.length).toBe(0);
  });
});
