package dev.marketplace.cataloginventory.domain.inventory;

import java.util.List;
import java.util.UUID;

/**
 * Pedido de reserva já identificado, antes de passar pela {@link ReservationPolicy}: o id novo, o tenant, a chave de
 * idempotência com o hash da requisição que a acompanha, a referência opcional do pedido e os itens na ordem pedida.
 * Não valida nada; quem decide se a reserva abre é a política.
 */
public record ReservationDraft(UUID id, UUID tenantId, String idempotencyKey, String requestHash, String orderRef,
    List<RequestedItem> items) {
}
