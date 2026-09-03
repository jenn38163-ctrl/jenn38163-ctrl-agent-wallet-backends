import { Action } from '../Action.js';
import { PaymentRequest, Capture } from '../Request.js';
import { config } from '../../config.js';

function toPayUSmallestUnit(numericStr: string): string {
  const parts = numericStr.split('.');
  const integerPart = parts[0] || '0';
  let fractionalPart = parts[1] || '00';
  fractionalPart = (fractionalPart + '00').substring(0, 2);
  return parseInt(integerPart + fractionalPart, 10).toString();
}

export class PayUSettlementAction implements Action {
  supports(request: PaymentRequest): boolean {
    return request.type === 'Capture' && (request.payload.currency === 'PLN' || request.payload.currency === 'USD');
  }

  async execute(request: Capture): Promise<{ providerRef?: string }> {
    if (!config.PAYU_MERCHANT_POS_ID || !config.PAYU_CLIENT_ID || !config.PAYU_CLIENT_SECRET) {
      throw new Error("PayU credentials not configured for settlement dispatch");
    }

    const payuTotalAmount = toPayUSmallestUnit(request.payload.amount);
    const currencyCode = request.payload.currency === 'USD' ? 'USD' : 'PLN';

    const authRes = await fetch('https://secure.payu.com/pl/standard/user/oauth/authorize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `grant_type=client_credentials&client_id=${config.PAYU_CLIENT_ID}&client_secret=${config.PAYU_CLIENT_SECRET}`
    });
    
    if (!authRes.ok) throw new Error(`PayU Auth failed: ${authRes.statusText}`);
    const authData = await authRes.json() as any;

    const orderRes = await fetch('https://secure.payu.com/api/v2_1/orders', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${authData.access_token}`
      },
      body: JSON.stringify({
        notifyUrl: "https://your-domain.com/v1/webhooks/payu",
        customerIp: "127.0.0.1",
        merchantPosId: config.PAYU_MERCHANT_POS_ID,
        description: `Agent Wallet Settlement to ${request.payload.targetAddress}`,
        currencyCode: currencyCode,
        totalAmount: payuTotalAmount,
        extOrderId: request.payload.intentId
      })
    });

    if (!orderRes.ok) {
      const err = await orderRes.text();
      throw new Error(`PayU Order failed: ${orderRes.status} ${err}`);
    }
    
    const body = await orderRes.json() as any;
    return { providerRef: body.orderId };
  }
}
