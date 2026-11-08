package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Correlation;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Aceita o {@code X-Correlation-Id} do chamador, ou gera um, e o devolve na
 * resposta; os eventos da requisição o carregam.
 */
public class CorrelationFilter {
    /**
     * Associa à requisição o {@code X-Correlation-Id} recebido, ou um novo se
     * ausente ou
     * malformado; roda antes de escolher a rota, então cobre também as respostas de
     * erro.
     */
    @ServerRequestFilter(preMatching = true)
    public void bind(ContainerRequestContext request) {
        Correlation.bind(Correlation.accept(request.getHeaderString(Correlation.HEADER)));
    }

    /**
     * Devolve a correlação da requisição no cabeçalho {@code X-Correlation-Id} da
     * resposta.
     */
    @ServerResponseFilter
    public void echo(ContainerResponseContext response) {
        response.getHeaders().putSingle(Correlation.HEADER, Correlation.current());
    }
}
