package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.offer.Price;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Passos comuns às unidades da importação: rodar em sequência, ler o preço da linha e transformar a recusa de negócio
 * em linha {@code FAILED}, com o código que a API responderia.
 */
final class ImportSteps {
  static final String INVALID_ROW = ApplicationReasons.INVALID_ROW.code();

  private ImportSteps() {
  }

  static <T, R> Uni<List<R>> sequentially(List<T> items, Function<T, Uni<R>> step) {
    return Multi.createFrom().iterable(items).onItem().<R>transformToUniAndConcatenate(step::apply).collect()
        .asList();
  }

  /** Preço da linha na moeda dela; sem preço de lista, só o de venda. */
  static Price price(ImportRow row) {
    Money list = row.listPrice() == null ? null : new Money(row.listPrice(), row.currency());
    return new Price(list, new Money(row.salePrice(), row.currency()));
  }

  /** Recusas de negócio viram linha {@code FAILED}; o restante é falha técnica. */
  static boolean isRefusal(Throwable e) {
    return e instanceof DomainRuleViolation || e instanceof DuplicateException || e instanceof NotFoundException
        || e instanceof ConcurrentUpdateException || e instanceof VersionMismatchException
        || e instanceof PreconditionRequiredException || e instanceof InvalidInputException
        || e instanceof AccessDeniedException;
  }

  /** Linha inválida; a descrição do problema na linha é o próprio texto ao seller. */
  static DomainRuleViolation invalidRow(String message) {
    return new DomainRuleViolation(Reason.of(ApplicationReasons.INVALID_ROW).withMessage(message), message);
  }

  /** A recusa de negócio leva à linha os motivos dela, como no corpo de erro da API. */
  static ImportResult failed(ImportRow row, UUID productId, Throwable e) {
    String message = Objects.requireNonNullElse(e.getMessage(), codeOf(e));
    List<Reason> reasons = e instanceof DomainRuleViolation violation ? violation.details() : List.of();
    return ImportResult.failed(row, productId, codeOf(e), message, reasons);
  }

  /** Código estável da recusa, igual ao {@code type} que a API responderia. */
  private static String codeOf(Throwable e) {
    return switch (e) {
      case DomainRuleViolation violation -> violation.code();
      case DuplicateException duplicate -> duplicate.code();
      case NotFoundException notFound -> "not-found";
      case ConcurrentUpdateException concurrent -> "concurrent-update";
      case VersionMismatchException mismatch -> "version-mismatch";
      case PreconditionRequiredException precondition -> "precondition-required";
      case AccessDeniedException denied -> "access-denied";
      default -> "invalid-request";
    };
  }
}
