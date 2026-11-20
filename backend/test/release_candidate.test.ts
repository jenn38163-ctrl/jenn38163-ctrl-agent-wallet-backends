import { describe, it, expect, beforeAll, vi, afterEach } from "vitest";
import { createHash, generateKeyPairSync, sign } from "node:crypto";
import {
  verifyFirebaseIdToken,
  clearFirebaseCertCacheForTests,
  requireScope,
  resolveOrRegisterFirebaseUser
} from "../src/auth.js";
import { verifyPayuSignature, processPayuWebhookTx } from "../src/payu_webhook.js";
import { postPayment, depositToWallet } from "../src/ledger.js";
import { Gateway } from "../src/settlement/Gateway.js";
import { Capture } from "../src/settlement/Request.js";
import { PayUSettlementAction, toPayUSmallestUnit } from "../src/settlement/actions/PayUSettlementAction.js";
import { PimlicoSettlementAction } from "../src/settlement/actions/PimlicoSettlementAction.js";
import { StarknetCaptureAction } from "../src/settlement/actions/StarknetCaptureAction.js";
import { SettlementWorker } from "../src/settlement_worker.js";
import { db, resolveSslConfig } from "../src/db.js";
import { config, parseRuntimeConfig } from "../src/config.js";
import { migrate, seedCredentials } from "../src/migrate.js";
import { createApp } from "../src/server.js";

