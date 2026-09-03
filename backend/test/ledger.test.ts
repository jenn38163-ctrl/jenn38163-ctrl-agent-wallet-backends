import { describe, it, expect, beforeAll } from 'vitest';
import { db } from '../src/db.js';
import { migrate, seedCredentials } from '../src/migrate.js';
import { postPayment } from '../src/ledger.js';
import { createFacility, processDrawRequest } from '../src/facilities.js';

describe('Ledger Exact Math and Limits', () => {
  let testWalletId: string;
  let testFacilityId: string;
  
  beforeAll(async () => {
    await migrate();
    await seedCredentials();
    
    // Create wallet for testing limits
    const wRes = await db.execute({
      text: "INSERT INTO wallets(name, organization, monthly_limit, per_request_limit) VALUES($1, $2, $3, $4) RETURNING id",
      values: ["Limit Test Wallet", "AI Corp Enterprises", 500, 100]
    } as any);
    testWalletId = (wRes.rows[0] as any).id;
    
    testFacilityId = `fac_test_${Date.now()}`;
    await createFacility(testFacilityId, "AI Corp Enterprises", "b1", "a1", "1000");
  });

  it('should deny payment exceeding per-request limit', async () => {
    await expect(postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "150.00", // exceeds 100
      currency: "USDC",
      vendor: "VendorA",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: `limit_key_${Date.now()}`
    })).rejects.toThrow(/Limits exceeded/);
  });

  it('should deny payment exceeding monthly limit', async () => {
    const key1 = `limit_key_${Date.now()}_1`;
    await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "90.00",
      currency: "USDC",
      vendor: "VendorA",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: key1
    });
    
    const key2 = `limit_key_${Date.now()}_2`;
    await expect(postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "450.00", // total 540 > 500
      currency: "USDC",
      vendor: "VendorA",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: key2
    })).rejects.toThrow(/Limits exceeded/);
  });

  it('should enforce idempotency for exact same request', async () => {
    const key = `idem_key_${Date.now()}`;
    
    const r1 = await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "10.00",
      currency: "USDC",
      vendor: "VendorIdem",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: key
    });
    
    // Simulate successful settlement response saving
    await db.execute({
      text: "UPDATE idempotency_keys SET response_status=201, response_body=$2 WHERE key=$1",
      values: [key, JSON.stringify({ status: 'OK' })]
    } as any);
    
    await expect(postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "10.00",
      currency: "USDC",
      vendor: "VendorIdem",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: key
    })).rejects.toThrow(/Idempotency key exists or is being processed/);
  });

  it('should reject idempotency key reuse with different request hash', async () => {
    const key = `idem_key_diff_${Date.now()}`;
    
    await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "10.00",
      currency: "USDC",
      vendor: "VendorIdem2",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: key
    });
    
    await expect(postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "20.00",
      currency: "USDC",
      vendor: "VendorIdem2",
      protocol: "HTTP_402",
      provider: "STARKNET",
      idempotencyKey: key
    })).rejects.toThrow(/Idempotency key reused with different request payload/);
  });

  it('should correctly draw from facility using string arithmetic', async () => {
    const key = `draw_key_${Date.now()}`;
    
    const res = await processDrawRequest(testFacilityId, "AI Corp Enterprises", "250.55", testWalletId, key, { draw: 1 });
    expect(res.status).toBe("SETTLEMENT_SUBMITTED");
    
    // Test credit limit
    await expect(processDrawRequest(testFacilityId, "AI Corp Enterprises", "1000.00", testWalletId, `draw_key_2_${Date.now()}`, {})).rejects.toThrow(/Credit risk check failed/);
  });
});
