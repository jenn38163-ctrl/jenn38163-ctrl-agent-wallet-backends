import express from "express";
import cors from "cors";
import helmet from "helmet";
import {createHash} from "node:crypto";
import {config} from "./config.js";
import {db} from "./db.js";
import {migrate, seedCredentials} from "./migrate.js";
import {auth} from "./auth.js";
import {audit} from "./audit.js";
import {postPayment, revertPayment} from "./ledger.js";
import {processGooglePayFunding} from "./google-pay.js";
import {createFacility, processDrawRequest} from "./facilities.js";
import {gateway, Capture} from "./settlement/index.js";

const app=express();
app.use(helmet());
app.use(cors({origin:config.CORS_ORIGIN==="*" ? true:config.CORS_ORIGIN}));

app.post("/v1/webhooks/payu", express.raw({ type: 'application/json' }), async (req, res) => {
  try {
    const signatureHeader = req.header("OpenPayu-Signature") ?? "";
    const match = signatureHeader.match(/signature=([a-f0-9]+)/);
    if (!match) return res.status(400).send("MISSING_SIGNATURE");
    
    const incomingSignature = match[1];
    if (!config.PAYU_SECOND_KEY) return res.status(503).send("PAYU_KEY_NOT_CONFIGURED");
    
    const rawBody = req.body.toString('utf8');
    const expectedSignature = createHash('md5').update(rawBody + config.PAYU_SECOND_KEY).digest('hex');
    
    if (incomingSignature !== expectedSignature) return res.status(401).send("INVALID_SIGNATURE");
    
    const data = JSON.parse(rawBody);
    const extOrderId = data.order.extOrderId; // UUID we sent
    const orderId = data.order.orderId;       // PayU provider ref
    const status = data.order.status; 
    const eventId = orderId || `${extOrderId}-${status}`;
    
    let newStatus = "SUBMITTED";
    if (status === "COMPLETED") newStatus = "CONFIRMED";
    else if (status === "CANCELED" || status === "REJECTED") newStatus = "FAILED";
    
    if (newStatus !== "SUBMITTED") {
      try {
        await db.execute({
          text: "INSERT INTO webhook_events(provider, event_id, payload) VALUES('payu', $1, $2)",
          values: [eventId, rawBody]
        } as any);
      } catch (e: any) {
        if (e.code === '23505') return res.status(200).send("OK_ALREADY_PROCESSED");
        throw e;
      }

      await db.transaction(async(tx) => {
        // Safe lookup: either by our generated UUID or PayU's orderId.
        // We'll use extOrderId (intent ID) but strictly validate UUID format or catch.
        let intentId: string | undefined;
        let isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(extOrderId);
        let idCheck = isUuid ? "id=$2" : "provider_ref=$2";
        try {
           const result = await tx.execute({
             text:`UPDATE settlement_intents SET status=$1 WHERE ${idCheck} AND status IN ('PENDING', 'SUBMITTED') RETURNING id`,
             values:[newStatus, extOrderId]
           } as any);
           if (result.rows.length > 0) intentId = (result.rows[0] as any).id;
        } catch(lookupErr: any) {
           console.warn(`Webhook lookup by extOrderId failed: ${lookupErr.message}`);
        }

        if (intentId) {
          const eRes = await tx.execute({
            text:"UPDATE journal_entries SET status=$1 WHERE id=(SELECT journal_entry_id FROM settlement_intents WHERE id=$2) RETURNING id",
            values:[newStatus, intentId]
          } as any);
          
          await audit(tx, "PAYU_WEBHOOK", "payu_provider", { extOrderId, status: newStatus, eventId });
        }
      });
      
      if (newStatus === "FAILED") {
        try {
          let isUuidFail = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(extOrderId);
          const jRes = await db.execute({text:`SELECT journal_entry_id FROM settlement_intents WHERE ${isUuidFail ? "id=$1" : "provider_ref=$1"}`, values:[extOrderId]} as any);
          if (jRes.rows.length > 0) {
              await revertPayment((jRes.rows[0] as any).journal_entry_id);
          }
        } catch (err: any) {}
      }
    }
    
    res.status(200).send("OK");
  } catch (e) {
    console.error(`PayU webhook error:`, e);
    res.status(500).send("ERROR");
  }
});

app.use(express.json({limit:"256kb"}));
app.get("/healthz",(_,res)=>res.json({ok:true,service:"agent-wallet-backend"}));
app.get("/readyz",async(_,res)=>{try{await db.execute("select 1");res.json({ok:true,database:"ready"})}catch{res.status(503).json({ok:false,database:"unavailable"})}});

app.use("/v1",auth);

app.post("/v1/wallets",async(req,res)=>{
  const {name,monthlyLimit,perRequestLimit}=req.body??{};
  const organization = req.principal?.organizationId;
  if(!organization) return res.status(403).json({error:"TENANT_NOT_FOUND"});
  if([name,monthlyLimit,perRequestLimit].some(x=>typeof x!=="string"||!x))return res.status(400).json({error:"INVALID_REQUEST"});
  try{
    const r=await db.execute({text:"INSERT INTO wallets(name,organization,monthly_limit,per_request_limit) VALUES($1,$2,$3,$4) RETURNING *",values:[name,organization,monthlyLimit,perRequestLimit]} as any);
    const w=r.rows[0];
    await db.transaction(async(tx)=>{ await audit(tx, "WALLET_CREATED","api",w); });
    res.status(201).json(w)
  }catch{res.status(500).json({error:"WALLET_CREATE_FAILED"})}});

