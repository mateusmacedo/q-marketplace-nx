package dev.marketplace.cataloginventory.observability;

import dev.marketplace.cataloginventory.config.CatalogConfig;
import io.smallrye.common.vertx.ContextLocals;
import jakarta.json.JsonString;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import java.util.Map;
import java.util.TreeSet;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import org.jboss.resteasy.reactive.server.SimpleResourceInfo;

/**
 * Contexto de negócio de cada requisição no span do servidor e no MDC (tenant,
 * loja, usuário, papéis, correlação e os
 * agregados do caminho) e uma linha de log por requisição que chega ao JAX-RS
 * (ADR 021). Recusa na autenticação,
 * antes do JAX-RS, não gera linha.
 */
public class RequestTelemetry {
    private static final Logger ACCESS = Logger.getLogger("dev.marketplace.cataloginventory.access");
    private static final String STARTED = "catalog.telemetry.started";
    // WHY: o Quarkus REST guarda o template da rota nos locais do contexto Vert.x
    // da requisição, com esta chave.
    private static final String ROUTE_TEMPLATE = "UrlPathTemplate";
    // WHY: o parâmetro {id} nomeia agregados diferentes conforme o recurso; os
    // demais parâmetros já dizem qual é.
    private static final Map<String, String> ID_BY_RESOURCE = Map.of("ProductResource", "product",
            "ModerationResource", "product", "CategoryResource", "category", "ReservationResource", "reservation",
            "ProductImportResource", "import");
    private static final Map<String, String> AGGREGATE_BY_PARAMETER = Map.of("skuId", "sku", "productId", "product",
            "storeId", "store", "tenantId", "tenant");
    private final JsonWebToken token;
    private final CatalogConfig.Security security;

    RequestTelemetry(JsonWebToken token, CatalogConfig config) {
        this.token = token;
        this.security = config.security();
    }

    /**
     * Grava no span e no MDC quem chama e sobre quais agregados, depois da
     * autenticação e da escolha da rota; claims
     * ausentes ficam de fora, e a validação deles continua com o recurso. Os
     * parâmetros de caminho entram como vieram,
     * antes de validados, e {@code tenantId}/{@code storeId} do caminho prevalecem
     * sobre os claims: a chave nomeia o
     * agregado da operação, não a origem do token.
     */
    @ServerRequestFilter
    public void enrich(ContainerRequestContext request, SimpleResourceInfo resource) {
        request.setProperty(STARTED, System.nanoTime());
        Telemetry.tag(Telemetry.CORRELATION, Correlation.current());
        if (token.getRawToken() != null) {
            Telemetry.tag(Telemetry.TENANT, claim(security.tenantClaim()));
            Telemetry.tag(Telemetry.STORE, claim(security.storeClaim()));
            Telemetry.tag(Telemetry.USER, token.getSubject());
            if (token.getGroups() != null && !token.getGroups().isEmpty())
                Telemetry.tag(Telemetry.ROLES, String.join(",", new TreeSet<>(token.getGroups())));
        }
        String idAggregate = ID_BY_RESOURCE.get(resource.getResourceClass().getSimpleName());
        request.getUriInfo().getPathParameters().forEach((name, values) -> {
            String aggregate = "id".equals(name) ? idAggregate : AGGREGATE_BY_PARAMETER.get(name);
            if (aggregate != null && !values.isEmpty())
                Telemetry.tag("marketplace." + aggregate + ".id", values.get(0));
        });
    }

    /**
     * Registra a requisição concluída, inclusive as recusas, cujo tipo e motivos
     * chegam pelo MDC: rota (o template, ou
     * o caminho cru sem rota), status e tempo da escolha da rota à resposta, ou -1
     * quando nenhuma rota foi escolhida.
     * O span do servidor guarda a duração completa.
     */
    @ServerResponseFilter
    public void log(ContainerRequestContext request, ContainerResponseContext response) {
        String route = ContextLocals.<String>get(ROUTE_TEMPLATE).orElse(request.getUriInfo().getPath());
        Object started = request.getProperty(STARTED);
        long millis = started instanceof Long nanos ? (System.nanoTime() - nanos) / 1_000_000 : -1;
        ACCESS.infof("%s %s %d em %d ms", request.getMethod(), route, response.getStatus(), millis);
    }

    private String claim(String name) {
        Object value = token.getClaim(name);
        return value instanceof JsonString json ? json.getString() : value == null ? null : value.toString();
    }
}
