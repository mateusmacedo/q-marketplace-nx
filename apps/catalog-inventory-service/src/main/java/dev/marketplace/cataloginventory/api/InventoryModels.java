package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.domain.inventory.StockLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.util.UUID;

/** Contratos HTTP do saldo por SKU. */
public final class InventoryModels {
    private InventoryModels() {
    }

    public record StockResponse(UUID skuId, int onHand, int reserved, int available, long sold,
            @Schema(description = "Versão do saldo, usada no If-Match do ajuste") long version) {
        static StockResponse of(StockLevel level) {
            return new StockResponse(level.skuId(), level.onHand(), level.reserved(), level.available(), level.sold(),
                    level.version());
        }
    }

    public record AdjustStockRequest(
            @NotNull @Min(0) @Max(StockLevel.MAX_ON_HAND) @Schema(description = "Estoque físico vendável contado pelo seller") Integer onHand) {
    }
}
