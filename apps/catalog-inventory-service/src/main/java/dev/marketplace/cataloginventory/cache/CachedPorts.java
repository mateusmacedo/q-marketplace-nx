package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.application.ModerationReasonRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Portas de leitura em cache que os casos de uso recebem com {@link CacheBacked}: a de categorias passa pela
 * {@link TaxonomyCache} e a de motivos, pelo {@link ModerationReasonCache}. As invalidações ficam nos decorators das
 * portas do banco.
 */
public class CachedPorts {
  @Produces
  @ApplicationScoped
  @CacheBacked
  CategoryRepository categories(CategoryRepository categories, TaxonomyCache taxonomy) {
    return taxonomy.over(categories);
  }

  @Produces
  @ApplicationScoped
  @CacheBacked
  ModerationReasonRepository moderationReasons(ModerationReasonRepository reasons, ModerationReasonCache cache) {
    return cache.over(reasons);
  }
}
