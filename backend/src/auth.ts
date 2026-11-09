import type {Request,Response,NextFunction} from "express";
import {createHash} from "node:crypto";
import {db} from "./db.js";

declare module 'express-serve-static-core' {
  interface Request {
    principal?: {
      organizationId: string;
      role: string;
      scopes: string[];
    }
  }
}

export async function auth(req:Request, res:Response, next:NextFunction) {
  const h = req.header("authorization") ?? "";
  const t = h.startsWith("Bearer ") ? h.slice(7) : "";
  
  if (!t) return res.status(401).json({error: "UNAUTHORIZED"});
  
  const tokenHash = createHash("sha256").update(t).digest("hex");
  
  try {
    const cred = (await db.execute({
      text: "SELECT organization_id, role, scopes FROM credentials WHERE token_hash=$1 AND status='ACTIVE'",
      values: [tokenHash]
    } as any)).rows[0] as any;
    
    if (!cred) return res.status(401).json({error: "INVALID_CREDENTIAL"});
    
    req.principal = {
      organizationId: cred.organization_id,
      role: cred.role,
      scopes: cred.scopes ? cred.scopes.split(',') : []
    };
    
    next();
  } catch (e) {
    res.status(500).json({error: "AUTH_ERROR"});
  }
}

export function requireScope(requiredScope: string) {
  return (req: Request, res: Response, next: NextFunction) => {
    if (!req.principal) {
      return res.status(401).json({ error: "UNAUTHORIZED" });
    }
    const scopes = req.principal.scopes;
    if (scopes.includes("all") || scopes.includes(requiredScope) || scopes.includes("admin:*")) {
      return next();
    }
    return res.status(403).json({ error: "FORBIDDEN", requiredScope });
  };
}
