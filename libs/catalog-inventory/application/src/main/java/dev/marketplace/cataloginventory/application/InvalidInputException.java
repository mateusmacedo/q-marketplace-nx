package dev.marketplace.cataloginventory.application;

/**
 * Entrada rejeitada por regra de negócio; a mensagem é segura para exibir ao
 * cliente.
 */
public class InvalidInputException extends RuntimeException {
  public InvalidInputException(String message) {
    super(message);
  }
}
