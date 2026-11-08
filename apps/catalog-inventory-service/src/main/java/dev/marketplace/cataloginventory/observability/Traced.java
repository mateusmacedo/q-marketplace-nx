package dev.marketplace.cataloginventory.observability;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cada método da classe que devolve {@code Uni} vira um span {@code Classe.método}, filho do span ativo (ADR 021).
 * {@code entry = true} marca a porta de entrada (resource HTTP, consumidor Kafka), cujo span fica ativo no contexto
 * da requisição ({@link Lifecycle#entry}); nos demais, os adaptadores, cada chamada roda num contexto aninhado
 * ({@link Lifecycle#nested}). Métodos que não devolvem {@code Uni} passam direto.
 */
@Inherited
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface Traced {
    @Nonbinding
    boolean entry() default false;
}
