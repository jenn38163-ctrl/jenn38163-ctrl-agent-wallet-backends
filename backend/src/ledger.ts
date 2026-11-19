import { db } from "./db.js";
import { audit } from "./audit.js";
import { validAmount, hashRequest } from "./utils.js";

export const ALLOWED_CURRENCIES = new Set(["USD", "PLN", "USDC"]);

export const PROVIDER_CURRENCIES: Record<string, Set<string>> = {
  PAYU: new Set(["USD", "PLN"])
};

export function normalizeCurrency(rawCurrency: string): string {
  const normalized = String(rawCurrency || "").trim().toUpperCase();
  if (!ALLOWED_CURRENCIES.has(normalized)) {
    throw Object.assign(new Error(`UNSUPPORTED_CURRENCY: ${normalized || "EMPTY"}`), {
      status: 400
    });
  }
  return normalized;
}

export async function postPayment(i: {
  tenantId: string;
  walletId: string;
  amount: string;
  currency: string;
  vendor: string;
  protocol: string;
  provider: string;
  idempotencyKey: string;
}) {
  const amount = validAmount(i.amount);
  const currency = normalizeCurrency(i.currency);
  const provider = String(i.provider || "").trim().toUpperCase();
  const providerAllowed = PROVIDER_CURRENCIES[provider];
  if (providerAllowed && !providerAllowed.has(currency)) {
    throw Object.assign(
      new Error(`UNSUPPORTED_PROVIDER_CURRENCY: ${provider} does not support ${currency}`),
      { status: 400 }
    );
  }

  const requestHash = hashRequest({
    tenantId: i.tenantId,
    walletId: i.walletId,
    amount,
    currency,
    vendor: i.vendor,
    protocol: i.protocol,
    provider
  });

  return db.transaction(async (tx) => {
    try {
      await tx.execute({
        text: `INSERT INTO idempotency_keys(organization_id, key, request_hash, response_status, response_body)
               VALUES($1, $2, $3, 202, 'Processing')`,
        values: [i.tenantId, i.idempotencyKey, requestHash]
      } as any);
    } catch (e: any) {
      if (e.code === "23505") {
        const existing = await tx.execute({
          text: `SELECT request_hash, response_status, response_body
                 FROM idempotency_keys
                 WHERE organization_id = $1 AND key = $2`,
          values: [i.tenantId, i.idempotencyKey]
        } as any);
        if (existing.rows[0]) {
          const row = existing.rows[0] as any;
          if (row.request_hash !== requestHash) {
            throw Object.assign(new Error("Idempotency key reused with different request payload"), {
              status: 409
            });
          }
          throw Object.assign(new Error("Idempotency key exists or is being processed"), {
            status: 409,
            existingResponse: row.response_body,
            existingStatus: row.response_status
          });
        }
      }
      throw e;
    }

    // Lock wallet in tenant and enforce status, currency, balance, monthly_limit, and per_request_limit atomically
    const walletRow = (
      await tx.execute({
        text: `SELECT id, status, currency, balance, monthly_limit, monthly_spent, per_request_limit
               FROM wallets
               WHERE id = $1 AND organization = $2
               FOR UPDATE`,
        values: [i.walletId, i.tenantId]
      } as any)
    ).rows[0] as any;

    if (!walletRow) {
      throw Object.assign(new Error("Wallet not found in tenant"), { status: 404 });
    }
    if (walletRow.status !== "ACTIVE") {
      throw Object.assign(new Error("Wallet inactive"), { status: 409 });
    }

    const walletCurrency = String(walletRow.currency || "").trim().toUpperCase();
    if (!walletCurrency || walletCurrency !== currency) {
      throw Object.assign(
        new Error(
          `CURRENCY_MISMATCH: wallet currency is ${walletCurrency || "UNKNOWN"}, payment currency is ${currency}`
        ),
        { status: 400 }
      );
    }

    const updatedWallet = (
      await tx.execute({
        text: `UPDATE wallets
               SET balance = balance - $3::numeric,
                   monthly_spent = monthly_spent + $3::numeric
               WHERE id = $1
                 AND organization = $2
                 AND status = 'ACTIVE'
                 AND UPPER(currency) = $4
                 AND balance >= $3::numeric
                 AND monthly_spent + $3::numeric <= monthly_limit
                 AND $3::numeric <= per_request_limit
               RETURNING id, balance, monthly_spent, currency`,
        values: [i.walletId, i.tenantId, amount, currency]
      } as any)
    ).rows[0] as any;

    if (!updatedWallet) {
      // Distinguish insufficient balance vs policy limit exceeded
      const balCheck = await tx.execute({
        text: `SELECT (balance < $2::numeric) AS insufficient_balance FROM wallets WHERE id = $1`,
        values: [i.walletId, amount]
      } as any);
      if ((balCheck.rows[0] as any)?.insufficient_balance) {
        throw Object.assign(new Error("Insufficient wallet balance"), { status: 403 });
      }
      throw Object.assign(new Error("Limits exceeded"), { status: 403 });
    }

    const dr = `asset:${currency.toLowerCase()}:wallet:${i.walletId}`;
    const cr = `liability:vendor:${i.vendor.toLowerCase()}`;

    await tx.execute({
      text: "INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET',$3) ON CONFLICT DO NOTHING",
      values: [dr, i.walletId, currency]
    } as any);
    await tx.execute({
      text: "INSERT INTO accounts(id,account_type,currency) VALUES($1,'LIABILITY',$2) ON CONFLICT DO NOTHING",
      values: [cr, currency]
    } as any);

    const e = (
      await tx.execute({
        text: `INSERT INTO journal_entries(organization_id, idempotency_key, wallet_id, reference, protocol, status)
               VALUES($1, $2, $3, $4, $5, 'LEDGER_POSTED')
               RETURNING id`,
        values: [i.tenantId, i.idempotencyKey, i.walletId, `payment:${i.vendor}`, i.protocol]
      } as any)
    ).rows[0] as any;

    await tx.execute({
      text: `INSERT INTO journal_lines(journal_entry_id, account_id, side, amount, currency)
             VALUES($1, $2, 'CR', $3, $4), ($1, $5, 'DR', $3, $4)`,
      values: [e.id, dr, amount, currency, cr]
    } as any);

    const intentRes = await tx.execute({
      text: `INSERT INTO settlement_intents(journal_entry_id, organization_id, provider, amount, currency, target_address, status)
             VALUES($1, $2, $3, $4, $5, $6, 'PENDING')
             RETURNING id`,
      values: [e.id, i.tenantId, provider, amount, currency, i.vendor]
    } as any);
    const intentId = (intentRes.rows[0] as any).id;

    const auditHash = await audit(
      tx,
      "PAYMENT_QUEUED",
      `wallet:${i.walletId}`,
      {
        entryId: e.id,
        intentId,
        amount,
        currency,
        vendor: i.vendor,
        protocol: i.protocol,
        provider,
        status: "PENDING"
      },
      i.tenantId
    );

    const responseBody = {
      status: "PENDING",
      journalEntryId: e.id,
      settlementIntentId: intentId,
      debitAccountId: cr,
      creditAccountId: dr,
      amount,
      currency,
      vendor: i.vendor,
      protocol: i.protocol,
      provider,
      auditHash
    };

    await tx.execute({
      text: `UPDATE idempotency_keys
             SET response_status = 201, response_body = $3
             WHERE organization_id = $1 AND key = $2`,
      values: [i.tenantId, i.idempotencyKey, JSON.stringify(responseBody)]
    } as any);

    return {
      entryId: e.id,
      intentId,
      auditHash,
      responseBody
    };
  });
}

