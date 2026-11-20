import { config } from "./config.js";
import { pool } from "./db.js";
import { migrate, seedCredentials } from "./migrate.js";

async function main() {
  try {
    console.log("[migrate] Applying idempotent PostgreSQL schema migrations...");
    await migrate();
    if (config.SEED_CREDENTIALS_ON_BOOT === "true") {
      await seedCredentials();
    }
    console.log("[migrate] Completed successfully.");
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error("[migrate] Failed:", err);
  process.exit(1);
});
