import { Action } from '../Action.js';
import { PaymentRequest, Capture } from '../Request.js';

export class StarknetCaptureAction implements Action {
  supports(request: PaymentRequest): boolean {
    return request.type === 'Capture' && request.payload.currency === 'USDC';
  }

  async execute(request: Capture): Promise<void> {
    if (process.env.NODE_ENV === 'production' || process.env.STARKNET_ENABLED !== 'true') {
      throw new Error("STARKNET_DISABLED: Starknet blockchain settlement adapter is disabled in production and not configured.");
    }
    console.log(`[StarknetCaptureAction] Executing capture for ${request.payload.amount} ${request.payload.currency} to ${request.payload.targetAddress}`);
  }
}

