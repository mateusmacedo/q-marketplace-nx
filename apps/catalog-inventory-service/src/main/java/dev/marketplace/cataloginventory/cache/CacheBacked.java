package dev.marketplace.cataloginventory.cache;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Porta servida pelo cache, como a taxonomia de {@link TaxonomyCache} e o catálogo de motivos de
 * {@link ModerationReasonCache}; sem o qualificador, a injeção recebe o adaptador do banco.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
public @interface CacheBacked {
}
