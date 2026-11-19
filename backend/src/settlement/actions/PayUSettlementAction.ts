import { Action, SettlementDispatchResult } from "../Action.js";
import { PaymentRequest, Capture } from "../Request.js";
import { config } from "../../config.js";

export function toPayUSmallestUnit(numericStr: string): string {
  const cleaned = String(numericStr).trim();
  if (!/^\d+(\.\d{1,6})?$/.test(cleaned)) {
    throw new Error(`Invalid PayU decimal amount: ${numericStr}`);
  }
  const [integerPartRaw, fracRaw = ""] = cleaned.split(".");
  const fractionalTwoDigits = (fracRaw + "00").substring(0, 2);
  const minorUnits = BigInt(integerPartRaw || "0") * 100n + BigInt(fractionalTwoDigits);
  if (minorUnits <= 0n) {
    throw new Error(`PayU minor amount must be positive: ${numericStr}`);
  }
  return minorUnits.toString();
}

export const PAYU_SUPPORTED_CURRENCIES = new Set(["USD", "PLN"]);

export interface PayUOrderStatusResult {
  orderId: string;
  status: string;
  extOrderId?: string;
  totalAmount?: string;
  currencyCode?: string;
}

export class PayUSettlementAction implements Action {
  supports(request: PaymentRequest): boolean {
    return (
      request.type === "Capture" &&
      PAYU_SUPPORTED_CURRENCIES.has(String(request.payload.currency || "").trim().toUpperCase())
    );
  }

  private resolveNotifyUrl(): string {
    if (config.PAYU_NOTIFY_URL) return config.PAYU_NOTIFY_URL;
    if (config.PUBLIC_BASE_URL) {
      return `${config.PUBLIC_BASE_URL.replace(/\/+$/, "")}/v1/webhooks/payu`;
    }
    throw new Error(
      "PAYU_NOTIFY_URL or PUBLIC_BASE_URL must be configured for PayU webhook confirmation."
    );
  }

  private async fetchAccessToken(): Promise<string> {
    if (!config.PAYU_MERCHANT_POS_ID || !config.PAYU_CLIENT_ID || !config.PAYU_CLIENT_SECRET) {
      throw Object.assign(new Error("PayU credentials not configured for settlement dispatch"), {
        permanent: true
      });
    }

    const baseUrl = config.PAYU_BASE_URL.replace(/\/+$/, "");
    const authRes = await fetch(`${baseUrl}/pl/standard/user/oauth/authorize`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: config.PAYU_CLIENT_ID,
        client_secret: config.PAYU_CLIENT_SECRET
      }).toString()
    });

    if (!authRes.ok) {
      const errText = await authRes.text().catch(() => authRes.statusText);
      const err: any = new Error(`PayU Auth failed: ${authRes.status} ${errText}`);
      err.permanent = authRes.status >= 400 && authRes.status < 500 && authRes.status !== 429;
      throw err;
    }

    const authData = (await authRes.json()) as any;
    if (!authData?.access_token) {
      throw new Error("PayU OAuth response missing access_token");
    }
    return authData.access_token;
  }

  async execute(request: Capture): Promise<SettlementDispatchResult> {
    const currencyCode = String(request.payload.currency || "").trim().toUpperCase();
    if (!PAYU_SUPPORTED_CURRENCIES.has(currencyCode)) {
      throw Object.assign(
        new Error(`UNSUPPORTED_PROVIDER_CURRENCY: PayU supports USD and PLN only, received ${currencyCode}`),
        { permanent: true }
      );
    }

    const accessToken = await this.fetchAccessToken();
    const notifyUrl = this.resolveNotifyUrl();
    const baseUrl = config.PAYU_BASE_URL.replace(/\/+$/, "");

    const payuTotalAmount = toPayUSmallestUnit(request.payload.amount);

    const orderRes = await fetch(`${baseUrl}/api/v2_1/orders`, {
      method: "POST",
      redirect: "manual",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${accessToken}`
      },
      body: JSON.stringify({
        notifyUrl,
        customerIp: "127.0.0.1",
        merchantPosId: config.PAYU_MERCHANT_POS_ID,
        description: `Agent Wallet Settlement to ${request.payload.targetAddress}`,
        currencyCode,
        totalAmount: payuTotalAmount,
        extOrderId: request.payload.intentId
      })
    });

    // PayU REST API returns HTTP 201 or 302 with JSON containing orderId
    if (!orderRes.ok && orderRes.status !== 302) {
      const errText = await orderRes.text().catch(() => orderRes.statusText);
      const err: any = new Error(`PayU Order creation failed: ${orderRes.status} ${errText}`);
      err.permanent = orderRes.status >= 400 && orderRes.status < 500 && orderRes.status !== 429;
      throw err;
    }

    const body = (await orderRes.json()) as any;
    if (!body?.orderId) {
      throw new Error("PayU Order response missing orderId");
    }

    return {
      providerRef: String(body.orderId),
      asyncConfirmation: true,
      rawStatus: body?.status?.statusCode || "SUBMITTED"
    };
  }

  async retrieveOrderStatus(orderId: string): Promise<PayUOrderStatusResult> {
    const accessToken = await this.fetchAccessToken();
    const baseUrl = config.PAYU_BASE_URL.replace(/\/+$/, "");

    const res = await fetch(`${baseUrl}/api/v2_1/orders/${encodeURIComponent(orderId)}`, {
      method: "GET",
      headers: {
        Authorization: `Bearer ${accessToken}`
      }
    });

    if (!res.ok) {
      throw new Error(`PayU Order retrieve failed: ${res.status} ${res.statusText}`);
    }

    const body = (await res.json()) as any;
    const order = Array.isArray(body?.orders) ? body.orders[0] : body?.order;
    if (!order?.status) {
      throw new Error(`PayU Order retrieve missing order status for ${orderId}`);
    }

    return {
      orderId: String(order.orderId || orderId),
      status: String(order.status),
      extOrderId: order.extOrderId,
      totalAmount: order.totalAmount !== undefined ? String(order.totalAmount) : undefined,
      currencyCode: typeof order.currencyCode === "string" ? order.currencyCode.toUpperCase() : undefined
    };
  }
}
