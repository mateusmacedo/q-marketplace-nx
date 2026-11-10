package dev.marketplace.cataloginventory.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Recusa o caractere nulo (U+0000) em cada texto lido de JSON, valor ou chave de mapa, em qualquer nível do corpo,
 * antes da validação de campo. O PostgreSQL não guarda esse caractere em {@code text} e recusaria o parâmetro com
 * erro de servidor (SQLSTATE 22021); a recusa sai como 400 {@code validation-failed}, com o caminho do campo em
 * {@code errors} ({@link ProblemMappers#mismatchedBody}).
 */
@ApplicationScoped
public class NullCharacterGuard implements ObjectMapperCustomizer {
  static final String MESSAGE = "não pode ter o caractere nulo (U+0000)";
  private static final String REJECTION = "Texto com o caractere nulo (U+0000)";
  private static final char NULL = '\u0000';

  @Override
  public void customize(ObjectMapper mapper) {
    mapper.registerModule(new SimpleModule("null-character-guard")
        .addDeserializer(String.class, new Text())
        .addKeyDeserializer(String.class, new Key()));
  }

  /**
   * Caminho do campo recusado por ter o caractere nulo, no formato da validação de campo, como {@code title},
   * {@code skus[0].sellerSku} e {@code attributes[cor]}; a chave de mapa recusada fica fora, e o caminho para no mapa
   * dela. Vazio quando a exceção é outra recusa de leitura do JSON.
   */
  static Optional<String> rejectedField(MismatchedInputException e) {
    if (!REJECTION.equals(e.getOriginalMessage()))
      return Optional.empty();
    StringBuilder field = new StringBuilder();
    for (Reference step : e.getPath()) {
      if (step.getIndex() >= 0)
        field.append('[').append(step.getIndex()).append(']');
      else if (step.getFieldName() == null || step.getFieldName().indexOf(NULL) >= 0)
        break;
      else if (step.getFrom() instanceof Map<?, ?>)
        field.append('[').append(step.getFieldName()).append(']');
      else
        field.append(field.isEmpty() ? "" : ".").append(step.getFieldName());
    }
    return Optional.of(field.toString());
  }

  /**
   * Recusa do texto com o caractere nulo. WHY: é a exceção do próprio Jackson, que acrescenta o caminho a cada nível
   * que ela atravessa e que o leitor do Quarkus REST repassa sem embrulhar; {@link #rejectedField} a reconhece pela
   * mensagem.
   */
  private static MismatchedInputException rejection(JsonParser parser) {
    return MismatchedInputException.from(parser, String.class, REJECTION);
  }

  private static final class Text extends StringDeserializer {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
      String value = super.deserialize(parser, context);
      if (value != null && value.indexOf(NULL) >= 0)
        throw rejection(parser);
      return value;
    }
  }

  private static final class Key extends KeyDeserializer {
    @Override
    public Object deserializeKey(String key, DeserializationContext context) throws IOException {
      if (key.indexOf(NULL) >= 0)
        throw rejection(context.getParser());
      return key;
    }
  }
}
