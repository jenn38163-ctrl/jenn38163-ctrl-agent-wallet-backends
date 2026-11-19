import type { Request, Response, NextFunction } from "express";
import { createHash, createVerify } from "node:crypto";
import { db } from "./db.js";
import { config } from "./config.js";

const FIREBASE_CERTS_URL =
  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

export const ASSIGNABLE_ROLES = new Set(["VIEWER", "OPERATOR", "TENANT_ADMIN"]);

export const ASSIGNABLE_SCOPES = new Set([
  "wallets:read",
  "wallets:write",
  "payments:authorize",
  "funding:write",
  "facilities:write",
  "facilities:draw",
  "transactions:read",
  "audit:read",
  "reconcile:write",
  "ai:invoke",
  "tenants:approve"
]);

export type ProvisioningStatus = "PENDING_APPROVAL" | "ACTIVE" | "SUSPENDED";

export interface Principal {
  organizationId: string;
  role: string;
  scopes: string[];
  subject: string;
  authType: "firebase_jwt" | "api_key";
  provisioningStatus: ProvisioningStatus;
  email?: string;
}

declare module "express-serve-static-core" {
  interface Request {
    principal?: Principal;
  }
}

interface CertCache {
  certs: Record<string, string>;
  expiresAtMs: number;
}

let certCache: CertCache | null = null;

export type CertFetcher = () => Promise<{ certs: Record<string, string>; maxAgeSeconds: number }>;

async function fetchGooglePublicCerts(): Promise<{
  certs: Record<string, string>;
  maxAgeSeconds: number;
}> {
  const res = await fetch(FIREBASE_CERTS_URL);
  if (!res.ok) {
    throw new Error(`Failed to fetch Firebase public certificates: HTTP ${res.status}`);
  }
  const cacheControl = res.headers.get("cache-control") ?? "";
  const maxAgeMatch = cacheControl.match(/max-age=(\d+)/i);
  const maxAgeSeconds = maxAgeMatch ? Number(maxAgeMatch[1]) : 3600;
  const certs = (await res.json()) as Record<string, string>;
  return { certs, maxAgeSeconds };
}

async function getFirebaseCert(kid: string, fetcher: CertFetcher = fetchGooglePublicCerts): Promise<string> {
  const now = Date.now();
  if (!certCache || now >= certCache.expiresAtMs || !certCache.certs[kid]) {
    const fetched = await fetcher();
    certCache = {
      certs: fetched.certs,
      expiresAtMs: now + Math.max(fetched.maxAgeSeconds, 60) * 1000
    };
  }
  const pem = certCache.certs[kid];
  if (!pem) {
    throw new Error("FIREBASE_CERT_NOT_FOUND");
  }
  return pem;
}

export function clearFirebaseCertCacheForTests() {
  certCache = null;
}

function decodeBase64UrlJson(segment: string): any {
  const normalized = segment.replace(/-/g, "+").replace(/_/g, "/");
  const pad = normalized.length % 4 === 0 ? "" : "=".repeat(4 - (normalized.length % 4));
  const raw = Buffer.from(normalized + pad, "base64").toString("utf8");
  return JSON.parse(raw);
}

function decodeBase64UrlBuffer(segment: string): Buffer {
  const normalized = segment.replace(/-/g, "+").replace(/_/g, "/");
  const pad = normalized.length % 4 === 0 ? "" : "=".repeat(4 - (normalized.length % 4));
  return Buffer.from(normalized + pad, "base64");
}

export interface VerifiedFirebaseClaims {
  sub: string;
  email?: string;
  emailVerified: boolean;
  aud: string;
  iss: string;
  exp: number;
  iat: number;
}

export async function verifyFirebaseIdToken(
  token: string,
  options?: {
    projectId?: string;
    nowSeconds?: number;
    certFetcher?: CertFetcher;
  }
): Promise<VerifiedFirebaseClaims> {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw Object.assign(new Error("INVALID_JWT_FORMAT"), { status: 401 });
  }

  const [headerB64, payloadB64, signatureB64] = parts;
  let header: any;
  let payload: any;
  try {
    header = decodeBase64UrlJson(headerB64);
    payload = decodeBase64UrlJson(payloadB64);
  } catch {
    throw Object.assign(new Error("MALFORMED_JWT"), { status: 401 });
  }

  if (header?.alg !== "RS256" || typeof header?.kid !== "string" || !header.kid) {
    throw Object.assign(new Error("INVALID_JWT_HEADER"), { status: 401 });
  }

  const projectId = options?.projectId ?? config.FIREBASE_PROJECT_ID;
  const expectedIssuer = `https://securetoken.google.com/${projectId}`;
  const nowSec = options?.nowSeconds ?? Math.floor(Date.now() / 1000);

  if (payload?.aud !== projectId) {
    throw Object.assign(new Error("INVALID_TOKEN_AUDIENCE"), { status: 401 });
  }
  if (payload?.iss !== expectedIssuer) {
    throw Object.assign(new Error("INVALID_TOKEN_ISSUER"), { status: 401 });
  }
  if (typeof payload?.sub !== "string" || !payload.sub || payload.sub.length > 128) {
    throw Object.assign(new Error("INVALID_TOKEN_SUBJECT"), { status: 401 });
  }
  if (typeof payload?.exp !== "number" || payload.exp <= nowSec) {
    throw Object.assign(new Error("TOKEN_EXPIRED"), { status: 401 });
  }
  if (typeof payload?.iat !== "number" || payload.iat > nowSec + 60) {
    throw Object.assign(new Error("INVALID_TOKEN_IAT"), { status: 401 });
  }
  if (typeof payload?.auth_time === "number" && payload.auth_time > nowSec + 60) {
    throw Object.assign(new Error("INVALID_AUTH_TIME"), { status: 401 });
  }

  const certPem = await getFirebaseCert(header.kid, options?.certFetcher);
  const verifier = createVerify("RSA-SHA256");
  verifier.update(`${headerB64}.${payloadB64}`);
  verifier.end();

  const signatureBytes = decodeBase64UrlBuffer(signatureB64);
  if (!verifier.verify(certPem, signatureBytes)) {
    throw Object.assign(new Error("INVALID_TOKEN_SIGNATURE"), { status: 401 });
  }

  return {
    sub: payload.sub,
    email: typeof payload.email === "string" ? payload.email : undefined,
    emailVerified: Boolean(payload.email_verified),
    aud: payload.aud,
    iss: payload.iss,
    exp: payload.exp,
    iat: payload.iat
  };
}

