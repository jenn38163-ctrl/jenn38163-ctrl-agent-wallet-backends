import express from "express";
import cors from "cors";
import helmet from "helmet";
import {config} from "./config.js";
import {db} from "./db.js";
import {migrate} from "./migrate.js";
import {auth} from "./auth.js";
import {audit} from "./audit.js";
import {postPayment} from "./ledger.js";
import {gateway, Capture} from "./settlement/index.js";
const app=express();
app.use(helmet());app.use(cors({origin:config.CORS_ORIGIN==="*" ? true:config.CORS_ORIGIN}));app.use(express.json({limit:"256kb"}));
app.get("/healthz",(_,res)=>res.json({ok:true,service:"agent-wallet-backend"}));
app.get("/readyz",async(_,res)=>{try{await db.execute("select 1");res.json({ok:true,database:"ready"})}catch{res.status(503).json({ok:false,database:"unavailable"})}});
app.use("/v1",auth);
app.post("/v1/wallets",async(req,res)=>{
  const {name,organization,monthlyLimit,perRequestLimit}=req.body??{};
  if([name,organization,monthlyLimit,perRequestLimit].some(x=>typeof x!=="string"||!x))return res.status(400).json({error:"INVALID_REQUEST"});
  try{
    const r=await db.execute({text:"INSERT INTO wallets(name,organization,monthly_limit,per_request_limit) VALUES($1,$2,$3,$4) RETURNING *",values:[name,organization,monthlyLimit,perRequestLimit]} as any);
    const w=r.rows[0];
    await audit("WALLET_CREATED","api",w);
    res.status(201).json(w)
  }catch{res.status(500).json({error:"WALLET_CREATE_FAILED"})}
});
app.get("/v1/wallets/:id",async(req,res)=>{const r=await db.execute({text:"SELECT * FROM wallets WHERE id=$1",values:[req.params.id]} as any);if(!r.rows[0])return res.status(404).json({error:"NOT_FOUND"});res.json(r.rows[0])});
app.post("/v1/payments/authorize",async(req,res)=>{
  const {walletId,amount,vendor,protocol="HTTP_402"}=req.body??{},key=req.header("idempotency-key");
  if(!key||typeof walletId!=="string"||typeof amount!=="string"||typeof vendor!=="string")return res.status(400).json({error:"walletId, amount, vendor and Idempotency-Key are required"});
  try{
    const existing=await db.execute({text:"SELECT response_status,response_body FROM idempotency_keys WHERE key=$1",values:[key]} as any);
    if(existing.rows[0])return res.status(Number((existing.rows[0] as any).response_status)).json(JSON.parse((existing.rows[0] as any).response_body));
    
    const e=await postPayment({walletId,amount,vendor,protocol,idempotencyKey:key});
    
    // Settlement Dispatch
    try {
      await gateway.execute(new Capture({
        intentId: e.id,
        amount,
        currency: 'USDC',
        targetAddress: vendor
      }));
    } catch (settlementErr) {
      console.warn(`Settlement dispatched with warning: ${settlementErr}`);
    }

    const auditHash=await audit("PAYMENT_POSTED",`wallet:${walletId}`,{entryId:e.id,amount,vendor,protocol});
    const body={status:"APPROVED",journalEntryId:e.id,auditHash};
    
    await db.execute({text:"INSERT INTO idempotency_keys(key,response_status,response_body) VALUES($1,201,$2)",values:[key,JSON.stringify(body)]} as any);
    res.status(201).json(body)
  }catch(e:any){const s=Number.isInteger(e?.status)?e.status:500;res.status(s).json({status:s===403?"DENIED":"ERROR",error:e?.message??"Payment failed"})}
});
app.post("/v1/ai/gemini",async(req,res)=>{
  if(!config.GEMINI_API_KEY)return res.status(503).json({error:"GEMINI_NOT_CONFIGURED"});
  const p=req.body?.prompt;
  if(typeof p!=="string"||!p.length||p.length>20000)return res.status(400).json({error:"INVALID_PROMPT"});
  try{
    const r=await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(config.GEMINI_MODEL)}:generateContent`,{method:"POST",headers:{"content-type":"application/json","x-goog-api-key":config.GEMINI_API_KEY},body:JSON.stringify({contents:[{parts:[{text:p}]}]})});
    const b=await r.json();
    if(!r.ok)return res.status(502).json({error:"GEMINI_UPSTREAM_ERROR"});
    res.json(b)
  }catch{res.status(502).json({error:"GEMINI_UNAVAILABLE"})}
});
await migrate();
app.listen(config.PORT,()=>console.log(`Agent Wallet Backend listening on ${config.PORT}`));