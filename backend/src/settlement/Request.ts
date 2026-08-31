export interface PaymentRequest {
  type: string;
  payload: any;
}

export class Capture implements PaymentRequest {
  public type = 'Capture';
  constructor(public payload: { intentId: string, amount: string, currency: string, targetAddress: string }) {}
}
