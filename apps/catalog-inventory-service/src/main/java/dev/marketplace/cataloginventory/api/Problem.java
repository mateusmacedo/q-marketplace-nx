package dev.marketplace.cataloginventory.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.util.List;

/**
 * Corpo de erro no formato RFC 9457. Extensões: {@code errors} (campos
 * inválidos), {@code reasons} (motivos estáveis de uma recusa de negócio, como
 * {@code missing-attribute:cor}) e {@code reasonDetails}, os mesmos motivos com
 * os parâmetros separados e o texto para mostrar.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String instance, List<FieldError> errors,
        List<String> reasons,
        @Schema(description = "Motivos da recusa de regra de negócio para mostrar ao usuário: um por item de"
                + " reasons, na mesma ordem, ou, sem reasons, o da própria recusa") List<ReasonDetail> reasonDetails) {
    public static final String MEDIA_TYPE = "application/problem+json";

    public record FieldError(String field, String message) {
    }

    /** Motivo de uma recusa com o texto já resolvido. */
    public record ReasonDetail(
            @Schema(description = "Código estável do motivo, sem os parâmetros (missing-variant-attribute)") String code,
            @Schema(description = "Valores do caso na ordem do texto, como o SKU, o atributo ou o limite vigente") List<String> params,
            @Schema(description = "Texto em português para mostrar ao usuário") String message) {
    }
}