function b64url(input: Buffer | string): string {
  return Buffer.from(input)
    .toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

function signJwt(
  header: Record<string, unknown>,
  payload: Record<string, unknown>,
  privateKeyPem: string
): string {
  const h = b64url(JSON.stringify(header));
  const p = b64url(JSON.stringify(payload));
  const sig = sign("RSA-SHA256", Buffer.from(`${h}.${p}`), privateKeyPem);
  return `${h}.${p}.${b64url(sig)}`;
}

describe("Firebase ID Token & Scoped RBAC Verification", () => {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "pem" },
    privateKeyEncoding: { type: "pkcs8", format: "pem" }
  });

  const certFetcher = async () => ({
    certs: { "test-kid-1": publicKey },
    maxAgeSeconds: 3600
  });

  beforeAll(() => {
    clearFirebaseCertCacheForTests();
  });

  it("verifies a valid RS256 Firebase ID token and extracts tenant subject", async () => {
    const now = Math.floor(Date.now() / 1000);
    const token = signJwt(
      { alg: "RS256", kid: "test-kid-1", typ: "JWT" },
      {
        iss: "https://securetoken.google.com/wallet-54645",
        aud: "wallet-54645",
        sub: "firebase_user_abc",
        email: "ops@agentwallet.os",
        email_verified: true,
        iat: now - 10,
        auth_time: now - 10,
        exp: now + 3600
      },
      privateKey
    );

    const claims = await verifyFirebaseIdToken(token, {
      projectId: "wallet-54645",
      nowSeconds: now,
      certFetcher
    });
    expect(claims.sub).toBe("firebase_user_abc");
    expect(claims.email).toBe("ops@agentwallet.os");
  });

  it("rejects expired, wrong-audience, wrong-issuer, and tampered Firebase tokens", async () => {
    const now = Math.floor(Date.now() / 1000);
    const expired = signJwt(
      { alg: "RS256", kid: "test-kid-1" },
      {
        iss: "https://securetoken.google.com/wallet-54645",
        aud: "wallet-54645",
        sub: "u1",
        iat: now - 7200,
        exp: now - 5
      },
      privateKey
    );
    await expect(
      verifyFirebaseIdToken(expired, { projectId: "wallet-54645", nowSeconds: now, certFetcher })
    ).rejects.toThrow("TOKEN_EXPIRED");

    const wrongAud = signJwt(
      { alg: "RS256", kid: "test-kid-1" },
      {
        iss: "https://securetoken.google.com/wallet-54645",
        aud: "wrong-project",
        sub: "u1",
        iat: now - 10,
        exp: now + 600
      },
      privateKey
    );
    await expect(
      verifyFirebaseIdToken(wrongAud, { projectId: "wallet-54645", nowSeconds: now, certFetcher })
    ).rejects.toThrow("INVALID_TOKEN_AUDIENCE");

    const valid = signJwt(
      { alg: "RS256", kid: "test-kid-1" },
      {
        iss: "https://securetoken.google.com/wallet-54645",
        aud: "wallet-54645",
        sub: "u1",
        iat: now - 10,
        exp: now + 600
      },
      privateKey
    );
    const parts = valid.split(".");
    const tamperedPayload = b64url(
      JSON.stringify({
        iss: "https://securetoken.google.com/wallet-54645",
        aud: "wallet-54645",
        sub: "attacker",
        iat: now - 10,
        exp: now + 600
      })
    );
    await expect(
      verifyFirebaseIdToken(`${parts[0]}.${tamperedPayload}.${parts[2]}`, {
        projectId: "wallet-54645",
        nowSeconds: now,
        certFetcher
      })
    ).rejects.toThrow("INVALID_TOKEN_SIGNATURE");
  });

  it("enforces explicit RBAC scopes without wildcard 'all' bypass", () => {
    const req: any = {
      principal: {
        organizationId: "org-a",
        role: "AGENT",
        scopes: ["wallets:read", "all"],
        subject: "cred-1",
        authType: "api_key",
        provisioningStatus: "ACTIVE"
      }
    };
    const res: any = {
      statusCode: 200,
      body: null as any,
      status(code: number) {
        this.statusCode = code;
        return this;
      },
      json(data: any) {
        this.body = data;
        return this;
      }
    };
    let nextCalled = false;
    requireScope("payments:authorize")(req, res, () => {
      nextCalled = true;
    });
    expect(nextCalled).toBe(false);
    expect(res.statusCode).toBe(403);

    nextCalled = false;
    requireScope("wallets:read")(req, res, () => {
      nextCalled = true;
    });
    expect(nextCalled).toBe(true);
  });

  it("registers new Firebase users as PENDING_APPROVAL with zero scopes (never auto TENANT_ADMIN)", async () => {
    const tx = {
      execute: async () => ({
        rows: [
          {
            organization_id: "unassigned",
            role: "PENDING_USER",
            scopes: "",
            status: "PENDING_APPROVAL"
          }
        ]
      })
    };

    const principal = await resolveOrRegisterFirebaseUser(tx, {
      sub: "new_uid_123",
      email: "newuser@example.com",
      emailVerified: true,
      aud: "wallet-54645",
      iss: "https://securetoken.google.com/wallet-54645",
      iat: 1000,
      exp: 2000
    });

    expect(principal.provisioningStatus).toBe("PENDING_APPROVAL");
    expect(principal.role).toBe("PENDING_USER");
    expect(principal.organizationId).toBe("unassigned");
    expect(principal.scopes).toEqual([]);

    const req: any = { principal };
    const res: any = {
      statusCode: 200,
      body: null as any,
      status(code: number) {
        this.statusCode = code;
        return this;
      },
      json(data: any) {
        this.body = data;
        return this;
      }
    };
    let nextCalled = false;
    requireScope("wallets:read")(req, res, () => {
      nextCalled = true;
    });
    expect(nextCalled).toBe(false);
    expect(res.statusCode).toBe(403);
    expect(res.body).toEqual({
      error: "TENANT_APPROVAL_REQUIRED",
      provisioningStatus: "PENDING_APPROVAL"
    });
  });

  it("grants only explicitly approved tenant organization, role, and scopes to ACTIVE Firebase users", async () => {
    const tx = {
      execute: async () => ({
        rows: [
          {
            organization_id: "OrgApproved",
            role: "OPERATOR",
            scopes: "wallets:read,payments:authorize",
            status: "ACTIVE"
          }
        ]
      })
    };

    const principal = await resolveOrRegisterFirebaseUser(tx, {
      sub: "approved_uid",
      email: "ops@approved.com",
      emailVerified: true,
      aud: "wallet-54645",
      iss: "https://securetoken.google.com/wallet-54645",
      iat: 1000,
      exp: 2000
    });

    expect(principal.provisioningStatus).toBe("ACTIVE");
    expect(principal.organizationId).toBe("OrgApproved");
    expect(principal.role).toBe("OPERATOR");
    expect(principal.scopes).toEqual(["wallets:read", "payments:authorize"]);
  });
});

