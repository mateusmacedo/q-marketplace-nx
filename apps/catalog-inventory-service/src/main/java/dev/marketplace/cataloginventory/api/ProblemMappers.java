package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Telemetry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.marketplace.cataloginventory.application.*;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.inventory.ReservationRefusal;
import dev.marketplace.cataloginventory.domain.inventory.StockConflictException;
import dev.marketplace.cataloginventory.imports.InvalidImportFileException;
import dev.marketplace.cataloginventory.security.TenantNotAuthorizedException;
import io.smallrye.mutiny.TimeoutException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import java.util.Comparator;
import java.util.List;

/** Traduz exceções em respostas RFC 9457 sem vazar detalhes internos. */
public class ProblemMappers {
    private static final Logger LOG = Logger.getLogger(ProblemMappers.class);
    private static final String TYPE_BASE = "https://marketplace.dev/problems/";

    /** Responde 404 {@code not-found}. */
    @ServerExceptionMapper
    public RestResponse<Problem> notFound(NotFoundException e, UriInfo uri) {
        return problem(404, "not-found", "Não encontrado", e.getMessage(), uri);
    }

    /** Responde 409 com o código específico do recurso duplicado. */
    @ServerExceptionMapper
    public RestResponse<Problem> duplicate(DuplicateException e, UriInfo uri) {
        return problem(409, e.code(), "Recurso duplicado", e.getMessage(), uri);
    }

    /**
     * Responde 409 {@code concurrent-update}: outra transação alterou o agregado.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> concurrentUpdate(ConcurrentUpdateException e, UriInfo uri) {
        return problem(409, "concurrent-update", "Conflito de atualização", e.getMessage(), uri);
    }

    /**
     * Responde 412 {@code version-mismatch} quando o {@code If-Match} não confere
     * com a versão
     * atual.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> versionMismatch(VersionMismatchException e, UriInfo uri) {
        return problem(412, "version-mismatch", "Versão desatualizada", e.getMessage(), uri);
    }

    /** Responde 428 quando a operação exige {@code If-Match} e ele não veio. */
    @ServerExceptionMapper
    public RestResponse<Problem> preconditionRequired(PreconditionRequiredException e, UriInfo uri) {
        return problem(428, "precondition-required", "If-Match obrigatório", e.getMessage(), uri);
    }

    /** Responde 409 com o código da transição negada pelo estado atual. */
    @ServerExceptionMapper
    public RestResponse<Problem> invalidTransition(InvalidTransitionException e, UriInfo uri) {
        return violation(409, "Transição não permitida", e, uri);
    }

    /**
     * Responde 409 com o código da recusa e os motivos estáveis em {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> reservationRefused(ReservationRefusal e, UriInfo uri) {
        return violation(409, "Reserva recusada", e, uri);
    }

    /**
     * Responde 409 com o código do conflito de estoque e, se houver, os motivos em
     * {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> stockConflict(StockConflictException e, UriInfo uri) {
        return violation(409, "Conflito de estoque", e, uri);
    }

    /**
     * Responde 422 com o código da regra violada e, se houver, os motivos em
     * {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> businessRule(DomainRuleViolation e, UriInfo uri) {
        return violation(422, "Regra de negócio violada", e, uri);
    }

    /** Responde 403 {@code access-denied}. */
    @ServerExceptionMapper
    public RestResponse<Problem> accessDenied(AccessDeniedException e, UriInfo uri) {
        return problem(403, "access-denied", "Acesso negado", e.getMessage(), uri);
    }

    /**
     * Responde 503 com {@code Retry-After: 1} quando o banco não responde no prazo.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> persistenceTimeout(TimeoutException e, UriInfo uri) {
        LOG.warnf("Banco não respondeu no prazo em %s", uri.getPath());
        return builder(503, "service-unavailable", "Serviço indisponível",
                "Dependência não respondeu no prazo; tente novamente", uri, null, null).header("Retry-After", "1")
                .build();
    }

    /** Responde 400 {@code invalid-request}. */
    @ServerExceptionMapper
    public RestResponse<Problem> invalidInput(InvalidInputException e, UriInfo uri) {
        return problem(400, "invalid-request", "Requisição inválida", e.getMessage(), uri);
    }

