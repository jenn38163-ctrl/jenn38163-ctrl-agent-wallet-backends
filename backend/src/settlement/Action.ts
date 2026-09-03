import { PaymentRequest } from './Request.js';

export interface Action {
  execute(request: PaymentRequest): Promise<{ providerRef?: string }> | Promise<void>;
  supports(request: PaymentRequest): boolean;
}
