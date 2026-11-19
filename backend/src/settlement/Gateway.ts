import { Action, SettlementDispatchResult } from "./Action.js";
import { PaymentRequest } from "./Request.js";

export class Gateway {
  private actions: Map<string, Action> = new Map();

  public addAction(providerName: string, action: Action) {
    this.actions.set(providerName.toUpperCase(), action);
  }

  public getAction(providerName: string): Action | undefined {
    return this.actions.get(providerName.toUpperCase());
  }

  public async execute(
    providerName: string,
    request: PaymentRequest
  ): Promise<SettlementDispatchResult | void> {
    const key = providerName.toUpperCase();
    const action = this.actions.get(key);
    if (!action) {
      throw new Error(`Provider not found: ${providerName}`);
    }
    if (!action.supports(request)) {
      throw new Error(`Provider ${providerName} does not support request type: ${request.type}`);
    }
    return await action.execute(request);
  }
}
