import { createHash } from "node:crypto";

export function validAmount(a: string) {
  if (!/^\d+(\.\d{1,6})?$/.test(a) || Number(a) <= 0) {
    throw Object.assign(new Error("Invalid positive decimal amount"), { status: 400 });
  }
  return a;
}

export function hashRequest(body: any): string {
  return createHash("sha256").update(JSON.stringify(body || {})).digest("hex");
}
