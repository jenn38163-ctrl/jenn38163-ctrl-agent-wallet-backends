import {db} from "./db.js";

function validAmount(a:string){
  if(!/^\d+(\.\d{1,6})?$/.test(a)||Number(a)<=0)throw Object.assign(new Error("Invalid positive decimal amount"),{status:400});
  return a;
}

export async function postPayment(i:{walletId:string,amount:string,vendor:string,protocol:string,idempotencyKey:string}){
  const amount=validAmount(i.amount);
  return db.transaction(async(tx)=>{
    try {
      await tx.execute({text:"INSERT INTO idempotency_keys(key,response_status,response_body) VALUES($1,202,'Processing')",values:[i.idempotencyKey]} as any);
    } catch(e:any) {
      if (e.code === '23505') throw Object.assign(new Error("Idempotency key exists or is being processed"),{status:409});
      throw e;
    }
    
    // Exact PostgreSQL Decimal Math for Limits
    const w=(await tx.execute({
      text:"SELECT * FROM wallets WHERE id=$1 AND monthly_spent + $2::numeric <= monthly_limit AND $2::numeric <= per_request_limit FOR UPDATE",
      values:[i.walletId, amount]
    } as any)).rows[0] as any;
    
    if(!w) {
      // Differentiate between wallet not existing vs limits exceeded
      const existing = await tx.execute({text:"SELECT status FROM wallets WHERE id=$1",values:[i.walletId]} as any);
      if (!existing.rows[0]) throw Object.assign(new Error("Wallet not found"),{status:404});
      if ((existing.rows[0] as any).status !== "ACTIVE") throw Object.assign(new Error("Wallet inactive"),{status:409});
      throw Object.assign(new Error("Limits exceeded"),{status:403});
    }

    const dr=`asset:usdc:wallet:${i.walletId}`,cr=`liability:vendor:${i.vendor.toLowerCase()}`;
    await tx.execute({text:"INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET','USDC') ON CONFLICT DO NOTHING",values:[dr,i.walletId]} as any);
    await tx.execute({text:"INSERT INTO accounts(id,account_type,currency) VALUES($1,'LIABILITY','USDC') ON CONFLICT DO NOTHING",values:[cr]} as any);
    
    const e=(await tx.execute({
      text:"INSERT INTO journal_entries(idempotency_key,wallet_id,reference,protocol,status) VALUES($1,$2,$3,$4,'LEDGER_POSTED') RETURNING id",
      values:[i.idempotencyKey,i.walletId,`payment:${i.vendor}`,i.protocol]
    } as any)).rows[0] as any;
    
    await tx.execute({
      text:"INSERT INTO journal_lines(journal_entry_id,account_id,side,amount,currency) VALUES($1,$2,'CR',$3,'USDC'),($1,$4,'DR',$3,'USDC')",
      values:[e.id,dr,amount,cr]
    } as any);
    
    await tx.execute({
      text:"UPDATE wallets SET monthly_spent=monthly_spent+$1::numeric WHERE id=$2",
      values:[amount,i.walletId]
    } as any);
    
    return e;
  });
}
