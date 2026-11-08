package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.StatusChange;
import io.smallrye.mutiny.Uni;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persiste o agregado inteiro (produto, SKUs e histórico de publicação) numa
 * única transação.
 */
public interface ProductRepository {
    /**
     * Agregado completo (com SKUs) do tenant; vazio quando o id não existe
     * nele, inclusive quando pertence a outro tenant.
     */
    Uni<Optional<Product>> findById(UUID tenantId, UUID productId);

    /** Produto dono do SKU; rotas de oferta e estoque chegam pelo SKU. */
    Uni<Optional<Product>> findBySku(UUID tenantId, UUID skuId);

    /**
     * Ids dos SKUs da loja pelo código do seller; código sem SKU na loja fica
     * fora do mapa, e coleção vazia resulta em mapa vazio.
     */
    Uni<Map<String, UUID>> skuIdsBySellerSku(UUID tenantId, UUID storeId, Collection<String> sellerSkus);

    /**
     * Produtos do tenant em ordem de id, filtrados por loja e estado quando
     * informados. {@code afterId} é o cursor (exclusivo) e {@code limit} o
     * máximo de itens devolvidos; os casos de uso pedem um a mais para saber se
     * há próxima página.
     */
    Uni<List<Product>> find(UUID tenantId, Optional<UUID> storeId, Optional<ProductStatus> status,
            Optional<String> afterId, int limit);

    /**
     * Grava o produto novo com SKUs, saldo zerado por SKU, histórico e eventos na
     * outbox. Falha com {@link DuplicateException} se um SKU já existe na loja.
     */
    Uni<Void> insert(Product product);

    /**
     * Grava o estado alterado (SKUs incluídos e removidos, histórico e eventos
     * na outbox) se a versão persistida for {@code expectedVersion}; senão lança
     * {@link ConcurrentUpdateException}. SKU removido leva a oferta dele junto;
     * com saldo: {@code sku-has-stock}.
     */
    Uni<Void> update(Product product, long expectedVersion);

    /**
     * Mudanças de estado do produto, da mais antiga para a mais recente; lista
     * vazia quando o id não existe no tenant.
     */
    Uni<List<StatusChange>> history(UUID tenantId, UUID productId);
}
