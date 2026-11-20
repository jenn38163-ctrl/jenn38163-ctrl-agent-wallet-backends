import { sql } from "drizzle-orm";
import { config } from "./config.js";
import { db, pool } from "./db.js";
import { migrate, seedCredentials } from "./migrate.js";

async function checkSchemaStatus() {
  const versionRes = await db.execute(sql`SELECT version() AS version`);
  const tablesRes = await db.execute(sql`
    SELECT
      to_regclass('public.wallets') IS NOT NULL AS wallets,
      to_regclass('public.accounts') IS NOT NULL AS accounts,
      to_regclass('public.journal_entries') IS NOT NULL AS journal_entries,
      to_regclass('public.journal_lines') IS NOT NULL AS journal_lines,
      to_regclass('public.audit_events') IS NOT NULL AS audit_events,
      to_regclass('public.idempotency_keys') IS NOT NULL AS idempotency_keys,
      to_regclass('public.facilities') IS NOT NULL AS facilities,
      to_regclass('public.cars_outbox') IS NOT NULL AS cars_outbox,
      to_regclass('public.settlement_intents') IS NOT NULL AS settlement_intents,
      to_regclass('public.credentials') IS NOT NULL AS credentials,
      to_regclass('public.tenant_users') IS NOT NULL AS tenant_users,
      to_regclass('public.webhook_events') IS NOT NULL AS webhook_events
  `);
  const triggerRes = await db.execute(sql`
    SELECT count(*)::int AS trigger_count
    FROM pg_trigger
    WHERE NOT tgisinternal
      AND tgname IN (
        'trg_journal_lines_immutable',
        'trg_audit_events_immutable',
        'trg_journal_entries_no_delete',
        'trg_journal_entries_validate_update',
        'trg_enforce_double_entry_balance'
      )
  `);

  console.log(
    JSON.stringify(
      {
        connected: true,
        postgresVersion: (versionRes.rows[0] as any)?.version ?? null,
        tables: tablesRes.rows[0] ?? {},
        ledgerTriggersInstalled: (triggerRes.rows[0] as any)?.trigger_count ?? 0
      },
      null,
      2
    )
  );
}

async function main() {
  const readOnlyCheck = process.argv.includes("--check");
  const includeSeed =
    process.argv.includes("--seed") || config.SEED_CREDENTIALS_ON_BOOT === "true";
  try {
    if (readOnlyCheck) {
      await checkSchemaStatus();
      return;
    }

    console.log("[migrate] Applying idempotent PostgreSQL schema migrations...");
    await migrate();
    if (includeSeed) {
      console.log("[migrate] Seeding configured API credential hash...");
      await seedCredentials();
    } else {
      console.log(
        "[migrate] Skipping credential seeding (pass --seed or set SEED_CREDENTIALS_ON_BOOT=true to enable)."
      );
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