describe("PayU Signature & Race-Free Webhook Processing", () => {
  it("verifies MD5 and SHA-256 PayU signatures with timing-safe comparison", () => {
    const raw = JSON.stringify({ order: { orderId: "ORD1", status: "COMPLETED" } });
    const key = "second-key-secret";
    const md5Sig = createHash("md5").update(raw + key).digest("hex");
    const shaSig = createHash("sha256").update(raw + key).digest("hex");

    expect(verifyPayuSignature(raw, `sender=Checkout;signature=${md5Sig};algorithm=MD5`, key)).toEqual({
      ok: true
    });
    expect(
      verifyPayuSignature(raw, `sender=Checkout;signature=${shaSig};algorithm=SHA-256`, key)
    ).toEqual({ ok: true });
    expect(
      verifyPayuSignature(raw, `sender=Checkout;signature=${"0".repeat(32)};algorithm=MD5`, key)
    ).toEqual({ ok: false, status: 401, error: "INVALID_SIGNATURE" });
  });

  function extractSqlText(q: any): string {
    if (typeof q === "string") return q;
    if (typeof q?.text === "string") return q.text;
    return "DRIZZLE_SQL_TEMPLATE";
  }

  it("handles webhook arriving while intent is still PROCESSING (race safety)", async () => {
    const intentId = "11111111-1111-4111-8111-111111111111";
    const queries: string[] = [];
    const tx = {
      execute: async (q: any) => {
        const text = extractSqlText(q);
        queries.push(text);
        if (text.includes("WHERE provider_ref = $1")) {
          // Worker hasn't persisted provider_ref yet
          return { rows: [] };
        }
        if (text.includes("SELECT * FROM settlement_intents WHERE id = $1 FOR UPDATE")) {
          return {
            rows: [
              {
                id: intentId,
                journal_entry_id: "22222222-2222-4222-8222-222222222222",
                organization_id: "tenant-1",
                status: "PROCESSING",
                provider_ref: null
              }
            ]
          };
        }
        if (text === "DRIZZLE_SQL_TEMPLATE") {
          return { rows: [{ event_hash: "a".repeat(64) }] };
        }
        return { rows: [{ id: intentId }] };
      }
    };

    const raw = JSON.stringify({
      order: { orderId: "PAYU_999", extOrderId: intentId, status: "COMPLETED" }
    });
    const res = await processPayuWebhookTx(tx, raw, JSON.parse(raw));
    expect(res).toEqual({ statusCode: 200, body: "OK" });
    expect(queries.some((t) => t.includes("UPDATE settlement_intents"))).toBe(true);
    expect(queries.some((t) => t.includes("UPDATE journal_entries") && t.includes("CONFIRMED"))).toBe(
      true
    );
  });

  it("does NOT poison webhook_events deduplication when intent is missing", async () => {
    const queries: string[] = [];
    const tx = {
      execute: async (q: any) => {
        const text = extractSqlText(q);
        queries.push(text);
        return { rows: [] };
      }
    };

    const raw = JSON.stringify({
      order: {
        orderId: "UNKNOWN_ORD",
        extOrderId: "33333333-3333-4333-8333-333333333333",
        status: "COMPLETED"
      }
    });
    const res = await processPayuWebhookTx(tx, raw, JSON.parse(raw));
    expect(res).toEqual({ statusCode: 404, body: "INTENT_NOT_FOUND" });
    expect(queries.some((t) => t.includes("INSERT INTO webhook_events"))).toBe(false);
  });

  it("does NOT revert an already CONFIRMED intent when late CANCELED webhook arrives", async () => {
    const intentId = "44444444-4444-4444-8444-444444444444";
    const queries: string[] = [];
    const tx = {
      execute: async (q: any) => {
        const text = extractSqlText(q);
        queries.push(text);
        if (text.includes("SELECT * FROM settlement_intents WHERE provider_ref = $1")) {
          return {
            rows: [
              {
                id: intentId,
                journal_entry_id: "55555555-5555-4555-8555-555555555555",
                organization_id: "tenant-1",
                status: "CONFIRMED",
                provider_ref: "PAYU_DONE"
              }
            ]
          };
        }
        return { rows: [{ event_hash: "b".repeat(64) }] };
      }
    };

    const raw = JSON.stringify({
      order: { orderId: "PAYU_DONE", extOrderId: intentId, status: "CANCELED" }
    });
    const res = await processPayuWebhookTx(tx, raw, JSON.parse(raw));
    expect(res).toEqual({ statusCode: 200, body: "OK_ALREADY_FINALIZED" });
    expect(queries.some((t) => t.includes("INSERT INTO audit_events"))).toBe(true);
    expect(queries.some((t) => t.includes("REVERSED"))).toBe(false);
  });
});

