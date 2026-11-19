import { Action, SettlementDispatchResult } from "../Action.js";
import { PaymentRequest, Capture } from "../Request.js";

export class StarknetCaptureAction implements Action {
  supports(request: PaymentRequest): boolean {
    return request.type === "Capture" && request.payload.currency === "USDC";
  }

  async execute(_request: Capture): Promise<SettlementDispatchResult> {
    throw Object.assign(
      new Error(
        "BLOCKCHAIN_SETTLEMENT_DISABLED: Starknet settlement is disabled until real transaction submission and on-chain confirmation verification are implemented."
      ),
      { permanent: true }
    );
  }
}
