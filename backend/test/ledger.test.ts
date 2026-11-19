import { describe, it, expect, beforeAll } from "vitest";
import { db } from "../src/db.js";
import { migrate, seedCredentials } from "../src/migrate.js";
import { postPayment } from "../src/ledger.js";
import { createFacility, processDrawRequest } from "../src/facilities.js";

const runPgTests = process.env.RUN_PG_TESTS === "true";

describe.runIf(runPgTests)("Ledger Exact Math, Limits and Tenant-Scoped Idempotency", () => {
  let testWalletId: string;
  let testFacilityId: string;

  beforeAll(async () => {
    await migrate();
    await seedCredentials();

    const wRes = await db.execute({
      text: "INSERT INTO wallets(name, organization, currency, balance, monthly_limit, per_request_limit) VALUES($1, $2, $3, $4, $5, $6) RETURNING id",
      values: ["Limit Test Wallet", "AI Corp Enterprises", "USD", 1000, 500, 100]
    } as any);
    testWalletId = (wRes.rows[0] as any).id;

    testFacilityId = `fac_test_${Date.now()}`;
    await createFacility(testFacilityId, "AI Corp Enterprises", "b1", "a1", "1000");
  });

  it("should deny payment exceeding per-request limit", async () => {
    await expect(
      postPayment({
        tenantId: "AI Corp Enterprises",
        walletId: testWalletId,
        amount: "150.00",
        currency: "USD",
        vendor: "VendorA",
        protocol: "HTTP_402",
        provider: "PAYU",
        idempotencyKey: `limit_key_${Date.now()}`
      })
    ).rejects.toThrow(/Limits exceeded/);
  });

  it("should deny payment exceeding monthly limit", async () => {
    const key1 = `limit_key_${Date.now()}_1`;
    await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "90.00",
      currency: "USD",
      vendor: "VendorA",
      protocol: "HTTP_402",
      provider: "PAYU",
      idempotencyKey: key1
    });

    const key2 = `limit_key_${Date.now()}_2`;
    await expect(
      postPayment({
        tenantId: "AI Corp Enterprises",
        walletId: testWalletId,
        amount: "450.00",
        currency: "USD",
        vendor: "VendorA",
        protocol: "HTTP_402",
        provider: "PAYU",
        idempotencyKey: key2
      })
    ).rejects.toThrow(/Limits exceeded/);
  });

  it("should isolate idempotency keys per tenant", async () => {
    const sharedKey = `shared_idem_${Date.now()}`;
    await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "10.00",
      currency: "USD",
      vendor: "VendorIdem",
      protocol: "HTTP_402",
      provider: "PAYU",
      idempotencyKey: sharedKey
    });

    const wBRes = await db.execute({
      text: "INSERT INTO wallets(name, organization, currency, balance, monthly_limit, per_request_limit) VALUES($1, $2, $3, $4, $5, $6) RETURNING id",
      values: ["Tenant B Wallet", "Tenant B Corp", "USD", 500, 500, 100]
    } as any);
    const tenantBWalletId = (wBRes.rows[0] as any).id;

    // Same idempotency key for a different tenant must succeed independently
    const rB = await postPayment({
      tenantId: "Tenant B Corp",
      walletId: tenantBWalletId,
      amount: "10.00",
      currency: "USD",
      vendor: "VendorIdem",
      protocol: "HTTP_402",
      provider: "PAYU",
      idempotencyKey: sharedKey
    });
    expect(rB.entryId).toBeDefined();
  });

  it("should reject idempotency key reuse with different request hash in same tenant", async () => {
    const key = `idem_key_diff_${Date.now()}`;

    await postPayment({
      tenantId: "AI Corp Enterprises",
      walletId: testWalletId,
      amount: "10.00",
      currency: "USD",
      vendor: "VendorIdem2",
      protocol: "HTTP_402",
      provider: "PAYU",
      idempotencyKey: key
    });

    await expect(
      postPayment({
        tenantId: "AI Corp Enterprises",
        walletId: testWalletId,
        amount: "20.00",
        currency: "USD",
        vendor: "VendorIdem2",
        protocol: "HTTP_402",
        provider: "PAYU",
        idempotencyKey: key
      })
    ).rejects.toThrow(/Idempotency key reused with different request payload/);
  });

  it("should correctly draw from facility using string arithmetic", async () => {
    const key = `draw_key_${Date.now()}`;

    const res = await processDrawRequest(
      testFacilityId,
      "AI Corp Enterprises",
      "250.55",
      testWalletId,
      key,
      { draw: 1 }
    );
    expect(res.status).toBe("SETTLEMENT_SUBMITTED");

    await expect(
      processDrawRequest(
        testFacilityId,
        "AI Corp Enterprises",
        "1000.00",
        testWalletId,
        `draw_key_2_${Date.now()}`,
        {}
      )
    ).rejects.toThrow(/Credit risk check failed/);
  });

  it("should reject facility draw to a wallet belonging to a different tenant", async () => {
    const wRes = await db.execute({
      text: "INSERT INTO wallets(name, organization, balance, monthly_limit, per_request_limit) VALUES($1, $2, $3, $4, $5) RETURNING id",
      values: ["Tenant B Wallet 2", "Tenant B Corp", 500, 500, 100]
    } as any);
    const tenantBWalletId = (wRes.rows[0] as any).id;

    const key = `cross_tenant_draw_${Date.now()}`;
    await expect(
      processDrawRequest(
        testFacilityId,
        "AI Corp Enterprises",
        "50.00",
        tenantBWalletId,
        key,
        { crossTenant: true }
      )
    ).rejects.toThrow(/Wallet not found, inactive, or does not belong to tenant/);
  });
});
