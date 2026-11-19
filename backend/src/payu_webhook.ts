import { createHash, timingSafeEqual } from "node:crypto";
import { audit } from "./audit.js";
import { revertPaymentTx } from "./ledger.js";
import { toPayUSmallestUnit } from "./settlement/actions/PayUSettlementAction.js";

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function verifyPayuSignature(
  rawBody: string,
  signatureHeader: string,
  secondKey?: string
): { ok: true } | { ok: false; status: number; error: string } {
  if (!secondKey) {
    return { ok: false, status: 503, error: "PAYU_KEY_NOT_CONFIGURED" };
  }
  const sigMatch = signatureHeader.match(/signature=([a-fA-F0-9]+)/);
  if (!sigMatch) {
    return { ok: false, status: 400, error: "MISSING_SIGNATURE" };
  }

  const algMatch = signatureHeader.match(/algorithm=([A-Za-z0-9-]+)/i);
  const rawAlg = (algMatch?.[1] || "MD5").toUpperCase();
  const nodeAlg =
    rawAlg === "MD5"
      ? "md5"
      : rawAlg === "SHA-256" || rawAlg === "SHA256"
        ? "sha256"
        : null;

  if (!nodeAlg) {
    return { ok: false, status: 400, error: "UNSUPPORTED_SIGNATURE_ALGORITHM" };
  }

  const incomingHex = sigMatch[1].toLowerCase();
  const expectedHex = createHash(nodeAlg)
    .update(rawBody + secondKey)
    .digest("hex")
    .toLowerCase();

  const incomingBuf = Buffer.from(incomingHex, "utf8");
  const expectedBuf = Buffer.from(expectedHex, "utf8");

  if (incomingBuf.length !== expectedBuf.length || !timingSafeEqual(incomingBuf, expectedBuf)) {
    return { ok: false, status: 401, error: "INVALID_SIGNATURE" };
  }

  return { ok: true };
}

export async function processPayuWebhookTx(
  tx: any,
  rawBody: string,
  data: any
): Promise<{ statusCode: number; body: string }> {
  const order = data?.order;
  if (!order || typeof order.status !== "string") {
    return { statusCode: 400, body: "INVALID_ORDER_PAYLOAD" };
  }

  const extOrderId = typeof order.extOrderId === "string" ? order.extOrderId.trim() : "";
  const orderId = typeof order.orderId === "string" ? order.orderId.trim() : "";
  const payuStatus = order.status.trim().toUpperCase();

  let newStatus: "CONFIRMED" | "FAILED" | null = null;
  if (payuStatus === "COMPLETED") newStatus = "CONFIRMED";
  else if (payuStatus === "CANCELED" || payuStatus === "REJECTED") newStatus = "FAILED";

  if (!newStatus) {
    return { statusCode: 200, body: "OK_NON_TERMINAL" };
  }

  // Locate and lock the settlement intent row strictly before inserting webhook_events
  // so missing/uncreated rows fail closed with 404/retry instead of poisoning deduplication.
  let intent: any;
  if (orderId) {
    const byProvider = await tx.execute({
      text: "SELECT * FROM settlement_intents WHERE provider_ref = $1 FOR UPDATE",
      values: [orderId]
    } as any);
    intent = byProvider.rows[0];
  }
  if (!intent && extOrderId && UUID_REGEX.test(extOrderId)) {
    const byId = await tx.execute({
      text: "SELECT * FROM settlement_intents WHERE id = $1 FOR UPDATE",
      values: [extOrderId]
    } as any);
    intent = byId.rows[0];
  }

  if (!intent) {
    return { statusCode: 404, body: "INTENT_NOT_FOUND" };
  }

  if (newStatus === "CONFIRMED") {
    const intentCurrency = intent.currency ? String(intent.currency).trim().toUpperCase() : "";
    const orderCurrency =
      typeof order.currencyCode === "string" ? order.currencyCode.trim().toUpperCase() : "";
    const currencyMismatch = Boolean(intentCurrency && orderCurrency && intentCurrency !== orderCurrency);

    let amountMismatch = false;
    let expectedMinor: string | undefined;
    const orderAmountStr =
      order.totalAmount !== undefined && order.totalAmount !== null
        ? String(order.totalAmount).trim()
        : "";
    if (intent.amount !== undefined && intent.amount !== null && orderAmountStr) {
      expectedMinor = toPayUSmallestUnit(String(intent.amount));
      amountMismatch = expectedMinor !== orderAmountStr;
    }

    if (currencyMismatch || amountMismatch) {
      await audit(
        tx,
        "PAYU_WEBHOOK_RECONCILIATION_MISMATCH",
        "payu_provider",
        {
          intentId: intent.id,
          orderId,
          expectedCurrency: intentCurrency || null,
          actualCurrency: orderCurrency || null,
          expectedMinor: expectedMinor ?? null,
          actualMinor: orderAmountStr || null
        },
        intent.organization_id || "system"
      );
      return { statusCode: 409, body: "RECONCILIATION_MISMATCH" };
    }
  }

  const eventId = orderId ? `${orderId}:${payuStatus}` : `${intent.id}:${payuStatus}`;

  try {
    await tx.execute({
      text: `INSERT INTO webhook_events(provider, event_id, intent_id, processing_status, payload)
             VALUES('payu', $1, $2, $3, $4)`,
      values: [eventId, intent.id, newStatus, rawBody]
    } as any);
  } catch (e: any) {
    if (e.code === "23505") {
      return { statusCode: 200, body: "OK_ALREADY_PROCESSED" };
    }
    throw e;
  }

  if (
    intent.status === "PENDING" ||
    intent.status === "PROCESSING" ||
    intent.status === "SUBMITTED"
  ) {
    await tx.execute({
      text: `UPDATE settlement_intents
             SET status = $1,
                 provider_ref = COALESCE(provider_ref, NULLIF($2, '')),
                 locked_at = NULL,
                 locked_by = NULL,
                 last_error = CASE WHEN $1 = 'FAILED' THEN $3 ELSE NULL END,
                 updated_at = now()
             WHERE id = $4`,
      values: [
        newStatus,
        orderId,
        newStatus === "FAILED" ? `PayU status: ${payuStatus}` : null,
        intent.id
      ]
    } as any);

    if (newStatus === "CONFIRMED") {
      await tx.execute({
        text: `UPDATE journal_entries
               SET status = 'CONFIRMED'
               WHERE id = $1 AND status IN ('LEDGER_POSTED', 'SUBMITTED')`,
        values: [intent.journal_entry_id]
      } as any);

      await audit(
        tx,
        "PAYU_WEBHOOK_CONFIRMED",
        "payu_provider",
        { intentId: intent.id, orderId, extOrderId, eventId, status: newStatus },
        intent.organization_id || "system"
      );
    } else {
      await revertPaymentTx(tx, intent.journal_entry_id, `PAYU_WEBHOOK_${payuStatus}`);
    }

    return { statusCode: 200, body: "OK" };
  }

  // Already in a terminal state (CONFIRMED / SETTLED / FAILED / REVERSED)
  if (
    (intent.status === "CONFIRMED" || intent.status === "SETTLED") &&
    newStatus === "FAILED"
  ) {
    await audit(
      tx,
      "PAYU_WEBHOOK_CONFLICT",
      "payu_provider",
      { intentId: intent.id, currentStatus: intent.status, webhookStatus: newStatus, eventId },
      intent.organization_id || "system"
    );
  }

  return { statusCode: 200, body: "OK_ALREADY_FINALIZED" };
}
