import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

export const serverErrors = new Rate('server_errors');
export const unexpectedResponses = new Rate('unexpected_responses');
export const hotAccepted = new Counter('hot_sku_accepted');
export const hotRefused = new Counter('hot_sku_refused');
export const spreadRefused = new Counter('spread_refused');
export const lastUnitAccepted = new Counter('last_unit_accepted');
export const lastUnitRefused = new Counter('last_unit_refused');
export const inventoryInvariants = new Rate('inventory_invariants');
export const outcomes = new Counter('outcomes');
export const massSetupSeconds = new Gauge('mass_setup_seconds');

export const outboxLagMax = new Trend('outbox_publish_lag_max_seconds');
// WHY: o Gauge repete a última amostra da sonda para o painel; a Trend acima fica para o limite, que precisa de max.
export const outboxLag = new Gauge('outbox_lag_seconds');
export const outboxPending = new Gauge('outbox_pending');
export const outboxDrained = new Rate('outbox_drained');
export const outboxDrainSeconds = new Gauge('outbox_drain_seconds');

export const hotFinalOnHand = new Gauge('hot_final_on_hand');
export const hotFinalReserved = new Gauge('hot_final_reserved');
export const hotFinalSold = new Gauge('hot_final_sold');
export const serviceP99 = new Gauge('service_update_p99_seconds');
export const serviceRefused = new Gauge('service_refused_insufficient_stock');