/**
 * Registers or loads a Firebase user without granting administrative roles or scopes.
 * New accounts start in PENDING_APPROVAL with zero scopes until an authorized operator
 * approves their organization_id, role, and scopes.
 */
export async function resolveOrRegisterFirebaseUser(
  tx: any,
  claims: VerifiedFirebaseClaims
): Promise<Principal> {
  const userRes = await tx.execute({
    text: `INSERT INTO tenant_users(firebase_uid, email, organization_id, role, scopes, status)
           VALUES($1, $2, 'unassigned', 'PENDING_USER', '', 'PENDING_APPROVAL')
           ON CONFLICT (firebase_uid) DO UPDATE
           SET email = COALESCE(EXCLUDED.email, tenant_users.email),
               updated_at = now()
           RETURNING organization_id, role, scopes, status`,
    values: [claims.sub, claims.email ?? null]
  } as any);

  const userRow = userRes.rows[0] as any;
  const status: ProvisioningStatus =
    userRow?.status === "ACTIVE"
      ? "ACTIVE"
      : userRow?.status === "SUSPENDED"
        ? "SUSPENDED"
        : "PENDING_APPROVAL";

  const activeScopes =
    status === "ACTIVE" && userRow?.scopes
      ? String(userRow.scopes)
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean)
      : [];

  return {
    organizationId: status === "ACTIVE" ? String(userRow.organization_id) : "unassigned",
    role: String(userRow?.role || "PENDING_USER"),
    scopes: activeScopes,
    subject: claims.sub,
    authType: "firebase_jwt",
    provisioningStatus: status,
    email: claims.email
  };
}

function isLikelyJwt(token: string): boolean {
  const parts = token.split(".");
  return parts.length === 3 && parts.every((p) => p.length > 0);
}

export async function auth(req: Request, res: Response, next: NextFunction) {
  const h = req.header("authorization") ?? "";
  const token = h.startsWith("Bearer ") ? h.slice(7).trim() : "";

  if (!token) return res.status(401).json({ error: "UNAUTHORIZED" });

  try {
    if (isLikelyJwt(token)) {
      const claims = await verifyFirebaseIdToken(token);
      req.principal = await resolveOrRegisterFirebaseUser(db, claims);
      return next();
    }

    // Scoped API Key verification
    const tokenHash = createHash("sha256").update(token).digest("hex");
    const cred = (
      await db.execute({
        text: `SELECT id, organization_id, role, scopes
               FROM credentials
               WHERE token_hash = $1
                 AND status = 'ACTIVE'
                 AND revoked_at IS NULL
                 AND (expires_at IS NULL OR expires_at > now())`,
        values: [tokenHash]
      } as any)
    ).rows[0] as any;

    if (!cred) return res.status(401).json({ error: "INVALID_CREDENTIAL" });

    req.principal = {
      organizationId: cred.organization_id,
      role: cred.role,
      scopes: cred.scopes
        ? String(cred.scopes)
            .split(",")
            .map((s) => s.trim())
            .filter(Boolean)
        : [],
      subject: cred.id,
      authType: "api_key",
      provisioningStatus: "ACTIVE"
    };

    return next();
  } catch (e: any) {
    if (e?.status === 401) {
      return res.status(401).json({ error: e.message || "INVALID_TOKEN" });
    }
    console.error("[auth] Authentication error:", e?.message || e);
    return res.status(500).json({ error: "AUTH_ERROR" });
  }
}

export function requireScope(requiredScope: string) {
  return (req: Request, res: Response, next: NextFunction) => {
    if (!req.principal) {
      return res.status(401).json({ error: "UNAUTHORIZED" });
    }
    if (req.principal.provisioningStatus !== "ACTIVE") {
      return res.status(403).json({
        error:
          req.principal.provisioningStatus === "PENDING_APPROVAL"
            ? "TENANT_APPROVAL_REQUIRED"
            : "USER_ACCOUNT_INACTIVE",
        provisioningStatus: req.principal.provisioningStatus
      });
    }
    const scopes = req.principal.scopes;
    if (scopes.includes(requiredScope) || scopes.includes("admin:*")) {
      return next();
    }
    return res.status(403).json({ error: "FORBIDDEN", requiredScope });
  };
}
