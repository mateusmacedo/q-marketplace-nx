package dev.marketplace.cataloginventory.observability;

/**
 * Marca exceções do serviço que viram resposta 4xx, para que {@link Lifecycle}
 * não pinte o trace de erro. WHY: a marca evita que {@code observability} importe
 * as exceções de cada pacote, o que criava ciclos entre os pacotes.
 */
public interface Refusal {
}
