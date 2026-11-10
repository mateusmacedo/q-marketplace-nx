package dev.marketplace.cataloginventory.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.marketplace.cataloginventory.api.CategoryModels.CreateCategoryRequest;
import dev.marketplace.cataloginventory.api.ModerationModels.DecisionRequest;
import dev.marketplace.cataloginventory.api.ProductModels.CreateProductRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Recusa do caractere nulo na leitura do JSON e o caminho do campo no formato da validação. */
class NullCharacterGuardTest {
  static final String NULL = "\\u0000";
  static final ObjectMapper JSON = guarded();

  static ObjectMapper guarded() {
    ObjectMapper mapper = new ObjectMapper();
    new NullCharacterGuard().customize(mapper);
    return mapper;
  }

  static String product(String title, String attributes, String variant) {
    return """
        {"categoryId":"6f1c2a52-6d5e-4a3b-9c7e-2f2b1d3c4e5f","currency":"BRL","title":"%s","attributes":%s,
         "skus":[{"sellerSku":"CAM-P","variantAttributes":{"tamanho":"P"}},
                 {"sellerSku":"CAM-M","variantAttributes":{"tamanho":"%s"}}]}""".formatted(title, attributes,
        variant);
  }

  static String refused(String json, Class<?> type) {
    MismatchedInputException refusal = assertThrows(MismatchedInputException.class, () -> JSON.readValue(json, type));
    return NullCharacterGuard.rejectedField(refusal).orElseThrow();
  }

  @Test
  void theFieldPathFollowsTheValidationFormatAtEveryLevel() {
    assertEquals("title", refused(product("Camiseta" + NULL, "{}", "M"), CreateProductRequest.class));
    assertEquals("attributes[marca]",
        refused(product("Camiseta", "{\"marca\":\"Ac" + NULL + "me\"}", "M"), CreateProductRequest.class));
    assertEquals("skus[1].variantAttributes[tamanho]",
        refused(product("Camiseta", "{}", "M" + NULL), CreateProductRequest.class));
    assertEquals("attributes[1].options[0]", refused("""
        {"code":"camisetas","name":"Camisetas","attributes":[
          {"code":"marca","label":"Marca","type":"TEXT"},
          {"code":"tamanho","label":"Tamanho","type":"OPTION","options":["P%s"]}]}""".formatted(NULL),
        CreateCategoryRequest.class));
    assertEquals("reasonCodes[0]",
        refused("{\"reasonCodes\":[\"spam" + NULL + "\"]}", DecisionRequest.class));
  }

  @Test
  void aRefusedMapKeyStaysOutOfThePath() {
    assertEquals("attributes",
        refused(product("Camiseta", "{\"mar" + NULL + "ca\":\"Acme\"}", "M"), CreateProductRequest.class));
  }

  @Test
  void otherCharactersPassUntouched() throws Exception {
    CreateProductRequest read = JSON.readValue(product("Camiseta \\u0001 é ☕", "{\"marca\":\"Acme\"}", "M"),
        CreateProductRequest.class);
    assertEquals("Camiseta \u0001 é ☕", read.title());
    assertEquals(Map.of("marca", "Acme"), read.attributes());
    assertEquals(List.of("P", "M"), read.skus().stream().map(s -> s.variantAttributes().get("tamanho")).toList());
  }
}
