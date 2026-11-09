import { db } from "./db.js";
import { gateway } from "./settlement/index.js";
import { Capture } from "./settlement/Request.js";
import { revertPayment } from "./ledger.js";

export class SettlementWorker {
  private static interval: NodeJS.Timeout | null = null;
  private static isRunning = false;

  static start(pollIntervalMs = 5000) {
    if (this.interval) return;
    console.log("[SettlementWorker] Starting background settlement outbox worker...");
    this.interval = setInterval(async () => {
      if (this.isRunning) return;
      this.isRunning = true;
      try {
        await this.processPendingIntents();
      } catch (err) {
        console.error("[SettlementWorker] Error processing pending settlement intents:", err);
      } finally {
        this.isRunning = false;
      }
    }, pollIntervalMs);
  }

  static stop() {
    if (this.interval) {
      clearInterval(this.interval);
      this.interval = null;
    }
  }

  static async processPendingIntents() {
    const res = await db.execute({
      text: `SELECT id, journal_entry_id, provider, amount, currency 
             FROM settlement_intents 
             WHERE status = 'PENDING' 
             LIMIT 10`
    } as any);

    const intents = res.rows as any[];
    if (intents.length === 0) return;

    for (const intent of intents) {
      try {
        console.log(`[SettlementWorker] Processing settlement intent ${intent.id} via ${intent.provider}`);
        
        // Mark as processing/submitted
        await db.execute({
          text: `UPDATE settlement_intents SET status = 'PROCESSING' WHERE id = $1 AND status = 'PENDING'`,
          values: [intent.id]
        } as any);

        const gRes = await gateway.execute(intent.provider, new Capture({
          intentId: intent.id,
          amount: intent.amount,
          currency: intent.currency,
          targetAddress: intent.provider
        }));

        const providerRef = (gRes as any)?.providerRef;
        
        await db.transaction(async (tx) => {
          await tx.execute({
            text: `UPDATE settlement_intents SET status = 'SETTLED', provider_ref = $1 WHERE id = $2`,
            values: [providerRef || `ref_${intent.id.substring(0,8)}`, intent.id]
          } as any);

          await tx.execute({
            text: `UPDATE journal_entries SET status = 'SETTLED' WHERE id = $1`,
            values: [intent.journal_entry_id]
          } as any);
        });

        console.log(`[SettlementWorker] Successfully settled intent ${intent.id}`);
      } catch (err: any) {
        console.error(`[SettlementWorker] Failed settlement intent ${intent.id}:`, err.message);
        
        await db.transaction(async (tx) => {
          await tx.execute({
            text: `UPDATE settlement_intents SET status = 'FAILED' WHERE id = $1`,
            values: [intent.id]
          } as any);
        });

        // Trigger compensation / reversal
        try {
          await revertPayment(intent.journal_entry_id);
        } catch (revErr) {
          console.error(`[SettlementWorker] Reversal failed for entry ${intent.journal_entry_id}:`, revErr);
        }
      }
    }
  }
}
