import pg from "pg";
import { drizzle } from "drizzle-orm/node-postgres";
import { config } from "./config.js";

export function resolveSslConfig(
  databaseUrl: string = config.DATABASE_URL,
  databaseSsl: "true" | "false" | undefined = config.DATABASE_SSL,
  rejectUnauthorized: "true" | "false" = config.DATABASE_SSL_REJECT_UNAUTHORIZED
): pg.PoolConfig["ssl"] {
  if (databaseSsl === "false") {
    return false;
  }
  const requiresSsl =
    databaseSsl === "true" ||
    /[?&]sslmode=(require|verify-ca|verify-full)/i.test(databaseUrl);
  if (!requiresSsl) {
    return undefined;
  }
  return {
    rejectUnauthorized: rejectUnauthorized !== "false"
  };
}

export const pool = new pg.Pool({
  connectionString: config.DATABASE_URL,
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 10_000,
  ssl: resolveSslConfig()
});

pool.on("error", (err) => {
  console.error("[pg.Pool] Unexpected idle client error:", err?.message || err);
});

export const db = drizzle(pool);