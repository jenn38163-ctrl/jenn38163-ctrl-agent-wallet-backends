import os, shutil, zipfile, textwrap

base="./backend_temp"
shutil.rmtree(base, ignore_errors=True)
os.makedirs(base, exist_ok=True)

files = {
"package.json": r'''{"name":"agent-wallet-backend","version":"0.1.0","private":true,"type":"module","engines":{"node":">=20"},"scripts":{"dev":"tsx watch src/server.ts","build":"tsc -p tsconfig.json","start":"node dist/server.js","test":"vitest run","check":"tsc --noEmit"},"dependencies":{"cors":"^2.8.5","dotenv":"^16.4.7","drizzle-orm":"^0.44.5","express":"^5.1.0","helmet":"^8.1.0","pg":"^8.16.3","zod":"^4.0.17"},"devDependencies":{"@types/cors":"^2.8.19","@types/express":"^5.0.3","@types/node":"^24.3.0","@types/pg":"^8.15.5","tsx":"^4.20.3","typescript":"^5.9.2","vitest":"^3.2.4"}}''',

"tsconfig.json": r'''{"compilerOptions":{"target":"ES2022","module":"NodeNext","moduleResolution":"NodeNext","strict":true,"esModuleInterop":true,"forceConsistentCasingInFileNames":true,"skipLibCheck":true,"outDir":"dist","rootDir":"src"},"include":["src/**/*.ts","test/**/*.ts"]}''',

".env.example": r'''NODE_ENV=development
PORT=8080
DATABASE_URL=postgresql://agent_wallet:agent_wallet@localhost:5432/agent_wallet
AGENT_WALLET_API_KEY=
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.7-flash
CORS_ORIGIN=*''',

".gitignore": "node_modules/\ndist/\n.env\ncoverage/\n*.log\n",

"docker-compose.yml": r'''services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_USER: agent_wallet
      POSTGRES_PASSWORD: agent_wallet
      POSTGRES_DB: agent_wallet
    ports:
      ["5432:5432"]
    volumes:
      - agent-wallet-postgres:/var/lib/postgresql/data
volumes:
  agent-wallet-postgres:''',

"Dockerfile": r'''FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package*.json ./
RUN npm ci --omit=dev
COPY --from=build /app/dist ./dist
EXPOSE 8080
CMD ["node","dist/server.js"]''',

"src/config.ts": r'''import "dotenv/config";
import { z } from "zod";
const s=z.object({NODE_ENV:z.enum(["development","test","production"]).default("development"),PORT:z.coerce.number().int().positive().default(8080),DATABASE_URL:z.string().min(1),AGENT_WALLET_API_KEY:z.string().optional(),GEMINI_API_KEY:z.string().optional(),GEMINI_MODEL:z.string().default("gemini-3.7-flash"),CORS_ORIGIN:z.string().default("*")});
export const config=s.parse(process.env);
if(config.NODE_ENV==="production"&&!config.AGENT_WALLET_API_KEY) throw new Error("AGENT_WALLET_API_KEY is required in production");''',

"src/db.ts": r'''import pg from "pg";
import {drizzle} from "drizzle-orm/node-postgres";
import {config} from "./config.js";
export const pool=new pg.Pool({connectionString:config.DATABASE_URL,max:10});
export const db=drizzle(pool);''',

"src/migrate.ts": r'''import {sql} from "drizzle-orm";
import {db} from "./db.js";
export async function migrate(){
const q=[
`CREATE EXTENSION IF NOT EXISTS pgcrypto`,
`CREATE TABLE IF NOT EXISTS wallets(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),name varchar(160) NOT NULL,organization varchar(160) NOT NULL,monthly_limit numeric(30,6) NOT NULL CHECK(monthly_limit>=0),per_request_limit numeric(30,6) NOT NULL CHECK(per_request_limit>=0),monthly_spent numeric(30,6) NOT NULL DEFAULT 0 CHECK(monthly_spent>=0),status varchar(32) NOT NULL DEFAULT 'ACTIVE',created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS accounts(id varchar(220) PRIMARY KEY,wallet_id uuid REFERENCES wallets(id),account_type varchar(32) NOT NULL,currency varchar(16) NOT NULL DEFAULT 'USDC',created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS journal_entries(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),idempotency_key varchar(255) NOT NULL UNIQUE,wallet_id uuid NOT NULL REFERENCES wallets(id),reference varchar(255) NOT NULL,protocol varchar(64) NOT NULL,status varchar(32) NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS journal_lines(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),journal_entry_id uuid NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,account_id varchar(220) NOT NULL REFERENCES accounts(id),side varchar(2) NOT NULL CHECK(side IN('DR','CR')),amount numeric(30,6) NOT NULL CHECK(amount>0),currency varchar(16) NOT NULL DEFAULT 'USDC')`,
`CREATE INDEX IF NOT EXISTS journal_lines_entry_idx ON journal_lines(journal_entry_id)`,
`CREATE TABLE IF NOT EXISTS audit_events(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),event_type varchar(100) NOT NULL,actor varchar(255) NOT NULL,payload text NOT NULL,previous_hash varchar(64) NOT NULL,event_hash varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`,
`CREATE TABLE IF NOT EXISTS idempotency_keys(key varchar(255) PRIMARY KEY,response_status integer NOT NULL,response_body text NOT NULL,created_at timestamptz NOT NULL DEFAULT now())`
];
for(const x of q) await db.execute(sql.raw(x));
}''',

"src/auth.ts": r'''import type {Request,Response,NextFunction} from "express";
import {config} from "./config.js";
export function auth(req:Request,res:Response,next:NextFunction){
if(config.NODE_ENV!=="production"&&!config.AGENT_WALLET_API_KEY)return next();
const h=req.header("authorization")??"";
const t=h.startsWith("Bearer ")?h.slice(7):"";
if(!t||t!==config.AGENT_WALLET_API_KEY)return res.status(401).json({error:"UNAUTHORIZED"});
next();
}''',

"src/audit.ts": r'''import {createHash} from "node:crypto";
import {db} from "./db.js";
export async function audit(type:string,actor:string,payload:unknown){
const r=await db.execute(sql`SELECT event_hash FROM audit_events ORDER BY created_at DESC LIMIT 1`);
const prev=(r.rows[0] as any)?.event_hash??"0".repeat(64);
const canonical=JSON.stringify({type,actor,payload,prev});
const hash=createHash("sha256").update(canonical).digest("hex");
await db.execute({text:"INSERT INTO audit_events(event_type,actor,payload,previous_hash,event_hash) VALUES($1,$2,$3,$4,$5)",values:[type,actor,JSON.stringify(payload),prev,hash]} as any);
return hash;
}'''.replace("sql`SELECT", "import {sql} from 'drizzle-orm';\nawait db.execute(sql`SELECT"),

"src/ledger.ts": r'''import {db} from "./db.js";
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
});}''',

"src/server.ts": r'''import express from "express";
import cors from "cors";
import helmet from "helmet";
import {config} from "./config.js";
import {db} from "./db.js";
import {migrate} from "./migrate.js";
import {auth} from "./auth.js";
import {audit} from "./audit.js";
import {postPayment} from "./ledger.js";
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
app.listen(config.PORT,()=>console.log(`Agent Wallet Backend listening on ${config.PORT}`));'''
}

for name, content in files.items(): 
    path=os.path.join(base,name) 
    os.makedirs(os.path.dirname(path), exist_ok=True) 
    with open(path,"w",encoding="utf-8") as f: 
        f.write(content)

