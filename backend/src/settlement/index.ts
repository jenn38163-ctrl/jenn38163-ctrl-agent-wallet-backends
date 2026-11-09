import { Gateway } from './Gateway.js';
import { StarknetCaptureAction } from './actions/StarknetCaptureAction.js';
import { PayUSettlementAction } from './actions/PayUSettlementAction.js';
import { PimlicoSettlementAction } from './actions/PimlicoSettlementAction.js';
import { PaymentRequest, Capture } from './Request.js';

export { PaymentRequest, Capture };

export const gateway = new Gateway();
gateway.addAction('STARKNET', new StarknetCaptureAction());
gateway.addAction('PAYU', new PayUSettlementAction());
gateway.addAction('PIMLICO', new PimlicoSettlementAction());

