package dev.marketplace.cataloginventory.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marketplace.cataloginventory.application.ModerationReasonEntry;
import dev.marketplace.cataloginventory.application.ModerationReasonRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Catálogo de motivos de moderação em cache (ADR 024), por tenant, dependente da geração dos motivos do tenant, que
 * {@link ModerationReasonCacheInvalidation} incrementa a cada motivo criado, revisado, ativado ou desativado. Cada
 * decisão de moderação com motivo lê o catálogo, e o motivo desativado deixa de valer na leitura seguinte à escrita.
 */
@ApplicationScoped
public class ModerationReasonCache {
  private static final Logger LOG = Logger.getLogger(ModerationReasonCache.class);
  private final CatalogCache cache;
  private final ObjectMapper json;

  ModerationReasonCache(CatalogCache cache, ObjectMapper json) {
    this.cache = cache;
    this.json = json;
  }

  /**
   * Porta com {@code catalog} pelo cache; a busca por código, que confere a versão antes de gravar, e as escritas
   * vão direto a {@code reasons}. Com o cache desligado, devolve {@code reasons}.
   */
  public ModerationReasonRepository over(ModerationReasonRepository reasons) {
    return cache.enabled() ? new Cached(reasons) : reasons;
  }

  private final class Cached implements ModerationReasonRepository {
    private final ModerationReasonRepository reasons;

    Cached(ModerationReasonRepository reasons) {
      this.reasons = reasons;
    }

    @Override
    public Uni<List<ModerationReasonEntry>> catalog(UUID tenantId) {
      String key = CacheKeys.moderationReasons(tenantId);
      return cache.read(CatalogCache.MODERATION_REASONS, key, CacheKeys.moderationReasonsGeneration(tenantId),
          null, null)
          .flatMap(lookup -> switch (lookup.result()) {
            case HIT -> decode(lookup.content(), tenantId);
            case MISS -> reasons.catalog(tenantId).invoke(found -> store(key, lookup.content(), found));
            case UNMAPPED, BYPASS -> reasons.catalog(tenantId);
          });
    }

    @Override
    public Uni<Optional<ModerationReasonEntry>> findByCode(UUID tenantId, String code) {
      return reasons.findByCode(tenantId, code);
    }

    @Override
    public Uni<Void> insert(ModerationReasonEntry reason) {
      return reasons.insert(reason);
    }

    @Override
    public Uni<Void> update(ModerationReasonEntry reason, long expectedVersion) {
      return reasons.update(reason, expectedVersion);
    }

    private Uni<List<ModerationReasonEntry>> decode(String content, UUID tenantId) {
      try {
        return Uni.createFrom().item(json.readValue(content, Snapshots.ModerationReasons.class).restore());
      } catch (JsonProcessingException | RuntimeException e) {
        LOG.debugf(e, "Retrato dos motivos de moderação ilegível; a consulta vai ao banco");
        return reasons.catalog(tenantId);
      }
    }

    /** A resposta não espera a gravação ({@link CatalogCache#detach}). */
    private void store(String key, String stamp, List<ModerationReasonEntry> found) {
      try {
        CatalogCache.detach(cache.write(CatalogCache.MODERATION_REASONS, key, stamp,
            json.writeValueAsString(Snapshots.ModerationReasons.of(found))));
      } catch (JsonProcessingException e) {
        LOG.debugf(e, "Retrato dos motivos de moderação não serializado; nada é guardado");
      }
    }
  }
}
