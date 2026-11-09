package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.observability.Telemetry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.marketplace.cataloginventory.application.*;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.category.CategoryNotEmptyException;
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
    private static final String TIMEOUT_DETAIL = "Dependência não respondeu no prazo; tente novamente";
    private static final String UNEXPECTED_DETAIL = "Falha inesperada; tente novamente mais tarde";

    /**
     * Problema de uma falha antes de ganhar o caminho da requisição:
     * {@code code} é o fim do {@code type}, e {@code details}, os motivos a
     * mostrar, com o texto resolvido no catálogo de mensagens.
     */
    record Described(int status, String code, String title, String detail, List<String> reasons,
            List<Reason> details) {
        Described(int status, String code, String title, String detail, List<String> reasons) {
            this(status, code, title, detail, reasons, null);
        }

        Problem toProblem(String instance, List<Problem.FieldError> errors) {
            return new Problem(TYPE_BASE + code, title, status, detail, instance, errors, reasons,
                    ReasonMessages.details(details, detail));
        }
    }

    /**
     * Status, código e título de cada falha que os casos de uso lançam; o que não
     * é reconhecido vira 500 sem a causa. Fonte única dos mapeadores e do
     * resultado de cada item dos lotes, para os dois responderem igual.
     */
    static Described describe(Throwable failure) {
        return switch (failure) {
            case NotFoundException e -> new Described(404, "not-found", "Não encontrado", e.getMessage(), null);
            case DuplicateException e -> new Described(409, e.code(), "Recurso duplicado", e.getMessage(), null);
            case ConcurrentUpdateException e ->
                new Described(409, "concurrent-update", "Conflito de atualização", e.getMessage(), null);
            case VersionMismatchException e ->
                new Described(412, "version-mismatch", "Versão desatualizada", e.getMessage(), null);
            case PreconditionRequiredException e ->
                new Described(428, "precondition-required", "If-Match obrigatório", e.getMessage(), null);
            case InvalidTransitionException e -> violation(409, "Transição não permitida", e);
            case ReservationRefusal e -> violation(409, "Reserva recusada", e);
            case StockConflictException e -> violation(409, "Conflito de estoque", e);
            case CategoryNotEmptyException e -> violation(409, "Categoria em uso", e);
            case DomainRuleViolation e -> violation(422, "Regra de negócio violada", e);
            case AccessDeniedException e -> new Described(403, "access-denied", "Acesso negado", e.getMessage(), null);
            case TimeoutException e ->
                new Described(503, "service-unavailable", "Serviço indisponível", TIMEOUT_DETAIL, null);
            case InvalidInputException e ->
                new Described(400, "invalid-request", "Requisição inválida", e.getMessage(), null);
            default -> new Described(500, "internal-error", "Erro interno", UNEXPECTED_DETAIL, null);
        };
    }

    /**
     * Problema de um item de lote que falhou, sem {@code instance}: a falha
     * inesperada ou o prazo estourado vão para o log, como nos mapeadores.
     */
    static Problem itemProblem(Throwable failure, String path) {
        Described described = describe(failure);
        if (described.status() == 500)
            LOG.errorf(failure, "Erro não tratado em item de lote de %s", path);
        else if (described.status() == 503)
            LOG.warnf("Banco não respondeu no prazo em item de lote de %s", path);
        return described.toProblem(null, null);
    }

    /** Responde 404 {@code not-found}. */
    @ServerExceptionMapper
    public RestResponse<Problem> notFound(NotFoundException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /** Responde 409 com o código específico do recurso duplicado. */
    @ServerExceptionMapper
    public RestResponse<Problem> duplicate(DuplicateException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 409 {@code concurrent-update}: outra transação alterou o agregado.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> concurrentUpdate(ConcurrentUpdateException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 412 {@code version-mismatch} quando o {@code If-Match} não confere
     * com a versão
     * atual.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> versionMismatch(VersionMismatchException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /** Responde 428 quando a operação exige {@code If-Match} e ele não veio. */
    @ServerExceptionMapper
    public RestResponse<Problem> preconditionRequired(PreconditionRequiredException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /** Responde 409 com o código da transição negada pelo estado atual. */
    @ServerExceptionMapper
    public RestResponse<Problem> invalidTransition(InvalidTransitionException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 409 com o código da recusa e os motivos estáveis em {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> reservationRefused(ReservationRefusal e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 409 com o código do conflito de estoque e, se houver, os motivos em
     * {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> stockConflict(StockConflictException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 409 {@code category-has-children} ou {@code category-has-products}, com o mesmo código em
     * {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> categoryNotEmpty(CategoryNotEmptyException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 422 com o código da regra violada e, se houver, os motivos em
     * {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> businessRule(DomainRuleViolation e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /** Responde 403 {@code access-denied}. */
    @ServerExceptionMapper
    public RestResponse<Problem> accessDenied(AccessDeniedException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 503 com {@code Retry-After: 1} quando o banco não responde no prazo.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> persistenceTimeout(TimeoutException e, UriInfo uri) {
        LOG.warnf("Banco não respondeu no prazo em %s", uri.getPath());
        return builder(describe(e), uri, null).header("Retry-After", "1").build();
    }

    /** Responde 400 {@code invalid-request}. */
    @ServerExceptionMapper
    public RestResponse<Problem> invalidInput(InvalidInputException e, UriInfo uri) {
        return respond(describe(e), uri);
    }

    /**
     * Responde 400 {@code invalid-import-file}, com os motivos estáveis da
     * recusa do arquivo em {@code reasons}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> invalidImportFile(InvalidImportFileException e, UriInfo uri) {
        return respond(new Described(400, "invalid-import-file", "Arquivo de importação inválido", e.getMessage(),
                e.reasons(), e.details()), uri);
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
        return validationFailed(uri, errors);
    }

    /**
     * Responde 400 {@code validation-failed}, como {@link #constraintViolation}, quando um texto do corpo traz o
     * caractere nulo (U+0000), com o caminho dele em {@code errors}. A leitura do corpo para no primeiro, e a
     * validação dos demais campos não chega a rodar.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> nullCharacter(NullCharacterGuard.Rejected e, UriInfo uri) {
        return validationFailed(uri, List.of(new Problem.FieldError(e.field(), NullCharacterGuard.MESSAGE)));
    }

    private static RestResponse<Problem> validationFailed(UriInfo uri, List<Problem.FieldError> errors) {
        return builder(new Described(400, "validation-failed", "Dados inválidos", "Um ou mais campos são inválidos",
                null), uri, errors).build();
    }

    /**
     * Responde 400 {@code malformed-body} para JSON malformado ou de tipo
     * incompatível.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> unreadableBody(JsonProcessingException e, UriInfo uri) {
        return respond(new Described(400, "malformed-body", "Corpo inválido",
                "JSON malformado ou com tipos incompatíveis", null), uri);
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
        return respond(new Described(403, "tenant-not-authorized", "Acesso negado", e.getMessage(), null), uri);
    }

    /**
     * Preserva o status da exceção JAX-RS (rota inexistente, método ou mídia não
     * aceitos) com o
     * código {@code http-<status>}; o 400 do JSON malformado vira {@code malformed-body}.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> webApplication(WebApplicationException e, UriInfo uri) {
        int status = e.getResponse().getStatus();
        // WHY: o leitor JSON do Quarkus REST embrulha a sintaxe inválida (StreamReadException) num 400 genérico.
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof JsonProcessingException))
            cause = cause.getCause();
        if (status == 400 && cause instanceof JsonProcessingException json)
            return unreadableBody(json, uri);
        String title = e.getResponse().getStatusInfo().getReasonPhrase();
        return respond(new Described(status, "http-" + status, title, null, null), uri);
    }

    /**
     * Responde 500 {@code internal-error} sem expor a causa, que vai só para o log.
     */
    @ServerExceptionMapper
    public RestResponse<Problem> unexpected(Exception e, UriInfo uri) {
        LOG.errorf(e, "Erro não tratado em %s", uri.getPath());
        return respond(describe(e), uri);
    }

    private static String field(ConstraintViolation<?> violation) {
        // WHY: o caminho inclui método e parâmetro (ex.: create.request.title); o
        // cliente precisa do caminho no corpo.
        String path = violation.getPropertyPath().toString();
        String[] parts = path.split("\\.", 3);
        return parts.length == 3 ? parts[2] : parts[parts.length - 1];
    }

    /**
     * Problema da família {@link DomainRuleViolation}: o tipo é o código da
     * exceção, {@code reasons} vazio fica fora do corpo e {@code reasonDetails}
     * traz os motivos ou, sem eles, o da própria recusa.
     */
    private static Described violation(int status, String title, DomainRuleViolation e) {
        return new Described(status, e.code(), title, e.getMessage(), e.reasons().isEmpty() ? null : e.reasons(),
                e.details());
    }

    private static RestResponse<Problem> respond(Described described, UriInfo uri) {
        return builder(described, uri, null).build();
    }

    /**
     * Monta a resposta RFC 9457 com o tipo {@code application/problem+json};
     * {@code instance} é o caminho da requisição. Todo problema devolvido passa
     * por aqui, inclusive 500 e 503, e fica registrado no span e no MDC
     * ({@link Telemetry#problem}).
     */
    private static RestResponse.ResponseBuilder<Problem> builder(Described described, UriInfo uri,
            List<Problem.FieldError> errors) {
        Problem body = described.toProblem(uri.getPath(), errors);
        Telemetry.problem(described.status(), described.code(), described.reasons());
        return RestResponse.ResponseBuilder.<Problem>create(described.status()).entity(body).type(Problem.MEDIA_TYPE);
    }
}
