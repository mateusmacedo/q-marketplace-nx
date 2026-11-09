package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.StatusChange;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
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

    /** Último item visto na fila de moderação: entrada na fila e id, que desempata. */
    record QueuePosition(Instant queuedAt, UUID id) {
    }

    /**
     * Até {@code limit} produtos do tenant em {@code PENDING_MODERATION}, do
     * que entrou na fila há mais tempo para o mais recente, com o id como
     * desempate, depois de {@code after} (exclusivo).
     */
    Uni<List<Product>> moderationQueue(UUID tenantId, Optional<QueuePosition> after, int limit);

    /**
     * Até {@code limit} produtos do tenant que atendem {@code query}, na ordem
     * dela, depois da posição (exclusiva). O texto casa como trecho do título
     * normalizado ou do código do seller de um SKU; o estado é o efetivo.
     */
    Uni<List<Product>> search(UUID tenantId, ProductQuery query, int limit);

    /**
     * Última mudança de estado de cada produto informado; produto sem histórico
     * no tenant fica fora do mapa, e coleção vazia resulta em mapa vazio.
     */
    Uni<Map<UUID, StatusChange>> lastChanges(UUID tenantId, Collection<UUID> productIds);

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
