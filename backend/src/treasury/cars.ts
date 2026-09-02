import { db } from "../db.js";
import { TreasuryClassification } from "./tfx.js";

/**
 * CARS Adapter (Central Accounting Reporting System)
 * Acts as the boundary between the internal Systemaccounting ledger
 * and the external U.S. Treasury settlement infrastructure.
 */
export class CARSAdapter {
  static async queueForReporting(tx: any, transactionId: string, classification: TreasuryClassification) {
    // Inserts into the CARS outbox atomically within the same ledger transaction
    await tx.execute({
      text: `INSERT INTO cars_outbox (transaction_id, tas, betc, alc, ussgl, cars_status)
             VALUES ($1, $2, $3, $4, $5, 'PENDING')`,
      values: [
        transactionId,
        classification.tas,
        classification.betc,
        classification.alc,
        classification.ussgl
      ]
    });
  }

  static async pollAndReport() {
    // In a production system, this would batch process PENDING outbox items
    // and submit them to the Treasury CAFÉ interface via SFTP/API.
    // For now, we mock the transition.
    await db.execute({
      text: `UPDATE cars_outbox 
             SET cars_status = 'REPORTED', reported_at = NOW() 
             WHERE cars_status = 'PENDING'`
    } as any);
  }
}