    /**
     * Responde 400 {@code invalid-import-file}, com os motivos estáveis da
     * recusa do arquivo em {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> invalidImportFile(InvalidImportFileException e, UriInfo uri) {
        return problem(400, "invalid-import-file", "Arquivo de importação inválido", e.getMessage(), uri, null,
                e.reasons());
    }

    /**
     * Responde 400 {@code validation-failed}, com os campos inválidos em
     * {@code errors} ordenados
     * por nome.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> constraintViolation(ConstraintViolationException e, UriInfo uri) {
        List<Problem.FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new Problem.FieldError(field(v), v.getMessage()))
                .sorted(Comparator.comparing(Problem.FieldError::field))
                .toList();
        return problem(400, "validation-failed", "Dados inválidos", "Um ou mais campos são inválidos", uri, errors,
                null);
    }

    /**
     * Responde 400 {@code malformed-body} para JSON malformado ou de tipo
     * incompatível.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> unreadableBody(JsonProcessingException e, UriInfo uri) {
        return problem(400, "malformed-body", "Corpo inválido", "JSON malformado ou com tipos incompatíveis", uri);
    }

    /**
     * Como {@link #unreadableBody}, para tipo incompatível ou propriedade
     * obrigatória ausente; sem ele, o mapeador embutido do Quarkus responderia
     * fora da RFC 9457.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> mismatchedBody(MismatchedInputException e, UriInfo uri) {
        return unreadableBody(e, uri);
    }

    /**
     * Responde 403 {@code tenant-not-authorized} quando o token não identifica um
     * tenant válido.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> tenantNotAuthorized(TenantNotAuthorizedException e, UriInfo uri) {
        return problem(403, "tenant-not-authorized", "Acesso negado", e.getMessage(), uri);
    }

    /**
     * Preserva o status da exceção JAX-RS (rota inexistente, método ou mídia não
     * aceitos) com o
     * código {@code http-<status>}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> webApplication(WebApplicationException e, UriInfo uri) {
        int status = e.getResponse().getStatus();
        String title = e.getResponse().getStatusInfo().getReasonPhrase();
        return problem(status, "http-" + status, title, null, uri);
    }

    /**
     * Responde 500 {@code internal-error} sem expor a causa, que vai só para o log.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> unexpected(Exception e, UriInfo uri) {
        LOG.errorf(e, "Erro não tratado em %s", uri.getPath());
        return problem(500, "internal-error", "Erro interno", "Falha inesperada; tente novamente mais tarde", uri);
    }

    private static String field(ConstraintViolation<?> violation) {
        // WHY: o caminho inclui método e parâmetro (ex.: create.request.title); o
        // cliente precisa do caminho no corpo.
        String path = violation.getPropertyPath().toString();
        String[] parts = path.split("\\.", 3);
        return parts.length == 3 ? parts[2] : parts[parts.length - 1];
    }

    /**
     * Resposta da família {@link DomainRuleViolation}: o tipo é o código da exceção
     * e {@code reasons} vazio fica fora do corpo.
     */
    private static RestResponse<Problem> violation(int status, String title, DomainRuleViolation e, UriInfo uri) {
        return problem(status, e.code(), title, e.getMessage(), uri, null,
                e.reasons().isEmpty() ? null : e.reasons());
    }

    private static RestResponse<Problem> problem(int status, String type, String title, String detail, UriInfo uri) {
        return problem(status, type, title, detail, uri, null, null);
    }

    private static RestResponse<Problem> problem(int status, String type, String title, String detail, UriInfo uri,
            List<Problem.FieldError> errors, List<String> reasons) {
        return builder(status, type, title, detail, uri, errors, reasons).build();
    }

    /**
     * Monta a resposta RFC 9457 com o tipo {@code application/problem+json};
     * {@code instance} é o caminho da requisição. Todo problema devolvido passa
     * por aqui, inclusive 500 e 503, e fica registrado no span e no MDC
     * ({@link Telemetry#problem}).
     */
    private static RestResponse.ResponseBuilder<Problem> builder(int status, String type, String title, String detail,
            UriInfo uri, List<Problem.FieldError> errors, List<String> reasons) {
        var body = new Problem(TYPE_BASE + type, title, status, detail, uri.getPath(), errors, reasons);
        Telemetry.problem(status, type, reasons);
        return RestResponse.ResponseBuilder.<Problem>create(status).entity(body).type(Problem.MEDIA_TYPE);
    }
}