export async function depositToWallet(i: {
  tenantId: string;
  walletId: string;
  amount: string;
  currency?: string;
  source?: string;
  idempotencyKey: string;
}) {
  const amount = validAmount(i.amount);
  const requestedCurrency = i.currency !== undefined ? normalizeCurrency(i.currency) : undefined;
  const source = i.source || "treasury_deposit";
  const requestHash = hashRequest({
    op: "fund",
    tenantId: i.tenantId,
    walletId: i.walletId,
    amount,
    currency: requestedCurrency ?? "WALLET_DEFAULT",
    source
  });

  return db.transaction(async (tx) => {
    try {
      await tx.execute({
        text: `INSERT INTO idempotency_keys(organization_id, key, request_hash, response_status, response_body)
               VALUES($1, $2, $3, 202, 'Processing')`,
        values: [i.tenantId, i.idempotencyKey, requestHash]
      } as any);
    } catch (e: any) {
      if (e.code === "23505") {
        const existing = await tx.execute({
          text: `SELECT request_hash, response_status, response_body
                 FROM idempotency_keys
                 WHERE organization_id = $1 AND key = $2`,
          values: [i.tenantId, i.idempotencyKey]
        } as any);
        if (existing.rows[0]) {
          const row = existing.rows[0] as any;
          if (row.request_hash !== requestHash) {
            throw Object.assign(new Error("Idempotency key reused with different request payload"), {
              status: 409
            });
          }
          throw Object.assign(new Error("Idempotency key exists or is being processed"), {
            status: 409,
            existingResponse: row.response_body,
            existingStatus: row.response_status
          });
        }
      }
      throw e;
    }

    const walletRow = (
      await tx.execute({
        text: "SELECT id, status, currency FROM wallets WHERE id = $1 AND organization = $2 FOR UPDATE",
        values: [i.walletId, i.tenantId]
      } as any)
    ).rows[0] as any;

    if (!walletRow) throw Object.assign(new Error("Wallet not found in tenant"), { status: 404 });
    if (walletRow.status !== "ACTIVE") throw Object.assign(new Error("Wallet inactive"), { status: 409 });

    const walletCurrency = normalizeCurrency(String(walletRow.currency || "USD"));
    const currency = requestedCurrency ?? walletCurrency;
    if (currency !== walletCurrency) {
      throw Object.assign(
        new Error(
          `CURRENCY_MISMATCH: wallet currency is ${walletCurrency}, funding currency is ${currency}`
        ),
        { status: 400 }
      );
    }

    const w = (
      await tx.execute({
        text: `UPDATE wallets
               SET balance = balance + $3::numeric
               WHERE id = $1 AND organization = $2 AND status = 'ACTIVE'
               RETURNING *`,
        values: [i.walletId, i.tenantId, amount]
      } as any)
    ).rows[0] as any;

    const dr = `asset:${currency.toLowerCase()}:wallet:${i.walletId}`;
    const cr = `equity:funding:${source.toLowerCase()}`;

    await tx.execute({
      text: "INSERT INTO accounts(id,wallet_id,account_type,currency) VALUES($1,$2,'ASSET',$3) ON CONFLICT DO NOTHING",
      values: [dr, i.walletId, currency]
    } as any);
    await tx.execute({
      text: "INSERT INTO accounts(id,account_type,currency) VALUES($1,'EQUITY',$2) ON CONFLICT DO NOTHING",
      values: [cr, currency]
    } as any);

    const e = (
      await tx.execute({
        text: `INSERT INTO journal_entries(organization_id, idempotency_key, wallet_id, reference, protocol, status)
               VALUES($1, $2, $3, $4, 'TREASURY_FUNDING', 'CONFIRMED')
               RETURNING id`,
        values: [i.tenantId, i.idempotencyKey, i.walletId, `funding:${source}`]
      } as any)
    ).rows[0] as any;

    await tx.execute({
      text: `INSERT INTO journal_lines(journal_entry_id, account_id, side, amount, currency)
             VALUES($1, $2, 'DR', $3, $4), ($1, $5, 'CR', $3, $4)`,
      values: [e.id, dr, amount, currency, cr]
    } as any);

    const auditHash = await audit(
      tx,
      "WALLET_FUNDED",
      `wallet:${i.walletId}`,
      { entryId: e.id, walletId: i.walletId, amount, currency, source, newBalance: w.balance },
      i.tenantId
    );

    const responseBody = {
      status: "CONFIRMED",
      journalEntryId: e.id,
      currency,
      wallet: w,
      auditHash
    };

    await tx.execute({
      text: `UPDATE idempotency_keys
             SET response_status = 201, response_body = $3
             WHERE organization_id = $1 AND key = $2`,
      values: [i.tenantId, i.idempotencyKey, JSON.stringify(responseBody)]
    } as any);

    return responseBody;
  });
}

