package dev.marketplace.cataloginventory.api;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import java.util.Map;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;

/**
 * Traduz para PT-BR as descrições que o Quarkus gera em inglês para as
 * respostas 400, 401 e 403 de cada operação.
 */
@OpenApiFilter(stages = OpenApiFilter.RunStage.BUILD)
public class OpenApiDescriptions implements OASFilter {
    private static final Map<String, String> PT_BR = Map.of(
            "Bad Request", "Requisição inválida",
            "Not Authorized", "Token ausente, expirado ou inválido",
            "Not Allowed", "Papel sem permissão para a operação");

    @Override
    public APIResponse filterAPIResponse(APIResponse response) {
        String translated = PT_BR.get(response.getDescription());
        if (translated != null)
            response.setDescription(translated);
        return response;
    }
}
