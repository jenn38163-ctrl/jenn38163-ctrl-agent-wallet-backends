import "dotenv/config";
import { z } from "zod";

const s = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  PORT: z.coerce.number().int().positive().default(8080),
  DATABASE_URL: z.string().min(1),
  DATABASE_SSL: z.enum(["true", "false"]).optional(),
  DATABASE_SSL_REJECT_UNAUTHORIZED: z.enum(["true", "false"]).default("true"),
  AUTO_MIGRATE: z.enum(["true", "false"]).default("true"),
  SEED_CREDENTIALS_ON_BOOT: z.enum(["true", "false"]).default("true"),
  SEED_ORGANIZATION_ID: z.string().min(1).default("AI Corp Enterprises"),
  AGENT_WALLET_API_KEY: z.string().min(8).optional(),
  AGENT_WALLET_API_SCOPES: z
    .string()
    .default(
      "wallets:read,wallets:write,payments:authorize,facilities:write,facilities:draw,funding:write,audit:read,transactions:read,reconcile:write,tenants:approve"
    ),
  FIREBASE_PROJECT_ID: z.string().min(1).default("wallet-54645"),
  GEMINI_API_KEY: z.string().optional(),
  GEMINI_MODEL: z.string().default("gemini-3.7-flash"),
  CORS_ORIGIN: z.string().min(1),
  PUBLIC_BASE_URL: z.string().optional(),

  // PayU Configuration
  PAYU_BASE_URL: z.string().default("https://secure.payu.com"),
  PAYU_NOTIFY_URL: z.string().optional(),
  PAYU_MERCHANT_POS_ID: z.string().optional(),
  PAYU_CLIENT_ID: z.string().optional(),
  PAYU_CLIENT_SECRET: z.string().optional(),
  PAYU_SECOND_KEY: z.string().optional(),
  PAYU_RECONCILE_AGE_SECONDS: z.coerce.number().int().positive().default(60),

  // Settlement Outbox Worker Configuration
  SETTLEMENT_WORKER_ENABLED: z.enum(["true", "false"]).default("true"),
  SETTLEMENT_POLL_MS: z.coerce.number().int().positive().default(5000),
  SETTLEMENT_STALE_LOCK_SECONDS: z.coerce.number().int().positive().default(120),
  SETTLEMENT_MAX_RETRIES: z.coerce.number().int().positive().default(5),

  // Blockchain settlement remains disabled until real submission + confirmation is implemented
  BLOCKCHAIN_SETTLEMENT_ENABLED: z.enum(["true", "false"]).default("false"),
  PIMLICO_API_KEY: z.string().optional(),
  PIMLICO_CHAIN_ID: z.string().default("8453")
});

export const config = s.parse(process.env);
if (config.NODE_ENV === "production" && config.CORS_ORIGIN === "*") {
  throw new Error("CORS_ORIGIN cannot be * in production");
}
