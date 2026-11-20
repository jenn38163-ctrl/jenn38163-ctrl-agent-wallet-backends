import { hostname } from "node:os";
import { randomUUID } from "node:crypto";
import { db } from "./db.js";
import { config } from "./config.js";
import { audit } from "./audit.js";
import { gateway } from "./settlement/index.js";
import { Capture } from "./settlement/Request.js";
import { PayUSettlementAction, toPayUSmallestUnit } from "./settlement/actions/PayUSettlementAction.js";
import { revertPaymentTx } from "./ledger.js";

const WORKER_ID = `${hostname()}-${randomUUID().slice(0, 8)}`;

export class SettlementWorker {
  private static interval: NodeJS.Timeout | null = null;
  private static isRunning = false;
  private static currentTick: Promise<void> | null = null;
  private static loggedPendingSchema = false;

  static async runTick() {
    try {
      await this.processPendingIntents();
      await this.reconcileSubmittedIntents();
      this.loggedPendingSchema = false;
    } catch (err: any) {
      if (err?.code === "42P01" || err?.cause?.code === "42P01") {
        if (!this.loggedPendingSchema) {
          console.log(
            "[SettlementWorker] Waiting for database migrations before polling settlement_intents."
          );
          this.loggedPendingSchema = true;
        }
        return;
      }
      throw err;
    }
  }

  static start(pollIntervalMs = config.SETTLEMENT_POLL_MS) {
    if (this.interval || config.SETTLEMENT_WORKER_ENABLED !== "true") return;
    console.log(`[SettlementWorker] Starting background outbox worker (${WORKER_ID})...`);
    this.interval = setInterval(() => {
      if (this.isRunning) return;
      this.isRunning = true;
      this.currentTick = (async () => {
        try {
          await this.runTick();
        } catch (err) {
          console.error("[SettlementWorker] Error in worker loop:", err);
        } finally {
          this.isRunning = false;
          this.currentTick = null;
        }
      })();
    }, pollIntervalMs);
  }

  static async stop() {
    if (this.interval) {
      clearInterval(this.interval);
      this.interval = null;
    }
    if (this.currentTick) {
      await this.currentTick;
    }
  }

  /**
   * Atomically claims a batch of PENDING (or stale-locked PROCESSING) settlement intents
   * using PostgreSQL FOR UPDATE SKIP LOCKED so concurrent instances cannot double-dispatch.
   */
  static async claimIntents(limit = 10): Promise<any[]> {
    return db.transaction(async (tx) => {
      const res = await tx.execute({
        text: `WITH claimed AS (
                 SELECT id
                 FROM settlement_intents
                 WHERE (status = 'PENDING' AND next_retry_at <= now())
                    OR (status = 'PROCESSING' AND locked_at < now() - make_interval(secs => $1))
                 ORDER BY created_at ASC
                 LIMIT $2
                 FOR UPDATE SKIP LOCKED
               )
               UPDATE settlement_intents s
               SET status = 'PROCESSING',
                   locked_at = now(),
                   locked_by = $3,
                   attempt_count = s.attempt_count + 1,
                   updated_at = now()
               FROM claimed
               WHERE s.id = claimed.id
               RETURNING s.*`,
        values: [config.SETTLEMENT_STALE_LOCK_SECONDS, limit, WORKER_ID]
      } as any);

      return res.rows as any[];
    });
  }

