import { parseParams } from './params.js';
import { planFor } from './profiles.js';

export const params = parseParams(__ENV);
export const plan = planFor(params);
