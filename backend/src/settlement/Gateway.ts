import { Action } from './Action.js';
import { PaymentRequest } from './Request.js';

export class Gateway {
  private actions: Action[] = [];

  public addAction(action: Action) {
    this.actions.push(action);
  }

  public async execute(request: PaymentRequest): Promise<void> {
    const action = this.actions.find(a => a.supports(request));
    if (!action) {
      throw new Error(`No action found that supports request type: ${request.type}`);
    }
    await action.execute(request);
  }
}
