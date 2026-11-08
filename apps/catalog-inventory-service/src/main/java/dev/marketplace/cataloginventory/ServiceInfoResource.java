package dev.marketplace.cataloginventory;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/internal/info")
@PermitAll
public class ServiceInfoResource {
    /**
     * Responde 200 com o nome do contexto; rota interna, aberta a qualquer chamador
     * e fora do contrato público.
     */
    @GET
    @Operation(hidden = true)
    public Map<String, String> info() {
        return Map.of("context", "catalog-inventory");
    }
}
