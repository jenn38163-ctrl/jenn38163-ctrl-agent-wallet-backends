import { db } from "./db.js";
import { audit } from "./audit.js";
import { TreasuryPolicyEngine } from "./treasury/tfx.js";
import { CARSAdapter } from "./treasury/cars.js";
import { createHash } from "node:crypto";

export async function createFacility(facilityId: string, borrowerId: string, agentId: string, commitment: string) {
  // Step 1: $1M facility approved -> records commitment, does not create settlement balance
  return db.transaction(async (tx) => {
    await tx.execute({
      text: "INSERT INTO facilities (id, borrower_id, agent_id, commitment) VALUES ($1, $2, $3, $4)",
      values: [facilityId, borrowerId, agentId, commitment]
    } as any);
    
    return { facilityId, commitment, drawn: '0' };
  });
}

export async function processDrawRequest(facilityId: string, drawAmount: string, walletId: string, idempotencyKey: string) {
  // Step 2-7: Facility Loan Engine -> Authorization/Policy -> Systemaccounting Ledger -> Settlement
  return db.transaction(async (tx) => {
    // Prevent duplicates
    try {
      await tx.execute({text:"INSERT INTO idempotency_keys(key,response_status,response_body) VALUES($1,202,'Processing')",values:[idempotencyKey]} as any);
    } catch(e:any) {
      if (e.code === '23505') throw Object.assign(new Error("Idempotency key exists or is being processed"),{status:409});
      throw e;
    }

    // 1. Fetch Facility (CAF Agent evaluates the draw)
    const f = (await tx.execute({
      text: "SELECT * FROM facilities WHERE id = $1 FOR UPDATE",
      values: [facilityId]
    } as any)).rows[0] as any;
    
    if (!f) throw Object.assign(new Error("Facility not found"), { status: 404 });
    if (f.status !== 'ACTIVE') throw Object.assign(new Error("Facility is not active"), { status: 409 });
    
    const available = Number(f.commitment) - Number(f.drawn);
    if (Number(drawAmount) > available) {
      throw Object.assign(new Error(`Credit risk check failed: draw exceeds available facility limit of ${available}`), { status: 403 });
    }

    // 2. TFX Policy Engine Validation
    // This validates against U.S. Treasury rules before any money moves.
    const classification = TreasuryPolicyEngine.evaluate('FACILITY_DRAW', Number(drawAmount));

    // 3. Double-Entry Settlement Layer (Systemaccounting Live Ledger)
    const borrowerSettlementAcc = `asset:usd:wallet:${walletId}`;
    const lenderSettlementAcc = `liability:usd:general_fund`;

    await tx.execute({text:"INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET','USD') ON CONFLICT DO NOTHING",values:[borrowerSettlementAcc,walletId]} as any);
    await tx.execute({text:"INSERT INTO accounts(id,account_type,currency) VALUES($1,'LIABILITY','USD') ON CONFLICT DO NOTHING",values:[lenderSettlementAcc]} as any);

    // Atomically insert the ledger transaction
    const e = (await tx.execute({
      text: "INSERT INTO journal_entries(idempotency_key,wallet_id,reference,protocol,status) VALUES($1,$2,$3,'FACILITY_DRAW','SETTLED') RETURNING id",
      values: [idempotencyKey, walletId, `draw:${facilityId}`]
    } as any)).rows[0] as any;
    
    await tx.execute({
      text: "INSERT INTO journal_lines(journal_entry_id,account_id,side,amount,currency) VALUES($1,$2,'DR',$3,'USD'),($1,$4,'CR',$3,'USD')",
      values: [e.id, borrowerSettlementAcc, drawAmount, lenderSettlementAcc]
    } as any);

    // 4. Update Credit Ledger (Facility Drawn Amount)
    await tx.execute({
      text: "UPDATE facilities SET drawn = drawn + $1::numeric WHERE id = $2",
      values: [drawAmount, facilityId]
    } as any);

    // 5. Insert to CARS outbox for actual Treasury reporting
    await CARSAdapter.queueForReporting(tx, e.id, classification);

    // 6. Append Immutable Audit Block
    const auditHash = await audit("FACILITY_DRAW_SETTLED", `facility:${facilityId}`, {
      entryId: e.id,
      amount: drawAmount,
      facilityId,
      borrowerSettlementAcc
    });

    const responseBody = { status: "SETTLED", journalEntryId: e.id, auditHash, tas: classification.tas };
    await tx.execute({text:"UPDATE idempotency_keys SET response_status=201, response_body=$2 WHERE key=$1",values:[idempotencyKey,JSON.stringify(responseBody)]} as any);
    
    return responseBody;
  });
}