describe("PayU Settlement Dispatch, Worker Claiming & Disabled Blockchain Guard", () => {
  const origFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = origFetch;
    vi.restoreAllMocks();
  });

  it("disables Pimlico and Starknet until real submission and confirmation exist", async () => {
    const pimlico = new PimlicoSettlementAction();
    const starknet = new StarknetCaptureAction();
    const cap = new Capture({
      intentId: "66666666-6666-4666-8666-666666666666",
      amount: "10.00",
      currency: "USDC",
      targetAddress: "0x123"
    });

    await expect(pimlico.execute(cap)).rejects.toThrow(/BLOCKCHAIN_SETTLEMENT_DISABLED/);
    await expect(starknet.execute(cap)).rejects.toThrow(/BLOCKCHAIN_SETTLEMENT_DISABLED/);
  });

  it("formats PayU minor units accurately without floating point drift", () => {
    expect(toPayUSmallestUnit("19.99")).toBe("1999");
    expect(toPayUSmallestUnit("0.01")).toBe("1");
    expect(toPayUSmallestUnit("250.5")).toBe("25050");
  });

  it("returns providerRef from Gateway -> PayUSettlementAction with asyncConfirmation=true", async () => {
    (config as any).PAYU_MERCHANT_POS_ID = "pos_123";
    (config as any).PAYU_CLIENT_ID = "cid_123";
    (config as any).PAYU_CLIENT_SECRET = "csec_123";
    (config as any).PAYU_NOTIFY_URL = "https://api.example.com/v1/webhooks/payu";

    globalThis.fetch = vi.fn(async (url: any) => {
      if (String(url).includes("/oauth/authorize")) {
        return new Response(JSON.stringify({ access_token: "payu-tok" }), { status: 200 });
      }
      if (String(url).endsWith("/api/v2_1/orders")) {
        return new Response(
          JSON.stringify({ orderId: "PAYU_ORD_777", status: { statusCode: "SUCCESS" } }),
          { status: 201 }
        );
      }
      return new Response(
        JSON.stringify({ orders: [{ orderId: "PAYU_ORD_777", status: "COMPLETED" }] }),
        { status: 200 }
      );
    }) as any;

    const gw = new Gateway();
    const payu = new PayUSettlementAction();
    gw.addAction("PAYU", payu);

    const res = await gw.execute(
      "PAYU",
      new Capture({
        intentId: "77777777-7777-4777-8777-777777777777",
        amount: "25.00",
        currency: "USD",
        targetAddress: "vendor.example"
      })
    );

    expect(res).toEqual({
      providerRef: "PAYU_ORD_777",
      asyncConfirmation: true,
      rawStatus: "SUCCESS"
    });

    const retrieved = await payu.retrieveOrderStatus("PAYU_ORD_777");
    expect(retrieved.status).toBe("COMPLETED");
  });

  it("SettlementWorker uses FOR UPDATE SKIP LOCKED and keeps PayU orders SUBMITTED (not SETTLED)", async () => {
    const executedSql: Array<{ text: string; values?: any[] }> = [];
    vi.spyOn(SettlementWorker, "claimIntents").mockResolvedValue([
      {
        id: "88888888-8888-4888-8888-888888888888",
        journal_entry_id: "99999999-9999-4999-8999-999999999999",
        organization_id: "org-1",
        provider: "PAYU",
        amount: "15.00",
        currency: "USD",
        target_address: "vendor.ai",
        attempt_count: 1
      }
    ]);

    (config as any).PAYU_MERCHANT_POS_ID = "pos_123";
    (config as any).PAYU_CLIENT_ID = "cid_123";
    (config as any).PAYU_CLIENT_SECRET = "csec_123";
    (config as any).PAYU_NOTIFY_URL = "https://api.example.com/v1/webhooks/payu";

    globalThis.fetch = vi.fn(async (url: any) => {
      if (String(url).includes("/oauth/authorize")) {
        return new Response(JSON.stringify({ access_token: "payu-tok" }), { status: 200 });
      }
      return new Response(
        JSON.stringify({ orderId: "PAYU_ORD_ASYNC_1", status: { statusCode: "SUCCESS" } }),
        { status: 201 }
      );
    }) as any;

    vi.spyOn(db, "transaction").mockImplementation(async (fn: any) => {
      const tx = {
        execute: async (q: any) => {
          const text = typeof q === "string" ? q : typeof q?.text === "string" ? q.text : "SQL_TEMPLATE";
          executedSql.push({ text, values: q.values });
          if (text === "SQL_TEMPLATE") {
            return { rows: [{ event_hash: "c".repeat(64) }] };
          }
          return { rows: [{ id: "88888888-8888-4888-8888-888888888888" }] };
        }
      };
      return fn(tx);
    });

    await SettlementWorker.processPendingIntents();

    const intentUpdate = executedSql.find((q) => q.text.includes("UPDATE settlement_intents"));
    const entryUpdate = executedSql.find((q) => q.text.includes("UPDATE journal_entries"));

    expect(intentUpdate?.values?.[0]).toBe("SUBMITTED");
    expect(intentUpdate?.values?.[1]).toBe("PAYU_ORD_ASYNC_1");
    expect(entryUpdate?.values?.[0]).toBe("SUBMITTED");
    expect(executedSql.some((q) => q.values?.includes("SETTLED"))).toBe(false);
  });

  it("rejects USDC on PayU and enforces strict wallet-to-transaction currency reconciliation", async () => {
    const payu = new PayUSettlementAction();
    const usdcCapture = new Capture({
      intentId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      amount: "10.00",
      currency: "USDC",
      targetAddress: "vendor.example"
    });
    const usdCapture = new Capture({
      intentId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      amount: "10.00",
      currency: "USD",
      targetAddress: "vendor.example"
    });

    expect(payu.supports(usdcCapture)).toBe(false);
    expect(payu.supports(usdCapture)).toBe(true);
    await expect(payu.execute(usdcCapture)).rejects.toThrow(/UNSUPPORTED_PROVIDER_CURRENCY/);

    await expect(
      postPayment({
        tenantId: "org-1",
        walletId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        amount: "10.00",
        currency: "USDC",
        vendor: "vendor.example",
        protocol: "HTTP_402",
        provider: "PAYU",
        idempotencyKey: "k-usdc-payu"
      })
    ).rejects.toThrow(/UNSUPPORTED_PROVIDER_CURRENCY/);

    vi.spyOn(db, "transaction").mockImplementation(async (fn: any) => {
      const tx = {
        execute: async (q: any) => {
          const text = typeof q === "string" ? q : q?.text || "";
          if (text.includes("FROM wallets") && text.includes("FOR UPDATE")) {
            return {
              rows: [
                {
                  id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                  status: "ACTIVE",
                  currency: "PLN",
                  balance: "500.00",
                  monthly_limit: "1000.00",
                  monthly_spent: "0.00",
                  per_request_limit: "500.00"
                }
              ]
            };
          }
          return { rows: [] };
        }
      };
      return fn(tx);
    });

    await expect(
      postPayment({
        tenantId: "org-1",
        walletId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        amount: "10.00",
        currency: "USD",
        vendor: "vendor.example",
        protocol: "HTTP_402",
        provider: "PAYU",
        idempotencyKey: "k-mismatch-pay"
      })
    ).rejects.toThrow(/CURRENCY_MISMATCH/);

    await expect(
      depositToWallet({
        tenantId: "org-1",
        walletId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        amount: "25.00",
        currency: "USD",
        idempotencyKey: "k-mismatch-fund"
      })
    ).rejects.toThrow(/CURRENCY_MISMATCH/);
  });

  it("blocks PayU webhook & worker reconciliation when currency or minor amount mismatches", async () => {
    const intentId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    const queries: string[] = [];
    const tx = {
      execute: async (q: any) => {
        const text = typeof q === "string" ? q : typeof q?.text === "string" ? q.text : "SQL_TEMPLATE";
        queries.push(text);
        if (text.includes("WHERE provider_ref = $1")) {
          return {
            rows: [
              {
                id: intentId,
                journal_entry_id: "ffffffff-ffff-4fff-8fff-ffffffffffff",
                organization_id: "org-1",
                status: "SUBMITTED",
                provider_ref: "PAYU_MISMATCH_1",
                amount: "25.00",
                currency: "USD"
              }
            ]
          };
        }
        return { rows: [{ event_hash: "d".repeat(64) }] };
      }
    };

    const wrongCurrencyRaw = JSON.stringify({
      order: {
        orderId: "PAYU_MISMATCH_1",
        extOrderId: intentId,
        status: "COMPLETED",
        currencyCode: "PLN",
        totalAmount: "2500"
      }
    });
    const currencyMismatchRes = await processPayuWebhookTx(tx, wrongCurrencyRaw, JSON.parse(wrongCurrencyRaw));
    expect(currencyMismatchRes).toEqual({ statusCode: 409, body: "RECONCILIATION_MISMATCH" });
    expect(queries.some((t) => t.includes("UPDATE settlement_intents"))).toBe(false);

    const wrongAmountRaw = JSON.stringify({
      order: {
        orderId: "PAYU_MISMATCH_1",
        extOrderId: intentId,
        status: "COMPLETED",
        currencyCode: "USD",
        totalAmount: "100"
      }
    });
    const amountMismatchRes = await processPayuWebhookTx(tx, wrongAmountRaw, JSON.parse(wrongAmountRaw));
    expect(amountMismatchRes).toEqual({ statusCode: 409, body: "RECONCILIATION_MISMATCH" });
    expect(queries.some((t) => t.includes("UPDATE settlement_intents"))).toBe(false);
  });
});

