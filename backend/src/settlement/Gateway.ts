import { Action } from './Action.js';
import { PaymentRequest } from './Request.js';

export class Gateway {
  private actions: Map<string, Action> = new Map();

  public addAction(providerName: string, action: Action) {
    this.actions.set(providerName, action);
  }

  public async execute(providerName: string, request: PaymentRequest): Promise<{ providerRef?: string }> | Promise<void> {
    const action = this.actions.get(providerName);
    if (!action) {
      throw new Error(`Provider not found: ${providerName}`);
    }
    if (!action.supports(request)) {
      throw new Error(`Provider ${providerName} does not support request type: ${request.type}`);
    }
    await action.execute(request);
  }
}