  static async processPendingIntents() {
    const intents = await this.claimIntents(10);
    if (intents.length === 0) return;

    for (const intent of intents) {
      try {
        console.log(
          `[SettlementWorker] Dispatching intent ${intent.id} via ${intent.provider} (attempt ${intent.attempt_count})`
        );

        const gRes = await gateway.execute(
          intent.provider,
          new Capture({
            intentId: intent.id,
            amount: String(intent.amount),
            currency: intent.currency,
            targetAddress: intent.target_address || intent.provider
          })
        );

        const providerRef = gRes?.providerRef;
        if (!providerRef) {
          throw new Error(`Provider ${intent.provider} did not return a provider reference`);
        }

        const isAsyncProvider = gRes?.asyncConfirmation !== false;
        const nextIntentStatus = isAsyncProvider ? "SUBMITTED" : "CONFIRMED";
        const nextEntryStatus = isAsyncProvider ? "SUBMITTED" : "CONFIRMED";

        await db.transaction(async (tx) => {
          // Only update if still PROCESSING — if a PayU webhook already raced in and moved it
          // to CONFIRMED or FAILED, preserve that authoritative terminal transition.
          const updatedIntent = await tx.execute({
            text: `UPDATE settlement_intents
                   SET status = $1,
                       provider_ref = COALESCE(provider_ref, $2),
                       locked_at = NULL,
                       locked_by = NULL,
                       last_error = NULL,
                       updated_at = now()
                   WHERE id = $3 AND status = 'PROCESSING'
                   RETURNING id`,
            values: [nextIntentStatus, providerRef, intent.id]
          } as any);

          if (updatedIntent.rows.length > 0) {
            await tx.execute({
              text: `UPDATE journal_entries
                     SET status = $1
                     WHERE id = $2 AND status = 'LEDGER_POSTED'`,
              values: [nextEntryStatus, intent.journal_entry_id]
            } as any);

            await audit(
              tx,
              isAsyncProvider ? "SETTLEMENT_SUBMITTED" : "SETTLEMENT_CONFIRMED",
              `worker:${WORKER_ID}`,
              {
                intentId: intent.id,
                journalEntryId: intent.journal_entry_id,
                provider: intent.provider,
                providerRef
              },
              intent.organization_id || "system"
            );
          }
        });

        console.log(
          `[SettlementWorker] Intent ${intent.id} transitioned to ${nextIntentStatus} (providerRef=${providerRef})`
        );
      } catch (err: any) {
        console.error(`[SettlementWorker] Dispatch error for intent ${intent.id}:`, err?.message);
        const isPermanent =
          Boolean(err?.permanent) || Number(intent.attempt_count) >= config.SETTLEMENT_MAX_RETRIES;

        await db.transaction(async (tx) => {
          // Lock the intent row to ensure webhook didn't finalize it concurrently
          const current = (
            await tx.execute({
              text: `SELECT id, status, journal_entry_id, organization_id
                     FROM settlement_intents
                     WHERE id = $1
                     FOR UPDATE`,
              values: [intent.id]
            } as any)
          ).rows[0] as any;

          if (!current || current.status !== "PROCESSING") {
            return;
          }

          if (isPermanent) {
            await tx.execute({
              text: `UPDATE settlement_intents
                     SET status = 'FAILED',
                         locked_at = NULL,
                         locked_by = NULL,
                         last_error = $2,
                         updated_at = now()
                     WHERE id = $1`,
              values: [intent.id, String(err?.message || "Dispatch failed").slice(0, 1000)]
            } as any);

            await revertPaymentTx(
              tx,
              current.journal_entry_id,
              `DISPATCH_FAILED:${String(err?.message || "unknown").slice(0, 120)}`
            );
          } else {
            const backoffSeconds = Math.min(300, Math.pow(2, Number(intent.attempt_count)) * 5);
            await tx.execute({
              text: `UPDATE settlement_intents
                     SET status = 'PENDING',
                         locked_at = NULL,
                         locked_by = NULL,
                         next_retry_at = now() + make_interval(secs => $2),
                         last_error = $3,
                         updated_at = now()
                     WHERE id = $1`,
              values: [
                intent.id,
                backoffSeconds,
                String(err?.message || "Transient dispatch error").slice(0, 1000)
              ]
            } as any);
          }
        });
      }
    }
  }

