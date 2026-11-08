package dev.marketplace.cataloginventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Impede divergência entre o código e o contrato versionado.
 * Para regenerar após mudança intencional:
 * {@code ./gradlew :catalog-inventory-service:test -Dcontract.update=true}.
 */
@QuarkusTest
class OpenApiContractTest {
    @Test
    void generatedSpecMatchesVersionedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode generated = mapper.readTree(given().queryParam("format", "json").get("/q/openapi")
                .then().statusCode(200).extract().asString());
        Path contract = Path.of(System.getProperty("contract.file"));
        if (Boolean.getBoolean("contract.update")) {
            Files.writeString(contract, mapper.writeValueAsString(generated) + "\n");
        }
        assertEquals(mapper.readTree(Files.readString(contract)), generated,
                "OpenAPI gerada diverge de " + contract
                        + "; rode o teste com -Dcontract.update=true se a mudança for intencional");
    }
}
