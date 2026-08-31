import { Action } from '../Action.js';
import { PaymentRequest, Capture } from '../Request.js';

export class StarknetCaptureAction implements Action {
  supports(request: PaymentRequest): boolean {
    return request.type === 'Capture' && request.payload.currency === 'USDC';
  }

  async execute(request: Capture): Promise<void> {
    console.log(`[StarknetCaptureAction] Executing capture for ${request.payload.amount} ${request.payload.currency} to ${request.payload.targetAddress}`);
    // Real Starknet.js logic goes here
  }
}