  /**
   * Reconciles SUBMITTED PayU intents that have not yet received a webhook by querying
   * PayU's authoritative order retrieval API.
   */
  static async reconcileSubmittedIntents(
    minAgeSeconds = config.PAYU_RECONCILE_AGE_SECONDS
  ): Promise<{ checked: number; confirmed: number; failed: number }> {
    const payuAction = gateway.getAction("PAYU") as PayUSettlementAction | undefined;
    if (!payuAction || !config.PAYU_CLIENT_ID || !config.PAYU_CLIENT_SECRET) {
      return { checked: 0, confirmed: 0, failed: 0 };
    }

    const rows = (
      await db.execute({
        text: `SELECT id, journal_entry_id, organization_id, provider_ref, amount, currency
               FROM settlement_intents
               WHERE provider = 'PAYU'
                 AND status = 'SUBMITTED'
                 AND provider_ref IS NOT NULL
                 AND updated_at <= now() - make_interval(secs => $1)
               ORDER BY updated_at ASC
               LIMIT 10`,
        values: [minAgeSeconds]
      } as any)
    ).rows as any[];

    let confirmed = 0;
    let failed = 0;

    for (const row of rows) {
      try {
        const orderInfo = await payuAction.retrieveOrderStatus(row.provider_ref);
        if (orderInfo.status === "COMPLETED") {
          const expectedCurrency = String(row.currency || "").trim().toUpperCase();
          const actualCurrency = orderInfo.currencyCode
            ? orderInfo.currencyCode.trim().toUpperCase()
            : "";
          const currencyMismatch = Boolean(
            expectedCurrency && actualCurrency && expectedCurrency !== actualCurrency
          );

          let amountMismatch = false;
          let expectedMinor: string | undefined;
          if (row.amount !== undefined && row.amount !== null && orderInfo.totalAmount) {
            expectedMinor = toPayUSmallestUnit(String(row.amount));
            amountMismatch = expectedMinor !== String(orderInfo.totalAmount).trim();
          }

          if (currencyMismatch || amountMismatch) {
            await db.transaction(async (tx) => {
              await audit(
                tx,
                "PAYU_RECONCILE_MISMATCH",
                `reconciler:${WORKER_ID}`,
                {
                  intentId: row.id,
                  orderId: row.provider_ref,
                  expectedCurrency: expectedCurrency || null,
                  actualCurrency: actualCurrency || null,
                  expectedMinor: expectedMinor ?? null,
                  actualMinor: orderInfo.totalAmount ?? null
                },
                row.organization_id || "system"
              );
            });
            continue;
          }

          const didConfirm = await db.transaction(async (tx) => {
            const locked = (
              await tx.execute({
                text: `UPDATE settlement_intents
                       SET status = 'CONFIRMED', updated_at = now()
                       WHERE id = $1 AND status = 'SUBMITTED'
                       RETURNING id`,
                values: [row.id]
              } as any)
            ).rows[0];
            if (!locked) return false;

            await tx.execute({
              text: `UPDATE journal_entries
                     SET status = 'CONFIRMED'
                     WHERE id = $1 AND status IN ('LEDGER_POSTED', 'SUBMITTED')`,
              values: [row.journal_entry_id]
            } as any);

            await audit(
              tx,
              "PAYU_RECONCILED_CONFIRMED",
              `reconciler:${WORKER_ID}`,
              { intentId: row.id, orderId: row.provider_ref, payuStatus: orderInfo.status },
              row.organization_id || "system"
            );
            return true;
          });
          if (didConfirm) confirmed++;
        } else if (orderInfo.status === "CANCELED" || orderInfo.status === "REJECTED") {
          const didFail = await db.transaction(async (tx) => {
            const locked = (
              await tx.execute({
                text: `UPDATE settlement_intents
                       SET status = 'FAILED', last_error = $2, updated_at = now()
                       WHERE id = $1 AND status = 'SUBMITTED'
                       RETURNING id`,
                values: [row.id, `PayU status: ${orderInfo.status}`]
              } as any)
            ).rows[0];
            if (!locked) return false;

            await revertPaymentTx(tx, row.journal_entry_id, `PAYU_RECONCILE_${orderInfo.status}`);
            return true;
          });
          if (didFail) failed++;
        }
      } catch (err: any) {
        console.warn(`[SettlementWorker] Reconcile skipped for ${row.id}:`, err?.message);
      }
    }

    return { checked: rows.length, confirmed, failed };
  }
}
