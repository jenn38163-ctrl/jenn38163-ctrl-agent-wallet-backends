import type {Request,Response,NextFunction} from "express";
import {config} from "./config.js";

declare module 'express-serve-static-core' {
  interface Request {
    principal?: {
      organizationId: string;
      role: string;
    }
  }
}

export function auth(req:Request,res:Response,next:NextFunction){
  const h=req.header("authorization")??"";
  const t=h.startsWith("Bearer ")?h.slice(7):"";
  
  if(!t || t !== config.AGENT_WALLET_API_KEY) {
    return res.status(401).json({error:"UNAUTHORIZED"});
  }

  // Bind tenant context
  req.principal = {
    organizationId: "AI Corp Enterprises", // In a real system, mapped from the API key
    role: "AGENT"
  };
  
  next();
}
