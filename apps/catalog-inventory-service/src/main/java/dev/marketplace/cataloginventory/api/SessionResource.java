package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.Actor;
import dev.marketplace.cataloginventory.observability.Traced;
import dev.marketplace.cataloginventory.security.CallerContext;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;

/**
 * Quem é o chamador e o que ele pode fazer no catálogo, derivado do token pelo
 * mesmo mapa de papéis que protege os endpoints. O painel monta menu e rotas a
 * partir daqui, sem conhecer nomes de papel nem claims do provedor.
 */
@Path("/me")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Session", description = "Identidade e permissões do chamador no catálogo")
@APIResponse(responseCode = "4XX", description = "Erro do cliente (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@APIResponse(responseCode = "5XX", description = "Erro do servidor (RFC 9457)",
    content = @Content(mediaType = Problem.MEDIA_TYPE, schema = @Schema(implementation = Problem.class)))
@Traced(entry = true)
public class SessionResource {
  public record SessionResponse(
      @Schema(description = "Subject do token") String subject,
      @Schema(description = "Tenant do token") UUID tenantId,
      @Schema(description = "Loja do token; só existe para o seller", nullable = true) UUID storeId,
      @Schema(description = "Capacidades de negócio do chamador. Lista aberta: o cliente ignora o valor que não"
          + " conhece") List<String> capabilities,
      @Schema(description = "Permissões que combinam capacidades, resolvidas pelo servidor") Actor.Permissions can) {
    static SessionResponse of(Actor actor) {
      return new SessionResponse(actor.subject(), actor.tenantId(), actor.storeId().orElse(null),
          actor.capabilities().stream().sorted().map(Enum::name).toList(), actor.permissions());
    }
  }

  private final CallerContext caller;

  SessionResource(CallerContext caller) {
    this.caller = caller;
  }

  /**
   * Devolve a sessão de qualquer usuário autenticado do tenant; papel que o
   * catálogo não conhece resulta em capacidades vazias, e token sem tenant, em
   * 403 {@code tenant-not-authorized}.
   */
  @GET
  @Authenticated
  @Operation(operationId = "getSession",
      summary = "Consulta o chamador: tenant, loja, capacidades e permissões compostas")
  @APIResponse(responseCode = "200", description = "Sessão do chamador")
  public Uni<SessionResponse> me() {
    return Uni.createFrom().item(() -> SessionResponse.of(caller.actor()));
  }
}
