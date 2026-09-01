import { Action } from '../Action.js';
import { PaymentRequest, Capture } from '../Request.js';
import { config } from '../../config.js';

function toPayUSmallestUnit(numericStr: string): string {
  // Converts exactly from PostgreSQL numeric to integer cents/units without floating point
  // "15.000000" -> "1500"
  const parts = numericStr.split('.');
  const integerPart = parts[0] || '0';
  let fractionalPart = parts[1] || '00';
  fractionalPart = (fractionalPart + '00').substring(0, 2);
  return parseInt(integerPart + fractionalPart, 10).toString();
}

export class PayUSettlementAction implements Action {
  supports(request: PaymentRequest): boolean {
    // We bind this adapter strictly to Capture operations intended for PayU fiat settlement
    return request.type === 'Capture' && (request.payload.currency === 'PLN' || request.payload.currency === 'USDC');
  }

  async execute(request: Capture): Promise<void> {
    if (!config.PAYU_MERCHANT_POS_ID || !config.PAYU_CLIENT_ID || !config.PAYU_CLIENT_SECRET) {
      throw new Error("PayU credentials not configured for settlement dispatch");
    }

    const payuTotalAmount = toPayUSmallestUnit(request.payload.amount);
    
    // 1. Authenticate with PayU (OAuth2 Client Credentials)
    const authRes = await fetch('https://secure.payu.com/pl/standard/user/oauth/authorize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `grant_type=client_credentials&client_id=${config.PAYU_CLIENT_ID}&client_secret=${config.PAYU_CLIENT_SECRET}`
    });
    if (!authRes.ok) throw new Error(`PayU Auth failed: ${authRes.statusText}`);
    const authData = await authRes.json() as any;

    // 2. Submit Settlement Order
    const orderRes = await fetch('https://secure.payu.com/api/v2_1/orders', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${authData.access_token}`
      },
      body: JSON.stringify({
        notifyUrl: "https://your-domain.com/v1/webhooks/payu", // To be templated by environment later
        customerIp: "127.0.0.1",
        merchantPosId: config.PAYU_MERCHANT_POS_ID,
        description: `Agent Wallet Settlement to ${request.payload.targetAddress}`,
        currencyCode: "PLN", // PayU test sandbox operates strictly in local fiat boundaries usually, but parameterized for prod
        totalAmount: payuTotalAmount,
        extOrderId: request.payload.intentId // CRITICAL: Idempotency check bound to Ledger Journal Entry ID
      })
    });

    if (!orderRes.ok) {
      const err = await orderRes.text();
      throw new Error(`PayU Order failed: ${orderRes.status} ${err}`);
    }

    // On success, we DO NOT mark the database as confirmed here.
    // It remains in SETTLEMENT_SUBMITTED until the webhook verifies provider status.
  }
}