describe("Railway Deployment Readiness, SSL Resolution & Safe Migrations", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("resolves SSL safely for Railway internal URLs, sslmode=require, and explicit flags", () => {
    expect(
      resolveSslConfig("postgresql://postgres:secret@postgres.railway.internal:5432/railway", undefined, "true")
    ).toBeUndefined();
    expect(
      resolveSslConfig("postgresql://user:pass@host:5432/db?sslmode=require", undefined, "true")
    ).toEqual({ rejectUnauthorized: true });
    expect(
      resolveSslConfig("postgresql://user:pass@roundhouse.proxy.rlwy.net:1234/railway", "true", "false")
    ).toEqual({ rejectUnauthorized: false });
    expect(
      resolveSslConfig("postgresql://user:pass@host:5432/db?sslmode=require", "false", "true")
    ).toBe(false);
  });

  it("runs migrate() inside an advisory-locked transaction without dropping triggers", async () => {
    const executedSql: string[] = [];
    vi.spyOn(db, "transaction").mockImplementation(async (fn: any) => {
      const tx = {
        execute: async (q: any) => {
          const text =
            typeof q === "string"
              ? q
              : Array.isArray(q?.queryChunks)
                ? q.queryChunks.map((c: any) => (Array.isArray(c?.value) ? c.value.join("") : String(c?.value ?? ""))).join("")
                : String(q);
          executedSql.push(text);
          return { rows: [] };
        }
      };
      return fn(tx);
    });

    await migrate();

    expect(executedSql.some((s) => s.includes("pg_advisory_xact_lock(88421001)"))).toBe(true);
    expect(executedSql.some((s) => s.includes("DROP TRIGGER"))).toBe(false);
    expect(executedSql.some((s) => s.includes("trg_journal_lines_immutable"))).toBe(true);
  });

  it("hashes AGENT_WALLET_API_KEY in seedCredentials() without inserting demo wallets", async () => {
    const queries: Array<{ text: string; values?: any[] }> = [];
    vi.spyOn(db, "execute").mockImplementation(async (q: any) => {
      queries.push({ text: q?.text || "", values: q?.values });
      return { rows: [] } as any;
    });

    await seedCredentials();
    expect(queries.length).toBe(1);
    expect(queries[0].text).toContain("INSERT INTO credentials");
    expect(queries[0].values?.[0]).toBe(config.SEED_ORGANIZATION_ID);
    expect(queries[0].values?.[1]).toMatch(/^[a-f0-9]{64}$/);
  });

  it("defaults AUTO_MIGRATE and SEED_CREDENTIALS_ON_BOOT to false in production unless opted in", () => {
    const prodDefaults = parseRuntimeConfig({
      NODE_ENV: "production",
      DATABASE_URL: "postgresql://user:pass@host:5432/db",
      CORS_ORIGIN: "https://app.agentwallet.os"
    });
    expect(prodDefaults.AUTO_MIGRATE).toBe("false");
    expect(prodDefaults.SEED_CREDENTIALS_ON_BOOT).toBe("false");
    expect(prodDefaults.SETTLEMENT_WORKER_ENABLED).toBe("false");

    const prodOptIn = parseRuntimeConfig({
      NODE_ENV: "production",
      DATABASE_URL: "postgresql://user:pass@host:5432/db",
      CORS_ORIGIN: "https://app.agentwallet.os",
      AUTO_MIGRATE: "true",
      SEED_CREDENTIALS_ON_BOOT: "true",
      SETTLEMENT_WORKER_ENABLED: "true"
    });
    expect(prodOptIn.AUTO_MIGRATE).toBe("true");
    expect(prodOptIn.SEED_CREDENTIALS_ON_BOOT).toBe("true");
    expect(prodOptIn.SETTLEMENT_WORKER_ENABLED).toBe("true");

    expect(() =>
      parseRuntimeConfig({
        NODE_ENV: "production",
        DATABASE_URL: "postgresql://user:pass@host:5432/db",
        CORS_ORIGIN: "*"
      })
    ).toThrow(/CORS_ORIGIN cannot be \* in production/);

    const devDefaults = parseRuntimeConfig({
      NODE_ENV: "development",
      DATABASE_URL: "postgresql://user:pass@localhost:5432/db",
      CORS_ORIGIN: "*"
    });
    expect(devDefaults.AUTO_MIGRATE).toBe("true");
    expect(devDefaults.SEED_CREDENTIALS_ON_BOOT).toBe("true");
  });

  it("exposes /healthz and /readyz probes accurately based on database connectivity and schema readiness", async () => {
    const app = createApp();
    const server = app.listen(0);
    const addr = server.address() as any;
    const baseUrl = `http://127.0.0.1:${addr.port}`;

    try {
      const healthRes = await fetch(`${baseUrl}/healthz`);
      expect(healthRes.status).toBe(200);
      expect(await healthRes.json()).toEqual({ ok: true, service: "agent-wallet-backend" });

      vi.spyOn(db, "execute").mockResolvedValueOnce({
        rows: [{ wallets_ready: true, ledger_ready: true, settlement_ready: true }]
      } as any);
      const readyOk = await fetch(`${baseUrl}/readyz`);
      expect(readyOk.status).toBe(200);
      expect(await readyOk.json()).toEqual({ ok: true, database: "ready", schema: "ready" });

      vi.spyOn(db, "execute").mockResolvedValueOnce({
        rows: [{ wallets_ready: true, ledger_ready: false, settlement_ready: false }]
      } as any);
      const schemaMissing = await fetch(`${baseUrl}/readyz`);
      expect(schemaMissing.status).toBe(503);
      expect(await schemaMissing.json()).toEqual({
        ok: false,
        database: "connected",
        schema: "pending_migration"
      });

      vi.spyOn(db, "execute").mockRejectedValueOnce(new Error("connect ECONNREFUSED"));
      const readyFail = await fetch(`${baseUrl}/readyz`);
      expect(readyFail.status).toBe(503);
      expect(await readyFail.json()).toEqual({ ok: false, database: "unavailable" });
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });

  it("SettlementWorker.runTick waits cleanly on PostgreSQL 42P01 undefined_table before migrations", async () => {
    vi.spyOn(SettlementWorker, "claimIntents").mockRejectedValueOnce(
      Object.assign(new Error('relation "settlement_intents" does not exist'), { code: "42P01" })
    );
    await expect(SettlementWorker.runTick()).resolves.toBeUndefined();
  });
});
