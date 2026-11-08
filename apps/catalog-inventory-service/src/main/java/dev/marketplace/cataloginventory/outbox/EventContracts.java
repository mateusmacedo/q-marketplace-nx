package dev.marketplace.cataloginventory.outbox;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contrato v1 dos eventos publicados, espelhado em
 * {@code contracts/catalog-inventory/events}. Valores monetários são
 * texto decimal exato. Depois do lançamento, campo novo opcional é compatível,
 * e qualquer outra mudança exige
 * {@code eventVersion} nova (ADR 015).
 */
// WHY: o Jackson serializa estes records fora do REST; na imagem nativa, sem
// registro, os componentes não são encontrados.
@RegisterForReflection
public final class EventContracts {
        public static final String SOURCE = "catalog-inventory";
        public static final int VERSION = 1;

        private EventContracts() {
        }

        public record Envelope(UUID eventId, String eventType, int eventVersion, String source, UUID tenantId,
                        String aggregateType,
                        UUID aggregateId, Instant occurredAt, String correlationId, Object data) {
        }

        public record SkuV1(UUID skuId, String sellerSku) {
        }

        public record ProductCreatedV1(UUID productId, UUID storeId, UUID categoryId, String currency, String title,
                        List<SkuV1> skus) {
        }

        /**
         * {@code visible}: o novo estado aparece na vitrine quando a loja está
         * aprovada.
         */
        public record ProductStatusChangedV1(UUID productId, UUID storeId, String transition, String from, String to,
                        String reason,
                        boolean visible) {
        }

        public record ProductBlockedV1(UUID productId, UUID storeId, String reason) {
        }

        public record PromotionV1(UUID promotionId, String salePrice, Instant startsAt, Instant endsAt) {
        }

        public record PriceChangedV1(UUID skuId, UUID productId, UUID storeId, String currency, String listPrice,
                        String salePrice,
                        long priceVersion, List<PromotionV1> promotions) {
        }

        public record StockV1(UUID skuId, UUID productId, UUID storeId, int onHand, int reserved, int available,
                        long version) {
        }

        public record InventoryUpdatedV1(StockV1 stock, String cause, UUID reservationId) {
        }

        public record ReservedItemV1(int quantity, StockV1 stock) {
        }

        public record InventoryReservedV1(UUID reservationId, String orderRef, Instant expiresAt,
                        List<ReservedItemV1> items) {
        }

        public record InventoryDepletedV1(StockV1 stock) {
        }

        /** {@code eligible}: a loja pode ter produtos ativos e visíveis. */
        public record StoreEligibilityChangedV1(UUID storeId, String previousStatus, String status, boolean eligible,
                        Instant decidedAt) {
        }
}
