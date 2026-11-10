package dev.marketplace.cataloginventory.domain.category;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.requireText;

/**
 * Atributo exigido ou aceito por uma categoria. {@code variant} indica que o
 * valor pertence a cada SKU
 * (ex.: cor, tamanho); os demais pertencem ao produto.
 */
public record AttributeDefinition(String code, String label, AttributeType type, boolean required, boolean variant,
    List<String> options) {
  public static final int MAX_OPTIONS = 100;
  public static final int MAX_TEXT_LENGTH = 500;
  public static final int MAX_LABEL_LENGTH = 80;
  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,39}");
  // WHY: os números são conferidos só por regex, sem conversão: new BigDecimal tem custo quadrático no tamanho
  // da entrada e aceita expoente, como 1E999999999.
  private static final Pattern INTEGER_FORMAT = Pattern.compile("-?\\d{1,18}");
  private static final Pattern DECIMAL_FORMAT = Pattern.compile("-?\\d{1,18}(\\.\\d{1,6})?");

  /**
   * Copia {@code options} de forma imutável. Recusa com {@code invalid-value}
   * código fora de {@code [a-z][a-z0-9_]{0,39}}, rótulo vazio ou longo, tipo
   * ausente, {@code OPTION} sem 1 a {@link #MAX_OPTIONS} opções distintas e
   * opções em outro tipo.
   */
  public AttributeDefinition {
    if (code == null || !CODE.matcher(code).matches())
      throw invalid("Código de atributo deve ter letras minúsculas, dígitos ou '_' e começar por letra (até 40)");
    label = requireText(label, MAX_LABEL_LENGTH,
        "Rótulo do atributo " + code + " é obrigatório e tem até " + MAX_LABEL_LENGTH + " caracteres");
    if (type == null)
      throw invalid("Tipo do atributo " + code + " é obrigatório");
    options = options == null ? List.of() : List.copyOf(options);
    if (type == AttributeType.OPTION) {
      if (options.isEmpty() || options.size() > MAX_OPTIONS)
        throw invalid("Atributo " + code + " do tipo OPTION exige de 1 a " + MAX_OPTIONS + " opções");
      if (options.stream().anyMatch(o -> o == null || o.isBlank() || o.length() > MAX_LABEL_LENGTH))
        throw invalid("Opções do atributo " + code + " não podem ser vazias nem passar de " + MAX_LABEL_LENGTH
            + " caracteres");
      if (new HashSet<>(options).size() != options.size())
        throw invalid("Opções do atributo " + code + " devem ser distintas");
    } else if (!options.isEmpty()) {
      throw invalid("Somente atributos do tipo OPTION aceitam opções (" + code + ")");
    }
  }

  /**
   * Indica se o valor textual é aceito pelo tipo do atributo. Números ficam em
   * notação simples, com ponto: {@code INTEGER} com até 18 dígitos e
   * {@code DECIMAL} com até 18 dígitos inteiros e 6 decimais.
   */
  public boolean accepts(String value) {
    if (value == null || value.isBlank())
      return false;
    return switch (type) {
      case TEXT -> value.length() <= MAX_TEXT_LENGTH;
      case INTEGER -> INTEGER_FORMAT.matcher(value).matches();
      case DECIMAL -> DECIMAL_FORMAT.matcher(value).matches();
      case BOOLEAN -> value.equals("true") || value.equals("false");
      case OPTION -> options.contains(value);
    };
  }
}
