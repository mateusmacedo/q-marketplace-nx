package dev.marketplace.cataloginventory.domain;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Motivo de uma recusa: código estável, valores do caso na ordem do texto (SKU,
 * atributo, limite vigente) e, quando quem recusa sabe mais que os parâmetros
 * (o rótulo do atributo), o texto pronto; sem ele, vale o texto do código.
 */
public record Reason(String code, List<String> params, String message) implements Serializable {
  public Reason {
    Objects.requireNonNull(code, "code");
    params = List.copyOf(params);
  }

  /** Motivo sem texto próprio; cada parâmetro vira texto. */
  public static Reason of(ReasonCode code, Object... params) {
    return new Reason(code.code(), Stream.of(params).map(String::valueOf).toList(), null);
  }

  /** Cópia com o texto pronto para quem lê a recusa. */
  public Reason withMessage(String message) {
    return new Reason(code, params, message);
  }

  /**
   * Forma compacta usada em {@code reasons}: o código e os parâmetros separados
   * por dois-pontos ({@code missing-variant-attribute:CAM-P:cor}).
   */
  public String key() {
    return Stream.concat(Stream.of(code), params.stream()).collect(Collectors.joining(":"));
  }
}
