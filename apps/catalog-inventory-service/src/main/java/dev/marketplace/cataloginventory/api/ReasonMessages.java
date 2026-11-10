package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.domain.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Textos em português dos códigos de recusa, lidos de
 * {@value #BUNDLE}. A chave {@code <código>.<n>} vale para o motivo com
 * {@code n} parâmetros ({0}, {1}...); a chave {@code <código>}, sem
 * parâmetros, vale para os demais casos.
 */
final class ReasonMessages {
  static final String BUNDLE = "i18n/ReasonMessages.properties";
  private static final Locale PT_BR = Locale.of("pt", "BR");
  private static final ResourceBundle MESSAGES = load();

  private ReasonMessages() {
  }

  /**
   * O texto para mostrar: o do motivo, quando quem recusou o escreveu; senão,
   * o do catálogo com os parâmetros; sem chave no catálogo, {@code fallback}.
   */
  static String resolve(Reason reason, String fallback) {
    if (reason.message() != null && !reason.message().isBlank())
      return reason.message();
    return format(reason.code(), reason.params()).orElse(fallback);
  }

  static Optional<String> format(String code, List<String> params) {
    String specific = code + "." + params.size();
    if (MESSAGES.containsKey(specific))
      return Optional.of(new MessageFormat(MESSAGES.getString(specific), PT_BR).format(params.toArray()));
    return MESSAGES.containsKey(code) ? Optional.of(MESSAGES.getString(code)) : Optional.empty();
  }

  static Set<String> keys() {
    return MESSAGES.keySet();
  }

  static Problem.ReasonDetail detail(Reason reason, String fallback) {
    return new Problem.ReasonDetail(reason.code(), reason.params(), resolve(reason, fallback));
  }

  /**
   * Lista dos motivos com o texto resolvido; vazia vira nula, para o campo
   * ficar fora do corpo.
   */
  static List<Problem.ReasonDetail> details(List<Reason> reasons, String fallback) {
    if (reasons == null || reasons.isEmpty())
      return null;
    return reasons.stream().map(reason -> detail(reason, fallback)).toList();
  }

  // WHY: o arquivo é lido como recurso comum, sem ResourceBundle.getBundle, porque a imagem nativa só embute
  // bundles registrados; o recurso entra por quarkus.native.resources.includes.
  private static ResourceBundle load() {
    try (InputStream in = ReasonMessages.class.getClassLoader().getResourceAsStream(BUNDLE)) {
      if (in == null)
        throw new IllegalStateException("Catálogo de mensagens ausente: " + BUNDLE);
      return new PropertyResourceBundle(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
