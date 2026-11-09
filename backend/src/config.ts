import "dotenv/config";
import { z } from "zod";

const s=z.object({
  NODE_ENV:z.enum(["development","test","production"]).default("development"),
  PORT:z.coerce.number().int().positive().default(8080),
  DATABASE_URL:z.string().min(1),
  AGENT_WALLET_API_KEY:z.string().min(8),
  GEMINI_API_KEY:z.string().optional(),
  GEMINI_MODEL:z.string().default("gemini-3.7-flash"),
  CORS_ORIGIN:z.string().min(1),
  
  // PayU Configuration
  PAYU_MERCHANT_POS_ID: z.string().optional(),
  PAYU_CLIENT_ID: z.string().optional(),
  PAYU_CLIENT_SECRET: z.string().optional(),
  PAYU_SECOND_KEY: z.string().optional(),

  // Pimlico (ERC-4337 Bundler & Paymaster) Configuration
  PIMLICO_API_KEY: z.string().optional(),
  PIMLICO_CHAIN_ID: z.string().default("8453")
});

export const config=s.parse(process.env);
if(config.NODE_ENV==="production" && config.CORS_ORIGIN === "*") throw new Error("CORS_ORIGIN cannot be * in production");