export async function revertPaymentTx(
  tx: any,
  journalEntryId: string,
  reason = "SETTLEMENT_FAILED"
): Promise<{ reverted: boolean; reason: string; reversalEntryId?: string }> {
  const e = (
    await tx.execute({
      text: "SELECT * FROM journal_entries WHERE id=$1 FOR UPDATE",
      values: [journalEntryId]
    } as any)
  ).rows[0] as any;

  if (!e) return { reverted: false, reason: "NOT_FOUND" };
  if (e.status === "REVERSED") return { reverted: false, reason: "ALREADY_REVERSED" };
  if (e.status === "CONFIRMED" || e.status === "SETTLED") {
    throw Object.assign(new Error(`Cannot revert finalized journal entry in status ${e.status}`), {
      status: 409
    });
  }

  let compId: string;
  try {
    const comp = (
      await tx.execute({
        text: `INSERT INTO journal_entries(organization_id, idempotency_key, wallet_id, reference, protocol, status, reversal_of)
               VALUES($1, $2, $3, $4, $5, 'REVERSED', $6)
               RETURNING id`,
        values: [
          e.organization_id,
          `rev_${e.idempotency_key}`,
          e.wallet_id,
          `reversal:${e.reference}`,
          e.protocol,
          e.id
        ]
      } as any)
    ).rows[0] as any;
    compId = comp.id;
  } catch (err: any) {
    if (err.code === "23505") {
      return { reverted: false, reason: "ALREADY_REVERSED" };
    }
    throw err;
  }

  const lines = (
    await tx.execute({
      text: "SELECT * FROM journal_lines WHERE journal_entry_id=$1",
      values: [journalEntryId]
    } as any)
  ).rows as any[];

  for (const line of lines) {
    const revSide = line.side === "CR" ? "DR" : "CR";
    await tx.execute({
      text: "INSERT INTO journal_lines(journal_entry_id, account_id, side, amount, currency) VALUES($1, $2, $3, $4, $5)",
      values: [compId, line.account_id, revSide, line.amount, line.currency]
    } as any);

    if (line.side === "CR" && line.account_id.startsWith(`asset:${line.currency.toLowerCase()}:wallet:`)) {
      await tx.execute({
        text: `UPDATE wallets
               SET balance = balance + $1::numeric,
                   monthly_spent = GREATEST(0, monthly_spent - $1::numeric)
               WHERE id = $2`,
        values: [line.amount, e.wallet_id]
      } as any);
    }
  }

  await tx.execute({
    text: "UPDATE journal_entries SET status='REVERSED' WHERE id=$1",
    values: [journalEntryId]
  } as any);

  await audit(
    tx,
    "PAYMENT_REVERSED",
    `wallet:${e.wallet_id}`,
    { originalEntryId: journalEntryId, reversalEntryId: compId, reason },
    e.organization_id
  );

  return { reverted: true, reason, reversalEntryId: compId };
}

export async function revertPayment(journalEntryId: string, reason = "SETTLEMENT_FAILED") {
  return db.transaction(async (tx) => revertPaymentTx(tx, journalEntryId, reason));
}
