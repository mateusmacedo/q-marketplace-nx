package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.InvalidInputException;
import dev.marketplace.cataloginventory.application.PreconditionRequiredException;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.RestResponse;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * ETag forte com a versão do agregado; If-Match é o único mecanismo de controle
 * otimista exposto.
 */
final class ETags {
    private ETags() {
    }

    static EntityTag of(long version) {
        return new EntityTag(Long.toString(version));
    }

    /**
     * Lê o {@code If-Match}: ausente, em branco ou {@code *} dispensam a checagem
     * de versão. ETag
     * fraco ({@code W/}), sem aspas ou não numérico responde 400.
     */
    static Optional<Long> optional(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank())
            return Optional.empty();
        String value = ifMatch.trim();
        if (value.equals("*"))
            return Optional.empty();
        if (value.startsWith("W/"))
            throw new InvalidInputException("If-Match exige ETag forte");
        if (value.length() < 3 || !value.startsWith("\"") || !value.endsWith("\""))
            throw new InvalidInputException("If-Match deve conter um ETag entre aspas");
        try {
            return Optional.of(Long.parseLong(value.substring(1, value.length() - 1)));
        } catch (NumberFormatException e) {
            throw new InvalidInputException("ETag inválido em If-Match");
        }
    }

    /**
     * Como {@link #optional}, mas cabeçalho ausente, em branco ou {@code *}
     * responde 428: a rota exige a versão do agregado, e {@code *} gravaria sem
     * checá-la.
     */
    static Optional<Long> required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*"))
            throw new PreconditionRequiredException();
        return optional(ifMatch);
    }

    /** Responde 200 com o corpo e o ETag forte da versão. */
    static <T> RestResponse<T> ok(T body, long version) {
        return RestResponse.ResponseBuilder.<T>ok(body).tag(of(version)).build();
    }

    /** Responde 201 com {@code Location}, o corpo e o ETag forte da versão. */
    static <T> RestResponse<T> created(URI location, T body, long version) {
        return RestResponse.ResponseBuilder.<T>created(location).entity(body).tag(of(version)).build();
    }

    /**
     * Responde 201 com {@code Location} em {@code <caminho atual>/<id>}, o corpo e
     * o ETag forte da versão.
     */
    static <T> RestResponse<T> created(UriInfo uri, UUID id, T body, long version) {
        return created(child(uri, id), body, version);
    }

    /**
     * Como {@link #created(UriInfo, UUID, Object, long)}, para recurso sem ETag.
     */
    static <T> RestResponse<T> created(UriInfo uri, UUID id, T body) {
        return RestResponse.ResponseBuilder.<T>created(child(uri, id)).entity(body).build();
    }

    private static URI child(UriInfo uri, UUID id) {
        return uri.getAbsolutePathBuilder().path(id.toString()).build();
    }
}
