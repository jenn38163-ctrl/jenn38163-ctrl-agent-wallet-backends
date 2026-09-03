import {db} from "./db.js";
import {validAmount, hashRequest} from "./utils.js";

export async function postPayment(i:{tenantId:string, walletId:string, amount:string, currency:string, vendor:string, protocol:string, provider:string, idempotencyKey:string}){
  const amount = validAmount(i.amount);
  const requestHash = hashRequest({ tenantId: i.tenantId, walletId: i.walletId, amount, currency: i.currency, vendor: i.vendor, protocol: i.protocol, provider: i.provider });
  
  return db.transaction(async(tx)=>{
    try {
      await tx.execute({text:"INSERT INTO idempotency_keys(key,request_hash,response_status,response_body) VALUES($1,$2,202,'Processing')",values:[i.idempotencyKey, requestHash]} as any);
    } catch(e:any) {
      if (e.code === '23505') {
        const existing = await tx.execute({text:"SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE key=$1",values:[i.idempotencyKey]} as any);
        if (existing.rows[0]) {
           if ((existing.rows[0] as any).request_hash !== requestHash) {
               throw Object.assign(new Error("Idempotency key reused with different request payload"),{status:409});
           }
           throw Object.assign(new Error("Idempotency key exists or is being processed"),{status:409, existingResponse: (existing.rows[0] as any).response_body, existingStatus: (existing.rows[0] as any).response_status});
        }
      }
      throw e;
    }
    
    // Exact PostgreSQL Decimal Math for Limits
    const w = (await tx.execute({
      text:"SELECT * FROM wallets WHERE id=$1 AND organization=$2 AND monthly_spent + $3::numeric <= monthly_limit AND $3::numeric <= per_request_limit FOR UPDATE",
      values:[i.walletId, i.tenantId, amount]
    } as any)).rows[0] as any;
    
    if(!w) {
      const existing = await tx.execute({text:"SELECT status FROM wallets WHERE id=$1 AND organization=$2",values:[i.walletId, i.tenantId]} as any);
      if (!existing.rows[0]) throw Object.assign(new Error("Wallet not found in tenant"),{status:404});
      if ((existing.rows[0] as any).status !== "ACTIVE") throw Object.assign(new Error("Wallet inactive"),{status:409});
      throw Object.assign(new Error("Limits exceeded"),{status:403});
    }

    const currency = i.currency;
    const dr=`asset:${currency.toLowerCase()}:wallet:${i.walletId}`;
    const cr=`liability:vendor:${i.vendor.toLowerCase()}`;

    await tx.execute({text:"INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET',$3) ON CONFLICT DO NOTHING",values:[dr,i.walletId, currency]} as any);
    await tx.execute({text:"INSERT INTO accounts(id,account_type,currency) VALUES($1,'LIABILITY',$2) ON CONFLICT DO NOTHING",values:[cr, currency]} as any);
    
    const e = (await tx.execute({
      text:"INSERT INTO journal_entries(idempotency_key,wallet_id,reference,protocol,status) VALUES($1,$2,$3,$4,'LEDGER_POSTED') RETURNING id",
      values:[i.idempotencyKey,i.walletId,`payment:${i.vendor}`,i.protocol]
    } as any)).rows[0] as any;
    
    await tx.execute({
      text:"INSERT INTO journal_lines(journal_entry_id,account_id,side,amount,currency) VALUES($1,$2,'CR',$3,$4),($1,$5,'DR',$3,$4)",
      values:[e.id,dr,amount,currency,cr]
    } as any);
    
    await tx.execute({
      text:"UPDATE wallets SET monthly_spent=monthly_spent+$1::numeric WHERE id=$2",
      values:[amount,i.walletId]
    } as any);

    // Create Settlement Intent in the same transaction
    const intentRes = await tx.execute({
      text: "INSERT INTO settlement_intents(journal_entry_id, provider, amount, currency, status) VALUES($1, $2, $3, $4, 'PENDING') RETURNING id",
      values: [e.id, i.provider, amount, currency]
    } as any);
    const intentId = (intentRes.rows[0] as any).id;
    
    return { entryId: e.id, intentId };
  });
}

export async function revertPayment(journalEntryId: string) {
  return db.transaction(async(tx) => {
    const e = (await tx.execute({
      text: "SELECT * FROM journal_entries WHERE id=$1 FOR UPDATE",
      values: [journalEntryId]
    } as any)).rows[0] as any;
    
    if(!e) return;
    
    try {
      // Create a new compensating entry
      const comp = (await tx.execute({
        text: "INSERT INTO journal_entries(idempotency_key, wallet_id, reference, protocol, status, reversal_of) VALUES($1, $2, $3, $4, 'REVERSED', $5) RETURNING id",
        values: [`rev_${e.idempotency_key}`, e.wallet_id, `reversal:${e.reference}`, e.protocol, e.id]
      } as any)).rows[0] as any;

      const lines = (await tx.execute({
        text: "SELECT * FROM journal_lines WHERE journal_entry_id=$1",
        values: [journalEntryId]
      } as any)).rows as any[];

      for (const line of lines) {
        const revSide = line.side === 'CR' ? 'DR' : 'CR';
        await tx.execute({
          text: "INSERT INTO journal_lines(journal_entry_id, account_id, side, amount, currency) VALUES($1, $2, $3, $4, $5)",
          values: [comp.id, line.account_id, revSide, line.amount, line.currency]
        } as any);
        
        if (line.side === 'CR' && line.account_id.startsWith(`asset:${line.currency.toLowerCase()}:wallet:`)) {
          // Reverse wallet limit
          await tx.execute({
            text: "UPDATE wallets SET monthly_spent=monthly_spent-$1::numeric WHERE id=$2",
            values: [line.amount, e.wallet_id]
          } as any);
        }
      }
      
      await tx.execute({
        text: "UPDATE journal_entries SET status='REVERSED' WHERE id=$1",
        values: [journalEntryId]
      } as any);
    } catch(err:any) {
       // Ignore duplicate key on unique constraint "reversal_of"
       if (err.code !== '23505') throw err;
    }
  });
}
