import {createHash} from "node:crypto";
import {sql} from 'drizzle-orm';
import {db} from "./db.js";

export async function audit(type:string,actor:string,payload:unknown){
const r=await db.execute(sql`SELECT event_hash FROM audit_events ORDER BY created_at DESC LIMIT 1`);
const prev=(r.rows[0] as any)?.event_hash??"0".repeat(64);
const canonical=JSON.stringify({type,actor,payload,prev});
const hash=createHash("sha256").update(canonical).digest("hex");
await db.execute({text:"INSERT INTO audit_events(event_type,actor,payload,previous_hash,event_hash) VALUES($1,$2,$3,$4,$5)",values:[type,actor,JSON.stringify(payload),prev,hash]} as any);
return hash;
}
