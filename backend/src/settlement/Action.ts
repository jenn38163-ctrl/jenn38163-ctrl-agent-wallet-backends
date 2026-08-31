import { PaymentRequest } from './Request.js';

export interface Action {
  execute(request: PaymentRequest): Promise<void>;
  supports(request: PaymentRequest): boolean;
}
