import { Gateway } from './Gateway.js';
import { StarknetCaptureAction } from './actions/StarknetCaptureAction.js';

export const gateway = new Gateway();
gateway.addAction(new StarknetCaptureAction());

export * from './Gateway.js';
export * from './Action.js';
export * from './Request.js';
