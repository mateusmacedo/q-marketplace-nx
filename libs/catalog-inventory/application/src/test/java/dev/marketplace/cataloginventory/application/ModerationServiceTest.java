package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.product.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModerationServiceTest {
  final Catalog catalog = new Catalog();
  final ProductService products = catalog.products;
  final ModerationService moderation = catalog.moderation;
  final Actor seller = catalog.seller;
  int skus;

  /** Rascunho completo, com oferta, e SKU de código único na loja. */
  Product ready() {
    skus++;
    return catalog.offered(catalog.draft(sku("SKU-" + skus, "P")));
  }

  Product submitted() {
    Product product = ready();
    catalog.clock.advance(Duration.ofSeconds(1));
    return item(products.submit(seller, product.id(), Optional.empty()));
  }

  List<UUID> queueIds(Optional<String> cursor, int limit) {
    return item(moderation.queue(moderator(), cursor, limit)).items().stream().map(Product::id).toList();
  }

  @Test
  void queueComesInOrderOfArrivalAndEditsInTheQueueKeepThePlace() {
    catalog.approveSeller();
    Product first = submitted();
    Product second = submitted();
    Product third = submitted();
    catalog.clock.advance(Duration.ofSeconds(1));
    item(products.reviseContent(seller, first.id(), Optional.empty(), catalog.category, "Camiseta nova", "",
        Map.of("marca", "Acme")));
    assertEquals(List.of(first.id(), second.id(), third.id()), queueIds(Optional.empty(), 10),
        "editar na fila não muda a ordem de chegada");

    Page<Product> page = item(moderation.queue(moderator(), Optional.empty(), 2));
    assertEquals(List.of(first.id(), second.id()), page.items().stream().map(Product::id).toList());
    assertEquals(List.of(third.id()), queueIds(page.nextCursor(), 2));

    item(moderation.approve(moderator(), first.id(), Optional.empty()));
    catalog.clock.advance(Duration.ofSeconds(1));
    item(products.reviseContent(seller, first.id(), Optional.empty(), catalog.category, "Camiseta revista", "",
        Map.of("marca", "Acme")));
    assertEquals(List.of(second.id(), third.id(), first.id()), queueIds(Optional.empty(), 10),
        "a volta à moderação entra no fim da fila");
  }

  @Test
  void queueRefusesMalformedCursor() {
    failure(moderation.queue(moderator(), Optional.of("2026-11-05T12:00:00Z"), 10), InvalidInputException.class);
    failure(moderation.queue(moderator(), Optional.of("ontem|" + UUID.randomUUID()), 10),
        InvalidInputException.class);
  }

  @Test
  void rejectionAndBlockingRecordCodesLabelsOfTheTimeAndNoteInTheHistory() {
    Product rejected = submitted();
    item(moderation.reject(moderator(), rejected.id(), Optional.empty(),
        List.of("inconsistent-attributes", "wrong-category"), Optional.of("A gola não bate com a foto")));
    StatusChange rejection = last(rejected);
    assertEquals(List.of("wrong-category", "inconsistent-attributes"), rejection.reasonCodes());
    assertEquals(List.of(new ReasonLabel("wrong-category", "Categoria incorreta"),
        new ReasonLabel("inconsistent-attributes", "Atributos inconsistentes com os SKUs")),
        rejection.reasons());
    assertEquals("A gola não bate com a foto", rejection.note());
    assertEquals("Categoria incorreta. Atributos inconsistentes com os SKUs. A gola não bate com a foto",
        rejection.reason());

    Product blocked = submitted();
    item(moderation.block(moderator(), blocked.id(), Optional.empty(), List.of("prohibited-item"),
        Optional.empty()));
    assertEquals(List.of("prohibited-item"), last(blocked).reasonCodes());
    assertEquals(List.of(), item(products.history(seller, blocked.id())).get(0).reasonCodes(),
        "transição sem motivo tem lista vazia");
  }

  @Test
  void invalidReasonIsRefusedBeforeReadingTheProduct() {
    var error = failure(moderation.reject(moderator(), UUID.randomUUID(), Optional.empty(),
        List.of("prohibited-item"), Optional.empty()), DomainRuleViolation.class);
    assertEquals("invalid-moderation-reason", error.code());
    assertEquals(List.of("reason-not-applicable:prohibited-item"), error.reasons());
    assertEquals("invalid-value", failure(moderation.block(moderator(), UUID.randomUUID(), Optional.empty(),
        List.of(), Optional.of(" ")), DomainRuleViolation.class).code());
    failure(moderation.reject(seller, UUID.randomUUID(), Optional.empty(), List.of("spam"), Optional.empty()),
        AccessDeniedException.class);
  }

  @Test
  void renamingAReasonDoesNotChangePastDecisions() {
    Product rejected = submitted();
    item(moderation.reject(moderator(), rejected.id(), Optional.empty(), List.of("wrong-category"),
        Optional.empty()));
    item(catalog.moderationReasons.revise(admin(), "wrong-category", Optional.of(0L), "Categoria errada",
        List.of(ModerationDecision.REJECT), 0));
    assertEquals(List.of(new ReasonLabel("wrong-category", "Categoria incorreta")), last(rejected).reasons(),
        "o histórico guarda o rótulo da época");

    Product next = submitted();
    item(moderation.reject(moderator(), next.id(), Optional.empty(), List.of("wrong-category"), Optional.empty()));
    assertEquals("Categoria errada.", last(next).reason(), "decisão nova usa o rótulo atual");
  }

  @Test
  void deactivatedOrForeignReasonIsRefusedInNewDecisions() {
    item(catalog.moderationReasons.deactivate(admin(), "price-out-of-range", Optional.empty()));
    Actor otherAdmin = new Actor(UUID.randomUUID(), Optional.empty(), "admin-2",
        Set.of(Capability.MANAGE_MODERATION_REASONS));
    item(catalog.moderationReasons.create(otherAdmin, "counterfeit", "Possível falsificação",
        List.of(ModerationDecision.REJECT), Optional.empty()));
    var error = failure(moderation.reject(moderator(), submitted().id(), Optional.empty(),
        List.of("price-out-of-range", "counterfeit"), Optional.empty()), DomainRuleViolation.class);
    assertEquals("invalid-moderation-reason", error.code());
    assertEquals(List.of("reason-inactive:price-out-of-range", "unknown-reason:counterfeit"), error.reasons(),
        "o motivo de outro tenant não existe neste");
  }

  @Test
  void theNoteTakesUpToItsLimitBesideSeveralReasonsAndAloneSkipsTheCatalog() {
    Product product = submitted();
    String note = "x".repeat(DecisionReason.NOTE_MAX_LENGTH);
    item(moderation.reject(moderator(), product.id(), Optional.empty(),
        List.of("wrong-category", "misleading-description", "price-out-of-range"), Optional.of(note)));
    assertEquals(note, last(product).note());
    assertEquals(3, last(product).reasons().size());

    int reads = catalog.store.catalogReads;
    item(moderation.block(moderator(), product.id(), Optional.empty(), List.of(), Optional.of("Só a nota")));
    assertEquals(reads, catalog.store.catalogReads, "decisão só com o complemento não lê o catálogo");
    assertEquals("Só a nota", last(product).reason());
  }

  @Test
  void batchDecisionRunsEachItemAloneWithTheSameReason() {
    Product first = submitted();
    Product stale = submitted();
    Product draft = ready();
    UUID missing = UUID.randomUUID();
    List<Batch.Outcome<Product>> outcomes = item(moderation.batch(moderator(), ModerationDecision.REJECT,
        List.of("wrong-category"), Optional.empty(),
        List.of(new Batch.Item(first.id(), Optional.of(first.version())),
            new Batch.Item(stale.id(), Optional.of(0L)),
            new Batch.Item(draft.id(), Optional.empty()),
            new Batch.Item(missing, Optional.empty()))));
    assertEquals(List.of(first.id(), stale.id(), draft.id(), missing),
        outcomes.stream().map(Batch.Outcome::id).toList(), "ordem do pedido");
    assertEquals(ProductStatus.DRAFT, outcomes.get(0).value().orElseThrow().status());
    assertEquals("Categoria incorreta.", last(first).reason());
    assertInstanceOf(VersionMismatchException.class, outcomes.get(1).failure().orElseThrow());
    assertEquals("invalid-transition",
        ((DomainRuleViolation) outcomes.get(2).failure().orElseThrow()).code());
    assertInstanceOf(NotFoundException.class, outcomes.get(3).failure().orElseThrow());
  }

  @Test
  void batchApprovalReportsIneligibleSellerPerItem() {
    Product product = submitted();
    List<Batch.Outcome<Product>> outcomes = item(moderation.batch(moderator(), ModerationDecision.APPROVE,
        List.of(), Optional.empty(), List.of(new Batch.Item(product.id(), Optional.empty()))));
    assertEquals("seller-not-eligible",
        ((DomainRuleViolation) outcomes.get(0).failure().orElseThrow()).code());
    catalog.approveSeller();
    assertEquals(ProductStatus.ACTIVE, item(moderation.batch(moderator(), ModerationDecision.APPROVE, List.of(),
        Optional.empty(), List.of(new Batch.Item(product.id(), Optional.empty())))).get(0).value()
        .orElseThrow().status());
  }

  @Test
  void batchRefusesTheWholeRequestBeforeTheFirstItem() {
    Product product = submitted();
    List<Batch.Item> one = List.of(new Batch.Item(product.id(), Optional.empty()));
    failure(moderation.batch(moderator(), ModerationDecision.APPROVE, List.of("wrong-category"),
        Optional.empty(), one), InvalidInputException.class);
    assertEquals("invalid-moderation-reason", failure(moderation.batch(moderator(), ModerationDecision.BLOCK,
        List.of("wrong-category"), Optional.empty(), one), DomainRuleViolation.class).code());
    failure(moderation.batch(moderator(), ModerationDecision.REJECT, List.of("wrong-category"),
        Optional.empty(), List.of()), InvalidInputException.class);
    failure(moderation.batch(moderator(), ModerationDecision.REJECT, List.of("wrong-category"),
        Optional.empty(), List.of(one.get(0), one.get(0))), InvalidInputException.class);
    failure(moderation.batch(seller, ModerationDecision.APPROVE, List.of(), Optional.empty(), one),
        AccessDeniedException.class);
    assertEquals(ProductStatus.PENDING_MODERATION, item(products.get(seller, product.id())).status(),
        "nada foi gravado");
  }

  private StatusChange last(Product product) {
    List<StatusChange> history = item(products.history(seller, product.id()));
    return history.get(history.size() - 1);
  }
}
