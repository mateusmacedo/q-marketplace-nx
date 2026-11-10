package dev.marketplace.cataloginventory.application;

/**
 * Outra gravação alterou o agregado entre a leitura e a escrita; o cliente deve
 * reler e repetir.
 */
public class ConcurrentUpdateException extends RuntimeException {
  public ConcurrentUpdateException() {
    super("O recurso foi alterado por outra operação; releia e tente novamente");
  }
}
