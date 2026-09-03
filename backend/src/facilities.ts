import { db } from "./db.js";
import { audit } from "./audit.js";
import { TreasuryPolicyEngine } from "./treasury/tfx.js";
import { CARSAdapter } from "./treasury/cars.js";
import { validAmount, hashRequest } from "./utils.js";

export async function createFacility(facilityId: string, tenantId: string, borrowerId: string, agentId: string, commitmentStr: string) {
  const commitment = validAmount(commitmentStr);
  return db.transaction(async (tx) => {
    await tx.execute({
      text: "INSERT INTO facilities (id, organization_id, borrower_id, agent_id, commitment) VALUES ($1, $2, $3, $4, $5)",
      values: [facilityId, tenantId, borrowerId, agentId, commitment]
    } as any);
    
    return { facilityId, commitment, drawn: '0' };
  });
}

export async function processDrawRequest(facilityId: string, tenantId: string, drawAmountStr: string, walletId: string, idempotencyKey: string, requestBody: any) {
  const drawAmount = validAmount(drawAmountStr);
  const requestHash = hashRequest(requestBody);
  
  return db.transaction(async (tx) => {
    try {
      await tx.execute({text:"INSERT INTO idempotency_keys(key,request_hash,response_status,response_body) VALUES($1,$2,202,'Processing')",values:[idempotencyKey, requestHash]} as any);
    } catch(e:any) {
      if (e.code === '23505') {
        const existing = await tx.execute({text:"SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE key=$1",values:[idempotencyKey]} as any);
        if (existing.rows[0]) {
           if ((existing.rows[0] as any).request_hash !== requestHash) {
               throw Object.assign(new Error("Idempotency key reused with different request payload"),{status:409});
           }
           throw Object.assign(new Error("Idempotency key exists or is being processed"),{status:409, existingResponse: (existing.rows[0] as any).response_body, existingStatus: (existing.rows[0] as any).response_status});
        }
      }
      throw e;
    }

    const f = (await tx.execute({
      text: "SELECT * FROM facilities WHERE id = $1 AND organization_id = $2 FOR UPDATE",
      values: [facilityId, tenantId]
    } as any)).rows[0] as any;
    
    if (!f) throw Object.assign(new Error("Facility not found"), { status: 404 });
    if (f.status !== 'ACTIVE') throw Object.assign(new Error("Facility is not active"), { status: 409 });
    
    const classification = TreasuryPolicyEngine.evaluate('FACILITY_DRAW', drawAmount);

    const borrowerSettlementAcc = `asset:usd:wallet:${walletId}`;
    const lenderSettlementAcc = `liability:usd:general_fund`;

    await tx.execute({text:"INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET','USD') ON CONFLICT DO NOTHING",values:[borrowerSettlementAcc,walletId]} as any);
    await tx.execute({text:"INSERT INTO accounts(id,account_type,currency) VALUES($1,'LIABILITY','USD') ON CONFLICT DO NOTHING",values:[lenderSettlementAcc]} as any);

    const e = (await tx.execute({
      text: "INSERT INTO journal_entries(idempotency_key,wallet_id,reference,protocol,status) VALUES($1,$2,$3,'FACILITY_DRAW','SETTLEMENT_SUBMITTED') RETURNING id",
      values: [idempotencyKey, walletId, `draw:${facilityId}`]
    } as any)).rows[0] as any;
    
    await tx.execute({
      text: "INSERT INTO journal_lines(journal_entry_id,account_id,side,amount,currency) VALUES($1,$2,'DR',$3,'USD'),($1,$4,'CR',$3,'USD')",
      values: [e.id, borrowerSettlementAcc, drawAmount, lenderSettlementAcc]
    } as any);

    const facilityUpdate = await tx.execute({
      text: "UPDATE facilities SET drawn = drawn + $1::numeric WHERE id = $2 AND commitment - drawn >= $1::numeric RETURNING id",
      values: [drawAmount, facilityId]
    } as any);

    if (facilityUpdate.rows.length === 0) {
      throw Object.assign(new Error(`Credit risk check failed: draw exceeds available facility limit`), { status: 403 });
    }

    await CARSAdapter.queueForReporting(tx, e.id, classification);

    const auditHash = await audit(tx, "FACILITY_DRAW_SUBMITTED", `facility:${facilityId}`, {
      entryId: e.id,
      amount: drawAmount,
      facilityId,
      borrowerSettlementAcc
    });

    const responseBody = { status: "SETTLEMENT_SUBMITTED", journalEntryId: e.id, auditHash, tas: classification.tas };
    await tx.execute({text:"UPDATE idempotency_keys SET response_status=201, response_body=$2 WHERE key=$1",values:[idempotencyKey,JSON.stringify(responseBody)]} as any);
    
    return responseBody;
  });
}
