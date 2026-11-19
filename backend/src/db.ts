import pg from "pg";
import { drizzle } from "drizzle-orm/node-postgres";
import { config } from "./config.js";

function resolveSslConfig(): pg.PoolConfig["ssl"] {
  if (config.DATABASE_SSL === "false") {
    return false;
  }
  const requiresSsl =
    config.DATABASE_SSL === "true" ||
    /[?&]sslmode=(require|verify-ca|verify-full)/i.test(config.DATABASE_URL);
  if (!requiresSsl) {
    return undefined;
  }
  return {
    rejectUnauthorized: config.DATABASE_SSL_REJECT_UNAUTHORIZED !== "false"
  };
}

export const pool = new pg.Pool({
  connectionString: config.DATABASE_URL,
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 10_000,
  ssl: resolveSslConfig()
});

export const db = drizzle(pool);