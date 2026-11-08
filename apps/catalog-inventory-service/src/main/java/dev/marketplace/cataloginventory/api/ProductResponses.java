package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.api.ProductModels.ProductPage;
import dev.marketplace.cataloginventory.api.ProductModels.ProductResponse;
import dev.marketplace.cataloginventory.application.InventoryService;
import dev.marketplace.cataloginventory.application.Page;
import dev.marketplace.cataloginventory.domain.product.Product;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.resteasy.reactive.RestResponse;
import java.util.List;

/**
 * Monta as respostas de produto com o saldo atual dos SKUs, para o status
 * efetivo refletir {@code OUT_OF_STOCK}.
 */
@ApplicationScoped
class ProductResponses {
    private final InventoryService inventory;

    ProductResponses(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** Resposta do produto com o saldo atual dos seus SKUs. */
    Uni<ProductResponse> of(Product product) {
        return inventory.stockOf(List.of(product)).map(stock -> ProductResponse.of(product, stock));
    }

    /** Responde 200 com o produto, o saldo atual dos SKUs e o ETag da versão. */
    Uni<RestResponse<ProductResponse>> ok(Product product) {
        return of(product).map(body -> ETags.ok(body, product.version()));
    }

    /** Página de produtos com o saldo atual dos SKUs e o cursor da próxima página. */
    Uni<ProductPage> page(Page<Product> page) {
        return inventory.stockOf(page.items())
                .map(stock -> new ProductPage(page.items().stream().map(p -> ProductResponse.of(p, stock)).toList(),
                        Cursors.next(page)));
    }
}
