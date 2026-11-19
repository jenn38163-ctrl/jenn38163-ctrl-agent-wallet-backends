import { Action, SettlementDispatchResult } from "../Action.js";
import { PaymentRequest, Capture } from "../Request.js";

export class PimlicoSettlementAction implements Action {
  supports(request: PaymentRequest): boolean {
    return (
      request.type === "Capture" &&
      (request.payload.currency === "ETH" ||
        request.payload.currency === "USDC" ||
        request.payload.currency === "PIMLICO")
    );
  }

  async execute(_request: Capture): Promise<SettlementDispatchResult> {
    throw Object.assign(
      new Error(
        "BLOCKCHAIN_SETTLEMENT_DISABLED: ERC-4337 Pimlico settlement is disabled until real UserOperation submission and on-chain confirmation verification are implemented."
      ),
      { permanent: true }
    );
  }
}
