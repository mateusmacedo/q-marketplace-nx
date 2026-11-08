export const INVARIANTS = Object.freeze({
  hot_non_negative: 'SKU disputado: nenhum valor negativo',
  hot_restored: 'SKU disputado (contend): saldo de volta ao inicial (reserved = 0, sold = 0, onHand inalterado)',
  hot_on_hand_unchanged: 'SKU disputado (exhaust): onHand inalterado',
  hot_no_oversell: 'SKU disputado (exhaust): reserved + sold <= onHand inicial',
  hot_sold_out: 'SKU disputado (exhaust): estoque inteiro reservado',
  last_unit_taken: 'último item: estoque inteiro reservado (reserved = estoque, available = 0, sold = 0)',
  last_unit_no_oversell: 'último item: nenhum valor negativo e reserved + sold <= estoque',
  insufficient_stock_limited: 'recusas insufficient-stock no serviço = tentativas − estoque do último item',
  spread_non_negative: 'reserva espalhada: nenhum valor negativo',
  spread_restored: 'reserva espalhada: saldo de volta ao inicial',
  adjust_non_negative: 'ajuste: nenhum valor negativo',
  reservation_non_negative: 'ciclo de reserva: nenhum valor negativo',
  reservation_balance: 'ciclo de reserva: onHand = inicial − confirmados, sold = confirmados e nada reservado',
  reservation_sold_matches_commits: 'ciclo de reserva: unidades vendidas = 2 × confirmações contadas pelo serviço',
  expiry_released: 'expiração: saldo devolvido (reserved = 0, onHand inicial, sold = 0)',
  expiry_expired: 'expiração: reservas amostradas EXPIRED, com closedAt',
  import_completed: 'importação: COMPLETED com as contagens esperadas',
  mass_state: 'massa: estado de cada produto conforme o planejado',
  storefront_coherent: 'vitrine: 200 só para ACTIVE e OUT_OF_STOCK, com o status e o estoque certos; 404 nos demais',
  kyb_final: 'KYB: situação final de cada loja sintética = último veredito enviado',
});

// Operações de catalog.inventory.update.latency que a carga exercita.
export const SERVICE_OPERATIONS = [
  'reserve', 'release', 'commit', 'extend', 'adjust_stock', 'define_offer', 'schedule_promotion', 'cancel_promotion',
];

// Desfechos de negócio contados por cenário, com a tag outcome da métrica outcomes.
export const OUTCOMES = Object.freeze({
  storefront_visible: 'vitrine: produto visível (200)',
  storefront_hidden: 'vitrine: produto oculto (404)',
  product_approved: 'ciclo do produto: aprovado, pausado e retomado',
  product_rejected: 'ciclo do produto: recusado pela moderação',
  product_archived: 'ciclo do produto: arquivado',
  moderation_blocked: 'moderação: bloqueio',
  moderation_unblocked: 'moderação: desbloqueio',
  promotion_cancelled: 'preço: oferta trocada, promoção agendada e cancelada',
  reservation_committed: 'ciclo de reserva: confirmada',
  reservation_released: 'ciclo de reserva: liberada',
  reservation_replayed: 'ciclo de reserva: repetição idempotente (200)',
  reservation_expiring: 'expiração: reserva curta criada',
  category_moved: 'categorias: criada, revisada e movida',
  import_completed: 'importação: concluída',
});
