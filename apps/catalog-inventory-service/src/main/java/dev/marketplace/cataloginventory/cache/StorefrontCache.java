package dev.marketplace.cataloginventory.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marketplace.cataloginventory.application.StorefrontService.StorefrontProduct;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Vitrine em cache (ADR 024): guarda o produto visível com as ofertas, nunca o estoque, que o resource lê do banco a
 * cada chamada, nem o preço vigente, calculado a cada leitura a partir das promoções guardadas. O conteúdo depende da
 * geração do produto (escritas no produto e nas ofertas) e da geração da loja (vereditos KYB/KYC); produto fora da
 * vitrine não entra no cache, então o 404 é sempre reavaliado no banco.
 */
@ApplicationScoped
public class StorefrontCache {
    private static final Logger LOG = Logger.getLogger(StorefrontCache.class);
    private final CatalogCache cache;
    private final ObjectMapper json;

    StorefrontCache(CatalogCache cache, ObjectMapper json) {
        this.cache = cache;
        this.json = json;
    }

    /**
     * Produto da vitrine pelo cache, ou por {@code load} quando falta, está velho, o Redis falha ou o cache está
     * desligado; {@code load} carregado depois de uma falta é guardado sem que a resposta espere a gravação. As falhas
     * de {@code load} (inclusive o 404) passam adiante sem nada guardar.
     */
    public Uni<StorefrontProduct> get(UUID tenantId, UUID productId, Supplier<Uni<StorefrontProduct>> load) {
        if (!cache.enabled())
            return load.get();
        String key = CacheKeys.storefront(tenantId, productId);
        return cache.read(CatalogCache.STOREFRONT, key, CacheKeys.productGeneration(tenantId, productId),
                CacheKeys.productStore(tenantId, productId), CacheKeys.storeGenerationPrefix(tenantId))
                .flatMap(lookup -> switch (lookup.result()) {
                    case HIT -> decode(lookup.content(), load);
                    case MISS -> load.get().invoke(shown -> store(key, lookup.content(), shown));
                    // WHY: a geração da loja só vale se lida antes da carga; sem o mapa, ela não foi lida.
                    case UNMAPPED -> load.get().invoke(shown -> CatalogCache
                            .detach(cache.remember(tenantId, productId, shown.product().storeId())));
                    case BYPASS -> load.get();
                });
    }

    private Uni<StorefrontProduct> decode(String content, Supplier<Uni<StorefrontProduct>> load) {
        try {
            return Uni.createFrom().item(json.readValue(content, Snapshots.Storefront.class).restore());
        } catch (JsonProcessingException | RuntimeException e) {
            LOG.debugf(e, "Retrato da vitrine ilegível; a consulta vai ao banco");
            return load.get();
        }
    }

    private void store(String key, String stamp, StorefrontProduct shown) {
        try {
            CatalogCache.detach(cache.write(CatalogCache.STOREFRONT, key, stamp,
                    json.writeValueAsString(Snapshots.Storefront.of(shown))));
        } catch (JsonProcessingException e) {
            LOG.debugf(e, "Retrato da vitrine não serializado; nada é guardado");
        }
    }
}