app.get("/v1/wallets/:id",async(req,res)=>{
  const organization = req.principal?.organizationId;
  const r=await db.execute({text:"SELECT * FROM wallets WHERE id=$1 AND organization=$2",values:[req.params.id, organization]} as any);
  if(!r.rows[0])return res.status(404).json({error:"NOT_FOUND"});
  res.json(r.rows[0])});

app.post("/v1/payments/authorize",async(req,res)=>{
  const {walletId,amount,currency="USDC",vendor,protocol="HTTP_402",provider="STARKNET"}=req.body??{};
  const key=req.header("idempotency-key");
  const organization = req.principal?.organizationId;
  if(!key||typeof walletId!=="string"||typeof amount!=="string"||typeof vendor!=="string")return res.status(400).json({error:"walletId, amount, vendor and Idempotency-Key are required"});
  if(!organization) return res.status(403).json({error:"TENANT_NOT_FOUND"});

  try{
    const existing=await db.execute({text:"SELECT response_status,response_body FROM idempotency_keys WHERE key=$1 AND response_status != 202",values:[key]} as any);
    if(existing.rows[0])return res.status(Number((existing.rows[0] as any).response_status)).json(JSON.parse((existing.rows[0] as any).response_body));
    
    // The entire setup is now in postPayment in one transaction
    const { entryId, intentId } = await postPayment({
       tenantId: organization, 
       walletId, amount, currency, vendor, protocol, provider, idempotencyKey:key
    });
    
    // Settlement Dispatch is Async / Separate
    let settlementStatus = "SUBMITTED";
    try {
      const gRes = await gateway.execute(provider, new Capture({
        intentId: intentId,
        amount,
        currency,
        targetAddress: vendor
      }));
      
      const providerRef = (gRes as any)?.providerRef;
      if (providerRef) {
         await db.execute({text:"UPDATE settlement_intents SET provider_ref=$1 WHERE id=$2",values:[providerRef, intentId]} as any);
      }
      
      await db.execute({text:"UPDATE journal_entries SET status=$1 WHERE id=$2",values:[settlementStatus, entryId]} as any);
      await db.execute({text:"UPDATE settlement_intents SET status=$1 WHERE id=$2",values:[settlementStatus, intentId]} as any);
    } catch (settlementErr) {
      console.warn(`Settlement failed: ${settlementErr}`);
      settlementStatus = "FAILED";
      await db.execute({text:"UPDATE settlement_intents SET status='FAILED' WHERE id=$1",values:[intentId]} as any);
      await revertPayment(entryId);
    }

    let auditHash = "";
    await db.transaction(async(tx) => {
      auditHash = await audit(tx, "PAYMENT_POSTED",`wallet:${walletId}`,{entryId:entryId,amount,vendor,protocol,settlementStatus});
    });

    const body={status:settlementStatus,journalEntryId:entryId,auditHash};
    
    await db.execute({text:"UPDATE idempotency_keys SET response_status=201, response_body=$2 WHERE key=$1",values:[key,JSON.stringify(body)]} as any);
    res.status(201).json(body)
  }catch(e:any){
    const s=Number.isInteger(e?.status)?e.status:500;
    res.status(s).json({status:s===403?"DENIED":"ERROR",error:e?.message??"Payment failed"})
  }
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

app.post("/v1/wallets/:id/fund/google-pay", async (req, res) => {
  const organization = req.principal?.organizationId;
  const walletId = req.params.id;
  const { amount, token } = req.body ?? {};
  if (!amount || !token) return res.status(400).json({ error: "amount and token are required" });

  try {
    const w = await db.execute({ text: "SELECT id FROM wallets WHERE id=$1 AND organization=$2", values: [walletId, organization] } as any);
    if (!w.rows[0]) throw Object.assign(new Error("Wallet not found in tenant"), { status: 404 });

    return res.status(501).json({ error: "NOT_IMPLEMENTED" });
  } catch (e: any) {
    const s = Number.isInteger(e?.status) ? e.status : 500;
    res.status(s).json({ status: "ERROR", error: e?.message ?? "Funding failed" });
  }
});

app.post("/v1/facilities", async (req, res) => {
  const organization = req.principal?.organizationId;
  if (!organization) return res.status(403).json({ error: "Tenant required" });
  const { facilityId, borrowerId, agentId, commitment } = req.body ?? {};
  if (!facilityId || !borrowerId || !agentId || !commitment) return res.status(400).json({ error: "Missing required facility fields" });
  
  try {
    const result = await createFacility(facilityId, organization, borrowerId, agentId, String(commitment));
    res.status(201).json(result);
  } catch (e: any) {
    res.status(500).json({ error: e?.message ?? "Facility creation failed" });
  }
});

app.post("/v1/facilities/:id/draw", async (req, res) => {
  const facilityId = req.params.id;
  const organization = req.principal?.organizationId;
  if (!organization) return res.status(403).json({ error: "Tenant required" });
  
  const { drawAmount, walletId } = req.body ?? {};
  const key = req.header("idempotency-key");
  
  if (!key || !drawAmount || !walletId) return res.status(400).json({ error: "Missing required draw fields or idempotency-key" });
  
  try {
    const result = await processDrawRequest(facilityId, organization, String(drawAmount), walletId, key, req.body);
    res.status(201).json(result);
  } catch (e: any) {
    const s = Number.isInteger(e?.status) ? e.status : 500;
    res.status(s).json({ status: "ERROR", error: e?.message ?? "Draw failed" });
  }
});

await migrate();
await seedCredentials();
app.listen(config.PORT,()=>console.log(`Agent Wallet Backend listening on ${config.PORT}`));
