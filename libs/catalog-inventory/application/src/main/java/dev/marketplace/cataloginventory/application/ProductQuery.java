package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Critérios da listagem de produtos: loja, estado efetivo como a resposta o
 * mostra (ADR 012), texto normalizado ({@link ProductText}), ordem e a posição
 * depois da qual a página começa.
 */
public record ProductQuery(Optional<UUID> storeId, Optional<ProductStatus> status, Optional<String> text,
    ProductSort sort, Optional<Position> after) {
  /** Tamanho máximo da busca {@code q}, antes da normalização. */
  public static final int TEXT_MAX_LENGTH = 100;
  private static final String SEPARATOR = "|";

  /**
   * Último item visto na ordem da consulta: valor da coluna ordenada, como em
   * {@link ProductSort}, e id.
   */
  public record Position(String value, UUID id) {
  }

  /**
   * Consulta sem posição. Texto acima de {@link #TEXT_MAX_LENGTH} caracteres
   * ou com o caractere nulo (U+0000) lança {@link InvalidInputException}; em
   * branco, ou sem nada comparável depois de normalizado, não filtra.
   */
  static ProductQuery of(Optional<UUID> storeId, Optional<ProductStatus> status, Optional<String> text,
      ProductSort sort) {
    if (text.filter(t -> t.length() > TEXT_MAX_LENGTH).isPresent())
      throw new InvalidInputException("q deve ter até " + TEXT_MAX_LENGTH + " caracteres");
    // WHY: o PostgreSQL não guarda U+0000 em text e recusa o parâmetro com erro de servidor; os demais caracteres
    // de controle podem estar no título e continuam aceitos.
    if (text.filter(t -> t.indexOf('\0') >= 0).isPresent())
      throw new InvalidInputException("q não pode ter o caractere nulo (U+0000)");
    Optional<String> normalized = text.map(ProductText::normalize).filter(t -> !t.isEmpty());
    return new ProductQuery(storeId, status, normalized, sort, Optional.empty());
  }

  /**
   * A mesma consulta a partir da posição guardada no cursor. Cursor emitido
   * para outra ordem ou outros filtros (loja, estado ou texto), ou malformado,
   * lança {@link InvalidInputException}.
   */
  ProductQuery after(Optional<String> cursorKey) {
    return new ProductQuery(storeId, status, text, sort, cursorKey.map(this::position));
  }

  /**
   * Chave do cursor que continua depois de {@code last}: ordem, impressão dos
   * filtros, id e valor da coluna, que vai por último porque o título pode
   * conter o separador.
   */
  String cursorOf(Product last) {
    return String.join(SEPARATOR, sort.token(), fingerprint(), last.id().toString(), sort.keyOf(last));
  }

  private Position position(String key) {
    String[] parts = key.split("\\" + SEPARATOR, 4);
    if (parts.length != 4)
      throw new InvalidInputException("cursor inválido");
    if (!parts[0].equals(sort.token()) || !parts[1].equals(fingerprint()))
      throw new InvalidInputException("cursor emitido para outra ordem ou outra busca; recomece sem cursor");
    try {
      if (sort.column() != ProductSort.Column.TITLE)
        Instant.parse(parts[3]);
      return new Position(parts[3], UUID.fromString(parts[2]));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new InvalidInputException("cursor inválido");
    }
  }

  /**
   * Resumo de 64 bits dos filtros; liga o cursor à busca que o emitiu sem
   * expor o texto nele.
   */
  private String fingerprint() {
    String criteria = String.join("\n", storeId.map(UUID::toString).orElse(""),
        status.map(Enum::name).orElse(""), text.orElse(""));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(criteria.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível", e);
    }
  }
}
