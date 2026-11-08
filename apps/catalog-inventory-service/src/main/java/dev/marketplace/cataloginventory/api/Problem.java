package dev.marketplace.cataloginventory.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Corpo de erro no formato RFC 9457. Extensões: {@code errors} (campos
 * inválidos) e {@code reasons}
 * (motivos estáveis de uma recusa de negócio, como
 * {@code missing-attribute:cor}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String instance, List<FieldError> errors,
        List<String> reasons) {
    public static final String MEDIA_TYPE = "application/problem+json";

    public record FieldError(String field, String message) {
    }
}
