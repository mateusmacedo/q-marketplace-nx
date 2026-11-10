package dev.marketplace.kybkyc;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;
@Path("/internal/info")
public class ServiceInfoResource {
  /** Responde 200 com o nome do contexto do serviço; rota interna de identificação. */
  @GET
  public Map<String, String> info() {
    return Map.of("context", "kyb-kyc");
  }
}
