import {db} from "./db.js";
function validAmount(a:string){if(!/^\d+(\.\d{1,6})?$/.test(a)||Number(a)<=0)throw Object.assign(new Error("Invalid positive decimal amount"),{status:400});return a}
export async function postPayment(i:{walletId:string,amount:string,vendor:string,protocol:string,idempotencyKey:string}){
const amount=validAmount(i.amount);
return db.transaction(async(tx)=>{
const w=(await tx.execute({text:"SELECT * FROM wallets WHERE id=$1 FOR UPDATE",values:[i.walletId]} as any)).rows[0] as any;
if(!w)throw Object.assign(new Error("Wallet not found"),{status:404});
if(w.status!=="ACTIVE")throw Object.assign(new Error("Wallet inactive"),{status:409});
const n=Number(amount);
if(n>Number(w.per_request_limit))throw Object.assign(new Error("Per-request limit exceeded"),{status:403});
if(Number(w.monthly_spent)+n>Number(w.monthly_limit))throw Object.assign(new Error("Monthly limit exceeded"),{status:403});
const dr=`asset:usdc:wallet:${i.walletId}`,cr=`liability:vendor:${i.vendor.toLowerCase()}`;
await tx.execute({text:"INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET','USDC') ON CONFLICT DO NOTHING",values:[dr,i.walletId]} as any);
await tx.execute({text:"INSERT INTO accounts(id,account_type,currency) VALUES($1,'LIABILITY','USDC') ON CONFLICT DO NOTHING",values:[cr]} as any);
const e=(await tx.execute({text:"INSERT INTO journal_entries(idempotency_key,wallet_id,reference,protocol,status) VALUES($1,$2,$3,$4,'POSTED') RETURNING id",values:[i.idempotencyKey,i.walletId,`payment:${i.vendor}`,i.protocol]} as any)).rows[0] as any;
await tx.execute({text:"INSERT INTO journal_lines(journal_entry_id,account_id,side,amount,currency) VALUES($1,$2,'CR',$3,'USDC'),($1,$4,'DR',$3,'USDC')",values:[e.id,dr,amount,cr]} as any);
await tx.execute({text:"UPDATE wallets SET monthly_spent=monthly_spent+$1 WHERE id=$2",values:[amount,i.walletId]} as any);
return e;
});}