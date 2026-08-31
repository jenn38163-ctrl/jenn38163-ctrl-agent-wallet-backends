import type {Request,Response,NextFunction} from "express";
import {config} from "./config.js";
export function auth(req:Request,res:Response,next:NextFunction){
if(config.NODE_ENV!=="production"&&!config.AGENT_WALLET_API_KEY)return next();
const h=req.header("authorization")??"";
const t=h.startsWith("Bearer ")?h.slice(7):"";
if(!t||t!==config.AGENT_WALLET_API_KEY)return res.status(401).json({error:"UNAUTHORIZED"});
next();
}