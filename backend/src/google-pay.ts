import { createHash } from "node:crypto";
import { db } from "./db.js";
import { audit } from "./audit.js";
import { validAmount } from "./utils.js";
import { config } from "./config.js";

export async function processGooglePayFunding(walletId: string, tokenPayload: any, amountStr: string) {
  if (config.NODE_ENV === 'production') {
    throw Object.assign(new Error("DISABLED_IN_PRODUCTION: Google Pay ECv2 decryption not fully implemented"), { status: 501 });
  }

  const amount = validAmount(amountStr);
  return db.transaction(async (tx) => {
    const w = (await tx.execute({
      text: "SELECT status FROM wallets WHERE id=$1 FOR UPDATE",
      values: [walletId]
    } as any)).rows[0] as any;
    
    if (!w) throw Object.assign(new Error("Wallet not found"), { status: 404 });
    if (w.status !== "ACTIVE") throw Object.assign(new Error("Wallet inactive"), { status: 409 });

    if (!tokenPayload || !tokenPayload.signature) {
      throw Object.assign(new Error("Invalid Google Pay ECv2 token"), { status: 400 });
    }

    const dr = `asset:usdc:wallet:${walletId}`;
    const cr = `liability:external:google_pay`;
    
    await tx.execute({
      text: "INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET','USDC') ON CONFLICT DO NOTHING",
      values: [dr, walletId]
    } as any);
    await tx.execute({
      text: "INSERT INTO accounts(id,account_type,currency) VALUES($1,'LIABILITY','USDC') ON CONFLICT DO NOTHING",
      values: [cr]
    } as any);

    const extRef = createHash("md5").update(tokenPayload.signature).digest("hex");
    const e = (await tx.execute({
      text: "INSERT INTO journal_entries(idempotency_key,wallet_id,reference,protocol,status) VALUES($1,$2,$3,'GOOGLE_PAY','SETTLEMENT_CONFIRMED') RETURNING id",
      values: [`gpay_${extRef}`, walletId, `funding:gpay`]
    } as any)).rows[0] as any;
    
    await tx.execute({
      text: "INSERT INTO journal_lines(journal_entry_id,account_id,side,amount,currency) VALUES($1,$2,'DR',$3,'USDC'),($1,$4,'CR',$3,'USDC')",
      values: [e.id, dr, amount, cr]
    } as any);

    const auditHash = await audit(tx, "GOOGLE_PAY_FUNDING", `wallet:${walletId}`, {
      entryId: e.id,
      amount,
      walletId
    });

    return { journalEntryId: e.id, auditHash };
  });
}
