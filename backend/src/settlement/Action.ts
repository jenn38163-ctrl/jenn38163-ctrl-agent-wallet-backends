import { PaymentRequest } from "./Request.js";

export interface SettlementDispatchResult {
  providerRef?: string;
  asyncConfirmation?: boolean;
  rawStatus?: string;
}

export interface Action {
  execute(request: PaymentRequest): Promise<SettlementDispatchResult | void>;
  supports(request: PaymentRequest): boolean;
}
