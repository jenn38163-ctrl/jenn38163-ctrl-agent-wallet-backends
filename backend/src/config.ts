import "dotenv/config";
import { z } from "zod";
const s=z.object({NODE_ENV:z.enum(["development","test","production"]).default("development"),PORT:z.coerce.number().int().positive().default(8080),DATABASE_URL:z.string().min(1),AGENT_WALLET_API_KEY:z.string().optional(),GEMINI_API_KEY:z.string().optional(),GEMINI_MODEL:z.string().default("gemini-3.7-flash"),CORS_ORIGIN:z.string().default("*")});
export const config=s.parse(process.env);
if(config.NODE_ENV==="production"&&!config.AGENT_WALLET_API_KEY) throw new Error("AGENT_WALLET_API_KEY is required in production");