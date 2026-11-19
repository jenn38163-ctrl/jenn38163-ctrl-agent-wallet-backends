import express from "express";
import cors from "cors";
import helmet from "helmet";
import { config } from "./config.js";
import { db } from "./db.js";
import { migrate, seedCredentials } from "./migrate.js";
import { auth, requireScope, ASSIGNABLE_ROLES, ASSIGNABLE_SCOPES } from "./auth.js";
import { audit } from "./audit.js";
import { validAmount, hashRequest } from "./utils.js";
import {
  postPayment,
  depositToWallet,
  normalizeCurrency,
  PROVIDER_CURRENCIES
} from "./ledger.js";
import { createFacility, processDrawRequest } from "./facilities.js";
import { SettlementWorker } from "./settlement_worker.js";
import { verifyPayuSignature, processPayuWebhookTx } from "./payu_webhook.js";

const DISABLED_PROVIDERS = new Set(["STARKNET", "PIMLICO"]);
const SUPPORTED_PROVIDERS = new Set(["PAYU"]);

export function createApp() {
  const app = express();
  app.use(helmet());
  app.use(cors({ origin: config.CORS_ORIGIN === "*" ? true : config.CORS_ORIGIN }));

  app.post(
    "/v1/webhooks/payu",
    express.raw({ type: "application/json", limit: "256kb" }),
    async (req, res) => {
      try {
        const signatureHeader = req.header("OpenPayu-Signature") ?? "";
        const rawBody = Buffer.isBuffer(req.body) ? req.body.toString("utf8") : "";
        const sigCheck = verifyPayuSignature(rawBody, signatureHeader, config.PAYU_SECOND_KEY);
        if (!sigCheck.ok) {
          return res.status(sigCheck.status).send(sigCheck.error);
        }

        let data: any;
        try {
          data = JSON.parse(rawBody);
        } catch {
          return res.status(400).send("INVALID_JSON");
        }

        const result = await db.transaction(async (tx) =>
          processPayuWebhookTx(tx, rawBody, data)
        );
        return res.status(result.statusCode).send(result.body);
      } catch (e) {
        console.error("PayU webhook error:", e);
        return res.status(500).send("ERROR");
      }
    }
  );

  app.use(express.json({ limit: "256kb" }));
  app.get("/healthz", (_, res) => res.json({ ok: true, service: "agent-wallet-backend" }));
  app.get("/readyz", async (_, res) => {
    try {
      await db.execute("select 1");
      res.json({ ok: true, database: "ready" });
    } catch {
      res.status(503).json({ ok: false, database: "unavailable" });
    }
  });

  app.use("/v1", auth);

  app.get("/v1/me", async (req, res) => {
    const p = req.principal;
    if (!p) return res.status(401).json({ error: "UNAUTHORIZED" });

    let requestedOrganization: string | null = null;
    if (p.authType === "firebase_jwt") {
      try {
        const r = await db.execute({
          text: "SELECT requested_organization FROM tenant_users WHERE firebase_uid = $1",
          values: [p.subject]
        } as any);
        requestedOrganization = (r.rows[0] as any)?.requested_organization ?? null;
      } catch {
        // Ignore non-fatal metadata read error
      }
    }

    res.json({
      subject: p.subject,
      email: p.email ?? null,
      authType: p.authType,
      organizationId: p.organizationId,
      role: p.role,
      provisioningStatus: p.provisioningStatus,
      scopes: p.scopes,
      requestedOrganization
    });
  });

  app.post("/v1/tenant-access/request", async (req, res) => {
    const p = req.principal;
    if (!p) return res.status(401).json({ error: "UNAUTHORIZED" });
    if (p.authType !== "firebase_jwt") {
      return res.status(400).json({ error: "ONLY_FIREBASE_USERS_CAN_REQUEST_ACCESS" });
    }
    const organizationName =
      typeof req.body?.organizationName === "string" ? req.body.organizationName.trim() : "";
    if (!organizationName || organizationName.length > 160) {
      return res.status(400).json({ error: "INVALID_ORGANIZATION_NAME" });
    }

    try {
      await db.transaction(async (tx) => {
        await tx.execute({
          text: `UPDATE tenant_users
                 SET requested_organization = $2, updated_at = now()
                 WHERE firebase_uid = $1`,
          values: [p.subject, organizationName]
        } as any);
        await audit(
          tx,
          "TENANT_ACCESS_REQUESTED",
          p.email || p.subject,
          { firebaseUid: p.subject, requestedOrganization: organizationName },
          "system"
        );
      });
      res.status(202).json({
        ok: true,
        provisioningStatus: p.provisioningStatus,
        requestedOrganization: organizationName
      });
    } catch {
      res.status(500).json({ error: "TENANT_ACCESS_REQUEST_FAILED" });
    }
  });

  app.get("/v1/admin/tenant-users/pending", requireScope("tenants:approve"), async (_req, res) => {
    try {
      const r = await db.execute({
        text: `SELECT firebase_uid, email, organization_id, role, status, requested_organization, created_at, updated_at
               FROM tenant_users
               WHERE status = 'PENDING_APPROVAL'
               ORDER BY created_at DESC
               LIMIT 100`
      } as any);
      res.json({ pendingUsers: r.rows });
    } catch {
      res.status(500).json({ error: "PENDING_USERS_FETCH_FAILED" });
    }
  });

  app.post("/v1/admin/tenant-users/approve", requireScope("tenants:approve"), async (req, res) => {
    const { firebaseUid, organizationId, role, scopes } = req.body ?? {};
    const uid = typeof firebaseUid === "string" ? firebaseUid.trim() : "";
    const org = typeof organizationId === "string" ? organizationId.trim() : "";
    const normalizedRole = typeof role === "string" ? role.trim().toUpperCase() : "";
    const scopeList = Array.isArray(scopes)
      ? scopes.map((s) => String(s).trim()).filter(Boolean)
      : typeof scopes === "string"
        ? scopes
            .split(",")
            .map((s) => s.trim())
            .filter(Boolean)
        : [];

    if (!uid || !org || org === "unassigned" || org.length > 160) {
      return res.status(400).json({ error: "INVALID_TENANT_TARGET" });
    }
    if (!ASSIGNABLE_ROLES.has(normalizedRole)) {
      return res.status(400).json({
        error: "INVALID_ROLE",
        allowedRoles: Array.from(ASSIGNABLE_ROLES)
      });
    }
    if (scopeList.length === 0 || scopeList.some((s) => !ASSIGNABLE_SCOPES.has(s))) {
      return res.status(400).json({
        error: "INVALID_SCOPES",
        allowedScopes: Array.from(ASSIGNABLE_SCOPES)
      });
    }

    const joinedScopes = Array.from(new Set(scopeList)).join(",");

    try {
      const updated = await db.transaction(async (tx) => {
        const resUpd = await tx.execute({
          text: `UPDATE tenant_users
                 SET organization_id = $2,
                     role = $3,
                     scopes = $4,
                     status = 'ACTIVE',
                     approved_by = $5,
                     approved_at = now(),
                     updated_at = now()
                 WHERE firebase_uid = $1
                 RETURNING firebase_uid, email, organization_id, role, scopes, status, approved_by, approved_at`,
          values: [uid, org, normalizedRole, joinedScopes, req.principal?.subject || "admin"]
        } as any);
        const row = resUpd.rows[0] as any;
        if (!row) {
          throw Object.assign(new Error("TENANT_USER_NOT_FOUND"), { status: 404 });
        }
        await audit(
          tx,
          "TENANT_USER_APPROVED",
          req.principal?.subject || "admin",
          {
            firebaseUid: uid,
            organizationId: org,
            role: normalizedRole,
            scopes: joinedScopes
          },
          org
        );
        return row;
      });
      res.json({ ok: true, user: updated });
    } catch (e: any) {
      const s = Number.isInteger(e?.status) ? e.status : 500;
      res.status(s).json({ error: e?.message || "TENANT_APPROVAL_FAILED" });
    }
  });

  app.post("/v1/admin/tenant-users/revoke", requireScope("tenants:approve"), async (req, res) => {
    const uid = typeof req.body?.firebaseUid === "string" ? req.body.firebaseUid.trim() : "";
    const targetStatus =
      req.body?.status === "PENDING_APPROVAL" ? "PENDING_APPROVAL" : "SUSPENDED";
    if (!uid) return res.status(400).json({ error: "MISSING_FIREBASE_UID" });

    try {
      const updated = await db.transaction(async (tx) => {
        const r = await tx.execute({
          text: `UPDATE tenant_users
                 SET status = $2,
                     scopes = '',
                     role = 'PENDING_USER',
                     updated_at = now()
                 WHERE firebase_uid = $1
                 RETURNING firebase_uid, organization_id, status`,
          values: [uid, targetStatus]
        } as any);
        const row = r.rows[0] as any;
        if (!row) {
          throw Object.assign(new Error("TENANT_USER_NOT_FOUND"), { status: 404 });
        }
        await audit(
          tx,
          "TENANT_USER_REVOKED",
          req.principal?.subject || "admin",
          { firebaseUid: uid, status: targetStatus },
          row.organization_id || "system"
        );
        return row;
      });
      res.json({ ok: true, user: updated });
    } catch (e: any) {
      const s = Number.isInteger(e?.status) ? e.status : 500;
      res.status(s).json({ error: e?.message || "TENANT_REVOKE_FAILED" });
    }
  });

  app.get("/v1/wallets", requireScope("wallets:read"), async (req, res) => {
    const organization = req.principal?.organizationId;
    if (!organization) return res.status(403).json({ error: "TENANT_NOT_FOUND" });
    try {
      const r = await db.execute({
        text: "SELECT * FROM wallets WHERE organization=$1 ORDER BY created_at DESC",
        values: [organization]
      } as any);
      res.json({ wallets: r.rows });
    } catch {
      res.status(500).json({ error: "WALLETS_FETCH_FAILED" });
    }
  });

  app.post("/v1/wallets", requireScope("wallets:write"), async (req, res) => {
    const {
      name,
      monthlyLimit,
      perRequestLimit,
      initialBalance = "0",
      currency = "USD",
      allowedVendors = "*",
      allowedChains = "PAYU",
      requireApproval = false
    } = req.body ?? {};
    const organization = req.principal?.organizationId;
    if (!organization) return res.status(403).json({ error: "TENANT_NOT_FOUND" });
    if ([name, monthlyLimit, perRequestLimit].some((x) => typeof x !== "string" || !x.trim())) {
      return res.status(400).json({ error: "INVALID_REQUEST" });
    }
    try {
      const normalizedCurrency = normalizeCurrency(String(currency));
      const parsedMonthly = validAmount(monthlyLimit);
      const parsedPerReq = validAmount(perRequestLimit);
      const hasInitial = String(initialBalance).trim() !== "" && Number(initialBalance) > 0;
      const parsedInitial = hasInitial ? validAmount(String(initialBalance)) : "0";

      const created = await db.transaction(async (tx) => {
        const did = `did:agent:${organization.replace(/[^a-zA-Z0-9]/g, "_").toLowerCase()}:${Date.now()}`;
        const r = await tx.execute({
          text: `INSERT INTO wallets(name, organization, balance, currency, monthly_limit, per_request_limit, allowed_vendors, allowed_chains, require_approval, did_identity)
                 VALUES($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                 RETURNING *`,
          values: [
            name.trim(),
            organization,
            parsedInitial,
            normalizedCurrency,
            parsedMonthly,
            parsedPerReq,
            String(allowedVendors || "*"),
            String(allowedChains || "PAYU"),
            Boolean(requireApproval),
            did
          ]
        } as any);
        const w = r.rows[0] as any;

        if (hasInitial) {
          const dr = `asset:${normalizedCurrency.toLowerCase()}:wallet:${w.id}`;
          const cr = `equity:funding:initial_deposit`;
          await tx.execute({
            text: "INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET',$3) ON CONFLICT DO NOTHING",
            values: [dr, w.id, normalizedCurrency]
          } as any);
          await tx.execute({
            text: "INSERT INTO accounts(id,account_type,currency) VALUES($1,'EQUITY',$2) ON CONFLICT DO NOTHING",
            values: [cr, normalizedCurrency]
          } as any);
          const e = (
            await tx.execute({
              text: `INSERT INTO journal_entries(organization_id, idempotency_key, wallet_id, reference, protocol, status)
                     VALUES($1, $2, $3, 'funding:initial', 'TREASURY_FUNDING', 'CONFIRMED')
                     RETURNING id`,
              values: [organization, `init_${w.id}`, w.id]
            } as any)
          ).rows[0] as any;
          await tx.execute({
            text: `INSERT INTO journal_lines(journal_entry_id, account_id, side, amount, currency)
                   VALUES($1, $2, 'DR', $3, $4), ($1, $5, 'CR', $3, $4)`,
            values: [e.id, dr, parsedInitial, normalizedCurrency, cr]
          } as any);
        }

        await audit(tx, "WALLET_CREATED", req.principal?.subject || "api", w, organization);
        return w;
      });

      res.status(201).json(created);
    } catch (e: any) {
      const s = Number.isInteger(e?.status) ? e.status : 500;
      res.status(s).json({ error: e?.message || "WALLET_CREATE_FAILED" });
    }
  });

  app.get("/v1/wallets/:id", requireScope("wallets:read"), async (req, res) => {
    const organization = req.principal?.organizationId;
    const walletId = String(req.params.id);
    const r = await db.execute({
      text: "SELECT * FROM wallets WHERE id=$1 AND organization=$2",
      values: [walletId, organization]
    } as any);
    if (!r.rows[0]) return res.status(404).json({ error: "NOT_FOUND" });
    res.json(r.rows[0]);
  });

  app.post("/v1/wallets/:id/fund", requireScope("funding:write"), async (req, res) => {
    const organization = req.principal?.organizationId;
    const key = req.header("idempotency-key");
    const walletId = String(req.params.id);
    const { amount, currency, source = "treasury_deposit" } = req.body ?? {};
    if (!organization) return res.status(403).json({ error: "TENANT_NOT_FOUND" });
    if (!key || typeof amount !== "string") {
      return res.status(400).json({ error: "amount and Idempotency-Key are required" });
    }

    try {
      const normalizedCurrency =
        typeof currency === "string" && currency.trim() ? normalizeCurrency(currency) : undefined;
      const result = await depositToWallet({
        tenantId: organization,
        walletId,
        amount,
        currency: normalizedCurrency,
        source,
        idempotencyKey: key
      });
      res.status(201).json(result);
    } catch (e: any) {
      if (e?.existingResponse && e?.existingStatus && e.existingStatus !== 202) {
        return res.status(Number(e.existingStatus)).json(JSON.parse(e.existingResponse));
      }
      const s = Number.isInteger(e?.status) ? e.status : 500;
      res.status(s).json({ status: "ERROR", error: e?.message ?? "Wallet funding failed" });
    }
  });

  app.post("/v1/payments/authorize", requireScope("payments:authorize"), async (req, res) => {
    const {
      walletId,
      amount,
      currency = "USD",
      vendor,
      protocol = "HTTP_402",
      provider = "PAYU"
    } = req.body ?? {};
    const key = req.header("idempotency-key");
    const organization = req.principal?.organizationId;
    if (!key || typeof walletId !== "string" || typeof amount !== "string" || typeof vendor !== "string") {
      return res.status(400).json({ error: "walletId, amount, vendor and Idempotency-Key are required" });
    }
    if (!organization) return res.status(403).json({ error: "TENANT_NOT_FOUND" });

    const normalizedProvider = String(provider).trim().toUpperCase();
    if (DISABLED_PROVIDERS.has(normalizedProvider)) {
      return res.status(400).json({
        error: "PROVIDER_DISABLED",
        message: `Blockchain settlement provider ${normalizedProvider} is disabled until real transaction submission and confirmation verification are implemented.`
      });
    }
    if (!SUPPORTED_PROVIDERS.has(normalizedProvider)) {
      return res.status(400).json({
        error: "UNSUPPORTED_PROVIDER",
        message: `Unsupported settlement provider: ${normalizedProvider}`
      });
    }

    try {
      const normalizedCurrency = normalizeCurrency(String(currency));
      const allowedForProvider = PROVIDER_CURRENCIES[normalizedProvider];
      if (allowedForProvider && !allowedForProvider.has(normalizedCurrency)) {
        return res.status(400).json({
          error: "UNSUPPORTED_PROVIDER_CURRENCY",
          message: `${normalizedProvider} supports ${Array.from(allowedForProvider).join(", ")}, not ${normalizedCurrency}`
        });
      }

      const normalizedAmount = validAmount(amount);
      const reqHash = hashRequest({
        tenantId: organization,
        walletId,
        amount: normalizedAmount,
        currency: normalizedCurrency,
        vendor,
        protocol,
        provider: normalizedProvider
      });

      const existing = await db.execute({
        text: `SELECT request_hash, response_status, response_body
               FROM idempotency_keys
               WHERE organization_id = $1 AND key = $2 AND response_status != 202`,
        values: [organization, key]
      } as any);
      if (existing.rows[0]) {
        const row = existing.rows[0] as any;
        if (row.request_hash !== reqHash) {
          return res.status(409).json({
            status: "ERROR",
            error: "Idempotency key reused with different request payload"
          });
        }
        return res.status(Number(row.response_status)).json(JSON.parse(row.response_body));
      }

      const { responseBody } = await postPayment({
        tenantId: organization,
        walletId,
        amount: normalizedAmount,
        currency: normalizedCurrency,
        vendor,
        protocol,
        provider: normalizedProvider,
        idempotencyKey: key
      });

      res.status(201).json(responseBody);
    } catch (e: any) {
      if (e?.existingResponse && e?.existingStatus && e.existingStatus !== 202) {
        return res.status(Number(e.existingStatus)).json(JSON.parse(e.existingResponse));
      }
      const s = Number.isInteger(e?.status) ? e.status : 500;
      res.status(s).json({
        status: s === 403 ? "DENIED" : "ERROR",
        error: e?.message ?? "Payment failed"
      });
    }
  });

  app.get("/v1/transactions", requireScope("transactions:read"), async (req, res) => {
    const organization = req.principal?.organizationId;
    if (!organization) return res.status(403).json({ error: "TENANT_NOT_FOUND" });
    try {
      const r = await db.execute({
        text: `SELECT
                 je.id,
                 je.wallet_id,
                 w.name AS wallet_name,
                 je.reference,
                 je.protocol,
                 je.status AS ledger_status,
                 je.reversal_of,
                 je.created_at,
                 si.id AS settlement_intent_id,
                 si.provider,
                 si.provider_ref,
                 si.status AS settlement_status,
                 COALESCE(si.amount, MAX(jl.amount)) AS amount,
                 COALESCE(si.currency, MAX(jl.currency)) AS currency,
                 MAX(CASE WHEN jl.side = 'DR' THEN jl.account_id END) AS debit_account_id,
                 MAX(CASE WHEN jl.side = 'CR' THEN jl.account_id END) AS credit_account_id
               FROM journal_entries je
               JOIN wallets w ON w.id = je.wallet_id
               LEFT JOIN settlement_intents si ON si.journal_entry_id = je.id
               LEFT JOIN journal_lines jl ON jl.journal_entry_id = je.id
               WHERE je.organization_id = $1
               GROUP BY je.id, w.name, si.id
               ORDER BY je.created_at DESC
               LIMIT 100`,
        values: [organization]
      } as any);
      res.json({ transactions: r.rows });
    } catch {
      res.status(500).json({ error: "TRANSACTIONS_FETCH_FAILED" });
    }
  });

  app.get("/v1/audit-events", requireScope("audit:read"), async (req, res) => {
    const organization = req.principal?.organizationId;
    if (!organization) return res.status(403).json({ error: "TENANT_NOT_FOUND" });
    try {
      const r = await db.execute({
        text: `SELECT id, seq, event_type, actor, payload, previous_hash, event_hash, created_at
               FROM audit_events
               WHERE organization_id = $1
               ORDER BY seq DESC
               LIMIT 100`,
        values: [organization]
      } as any);
      res.json({ auditEvents: r.rows });
    } catch {
      res.status(500).json({ error: "AUDIT_FETCH_FAILED" });
    }
  });

  app.post("/v1/settlements/reconcile", requireScope("reconcile:write"), async (_req, res) => {
    try {
      const summary = await SettlementWorker.reconcileSubmittedIntents(0);
      res.json({ ok: true, ...summary });
    } catch (e: any) {
      res.status(500).json({ ok: false, error: e?.message || "RECONCILE_FAILED" });
    }
  });

  app.post("/v1/ai/gemini", requireScope("ai:invoke"), async (req, res) => {
    if (!config.GEMINI_API_KEY) return res.status(503).json({ error: "GEMINI_NOT_CONFIGURED" });
    const p = req.body?.prompt;
    if (typeof p !== "string" || !p.length || p.length > 20000) {
      return res.status(400).json({ error: "INVALID_PROMPT" });
    }
    try {
      const r = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(config.GEMINI_MODEL)}:generateContent`,
        {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-goog-api-key": config.GEMINI_API_KEY
          },
          body: JSON.stringify({ contents: [{ parts: [{ text: p }] }] })
        }
      );
      const b = await r.json();
      if (!r.ok) return res.status(502).json({ error: "GEMINI_UPSTREAM_ERROR" });
      res.json(b);
    } catch {
      res.status(502).json({ error: "GEMINI_UNAVAILABLE" });
    }
  });

  app.post("/v1/wallets/:id/fund/google-pay", requireScope("funding:write"), async (req, res) => {
    const organization = req.principal?.organizationId;
    const walletId = req.params.id;
    const { amount, token } = req.body ?? {};
    if (!amount || !token) return res.status(400).json({ error: "amount and token are required" });

    try {
      const w = await db.execute({
        text: "SELECT id FROM wallets WHERE id=$1 AND organization=$2",
        values: [walletId, organization]
      } as any);
      if (!w.rows[0]) throw Object.assign(new Error("Wallet not found in tenant"), { status: 404 });

      return res.status(501).json({ error: "NOT_IMPLEMENTED" });
    } catch (e: any) {
      const s = Number.isInteger(e?.status) ? e.status : 500;
      res.status(s).json({ status: "ERROR", error: e?.message ?? "Funding failed" });
    }
  });

  app.post("/v1/facilities", requireScope("facilities:write"), async (req, res) => {
    const organization = req.principal?.organizationId;
    if (!organization) return res.status(403).json({ error: "Tenant required" });
    const { facilityId, borrowerId, agentId, commitment } = req.body ?? {};
    if (!facilityId || !borrowerId || !agentId || !commitment) {
      return res.status(400).json({ error: "Missing required facility fields" });
    }

    try {
      const result = await createFacility(
        facilityId,
        organization,
        borrowerId,
        agentId,
        String(commitment)
      );
      res.status(201).json(result);
    } catch (e: any) {
      res.status(500).json({ error: e?.message ?? "Facility creation failed" });
    }
  });

  app.post("/v1/facilities/:id/draw", requireScope("facilities:draw"), async (req, res) => {
    const facilityId = String(req.params.id);
    const organization = req.principal?.organizationId;
    if (!organization) return res.status(403).json({ error: "Tenant required" });

    const { drawAmount, walletId } = req.body ?? {};
    const key = req.header("idempotency-key");

    if (!key || !drawAmount || !walletId) {
      return res.status(400).json({ error: "Missing required draw fields or idempotency-key" });
    }

    try {
      const result = await processDrawRequest(
        facilityId,
        organization,
        String(drawAmount),
        walletId,
        key,
        req.body
      );
      res.status(201).json(result);
    } catch (e: any) {
      if (e?.existingResponse && e?.existingStatus && e.existingStatus !== 202) {
        return res.status(Number(e.existingStatus)).json(JSON.parse(e.existingResponse));
      }
      const s = Number.isInteger(e?.status) ? e.status : 500;
      res.status(s).json({ status: "ERROR", error: e?.message ?? "Draw failed" });
    }
  });

  return app;
}

export const app = createApp();

if (config.NODE_ENV !== "test" && !process.env.VITEST) {
  await migrate();
  await seedCredentials();
  SettlementWorker.start();
  app.listen(config.PORT, () => console.log(`Agent Wallet Backend listening on ${config.PORT}`));
}
