package dev.marketplace.cataloginventory.application;

/**
 * Alteração exige If-Match com a versão lida, para não sobrescrever mudanças de
 * terceiros (RFC 6585, 428).
 */
public class PreconditionRequiredException extends RuntimeException {
  public PreconditionRequiredException() {
    super("Envie o cabeçalho If-Match com o ETag da última leitura");
  }
}
