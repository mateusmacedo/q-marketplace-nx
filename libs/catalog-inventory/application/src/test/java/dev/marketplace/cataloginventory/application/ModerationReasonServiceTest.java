package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ModerationReasonServiceTest {
  final Catalog catalog = new Catalog();
  final ModerationReasonService reasons = catalog.moderationReasons;

  List<String> codes(List<ModerationReasonEntry> entries) {
    return entries.stream().map(ModerationReasonEntry::code).toList();
  }

  @Test
  void moderatorListsOnlyActiveReasonsFilteredByDecision() {
    item(reasons.deactivate(admin(), "misleading-description", Optional.empty()));
    assertEquals(List.of("wrong-category", "inconsistent-attributes", "price-out-of-range", "prohibited-item"),
        codes(item(reasons.list(moderator(), false, Optional.empty()))));
    assertEquals(List.of("price-out-of-range", "prohibited-item"),
        codes(item(reasons.list(moderator(), false, Optional.of(ModerationDecision.BLOCK)))));
    assertEquals(List.of(), item(reasons.list(moderator(), false, Optional.of(ModerationDecision.APPROVE))));
    assertEquals(5, item(reasons.list(admin(), true, Optional.empty())).size(), "a administração vê os inativos");
    failure(reasons.list(moderator(), true, Optional.empty()), AccessDeniedException.class);
    failure(reasons.list(catalog.seller, false, Optional.empty()), AccessDeniedException.class);
  }

  @Test
  void creationAppendsAnActiveReasonAndRefusesInvalidOrRepeatedOnes() {
    ModerationReasonEntry created = item(reasons.create(admin(), "counterfeit", " Possível falsificação ",
        List.of(ModerationDecision.REJECT, ModerationDecision.BLOCK), Optional.empty()));
    assertEquals(5, created.position(), "sem posição, entra no fim");
    assertEquals("Possível falsificação", created.definition().label());
    assertTrue(created.definition().active());
    assertEquals(0, created.version());
    assertEquals(0, item(reasons.create(admin(), "first-one", "Primeiro", List.of(ModerationDecision.BLOCK),
        Optional.of(0))).position());

    failure(reasons.create(admin(), "counterfeit", "Outro", List.of(ModerationDecision.REJECT), Optional.empty()),
        DuplicateException.class);
    assertEquals("invalid-value", failure(reasons.create(admin(), "approval", "Aprovação",
        List.of(ModerationDecision.APPROVE), Optional.empty()), DomainRuleViolation.class).code());
    assertEquals("invalid-value", failure(reasons.create(admin(), "Bad Code", "Rótulo",
        List.of(ModerationDecision.REJECT), Optional.empty()), DomainRuleViolation.class).code());
    assertEquals("invalid-value", failure(reasons.create(admin(), "no-decision", "Rótulo", List.of(),
        Optional.empty()), DomainRuleViolation.class).code());
    failure(reasons.create(admin(), "negative", "Rótulo", List.of(ModerationDecision.REJECT), Optional.of(-1)),
        InvalidInputException.class);
    failure(reasons.create(moderator(), "spam", "Spam", List.of(ModerationDecision.REJECT), Optional.empty()),
        AccessDeniedException.class);
  }

  @Test
  void revisionChecksTheVersionKeepsTheCodeAndReorders() {
    ModerationReasonEntry revised = item(reasons.revise(admin(), "prohibited-item", Optional.of(0L),
        "Item proibido", List.of(ModerationDecision.BLOCK, ModerationDecision.REJECT), 0));
    assertEquals("prohibited-item", revised.code());
    assertEquals(1, revised.version());
    assertTrue(revised.definition().active());
    assertEquals(List.of("prohibited-item", "wrong-category"),
        codes(item(reasons.list(moderator(), false, Optional.empty()))).subList(0, 2),
        "na posição 0 com wrong-category, o código desempata");

    failure(reasons.revise(admin(), "prohibited-item", Optional.of(0L), "Outro", List.of(ModerationDecision.BLOCK),
        0), VersionMismatchException.class);
    failure(reasons.revise(admin(), "missing", Optional.empty(), "Outro", List.of(ModerationDecision.BLOCK), 0),
        NotFoundException.class);
    failure(reasons.revise(admin(), "prohibited-item", Optional.empty(), "Outro",
        List.of(ModerationDecision.BLOCK), ModerationReasonEntry.MAX_POSITION + 1), InvalidInputException.class);
    failure(reasons.revise(moderator(), "prohibited-item", Optional.empty(), "Outro",
        List.of(ModerationDecision.BLOCK), 0), AccessDeniedException.class);
  }

  @Test
  void activationWritesOnlyWhenTheSituationChanges() {
    ModerationReasonEntry off = item(reasons.deactivate(admin(), "wrong-category", Optional.of(0L)));
    assertFalse(off.definition().active());
    assertEquals(1, off.version());
    assertEquals(1, item(reasons.deactivate(admin(), "wrong-category", Optional.of(1L))).version(),
        "já inativo, nada é gravado");
    failure(reasons.activate(admin(), "wrong-category", Optional.of(0L)), VersionMismatchException.class);
    ModerationReasonEntry on = item(reasons.activate(admin(), "wrong-category", Optional.of(1L)));
    assertTrue(on.definition().active());
    assertEquals(2, on.version());
    assertEquals(item(reasons.get(admin(), "wrong-category")), on);
    failure(reasons.get(moderator(), "wrong-category"), AccessDeniedException.class);
  }

  @Test
  void eachTenantHasItsOwnCatalog() {
    UUID otherTenant = UUID.randomUUID();
    Actor otherAdmin = new Actor(otherTenant, Optional.empty(), "admin-2",
        Set.of(Capability.MANAGE_MODERATION_REASONS));
    Actor otherModerator = new Actor(otherTenant, Optional.empty(), "mod-2", Set.of(Capability.MODERATE_PRODUCTS));
    assertEquals(List.of(), item(reasons.list(otherModerator, false, Optional.empty())),
        "tenant novo começa sem motivos");
    item(reasons.create(otherAdmin, "wrong-category", "Categoria errada", List.of(ModerationDecision.REJECT),
        Optional.empty()));
    assertEquals("Categoria errada",
        item(reasons.list(otherModerator, false, Optional.empty())).get(0).definition().label());
    assertEquals("Categoria incorreta", item(reasons.get(admin(), "wrong-category")).definition().label());
    failure(reasons.get(otherAdmin, "prohibited-item"), NotFoundException.class);
  }

  @Test
  void theCeilingCountsActiveReasonsSoRetiringOneFreesRoom() {
    for (int i = item(reasons.list(admin(), true, Optional.empty()))
        .size(); i < ModerationReasonService.MAX_ACTIVE_PER_TENANT; i++)
      create("reason-" + letters(i));
    DomainRuleViolation full = failure(reasons.create(admin(), "one-more", "Mais um",
        List.of(ModerationDecision.REJECT), Optional.empty()), DomainRuleViolation.class);
    assertEquals("moderation-reason-limit", full.code());
    assertEquals(List.of(String.valueOf(ModerationReasonService.MAX_ACTIVE_PER_TENANT)), full.reason().params());

    item(reasons.deactivate(admin(), "wrong-category", Optional.empty()));
    create("one-more");
    assertEquals("moderation-reason-limit",
        failure(reasons.activate(admin(), "wrong-category", Optional.empty()), DomainRuleViolation.class)
            .code(),
        "reativar também ocupa uma vaga de ativo");
    assertFalse(item(reasons.get(admin(), "wrong-category")).definition().active());
    assertTrue(item(reasons.activate(admin(), "one-more", Optional.empty())).definition().active(),
        "o motivo já ativo não grava nem confere o teto");

    item(reasons.deactivate(admin(), "one-more", Optional.empty()));
    assertTrue(item(reasons.activate(admin(), "wrong-category", Optional.empty())).definition().active());
  }

  @Test
  void theWholeCatalogHasAProtectionCeilingAboveTheActiveOne() {
    for (int i = item(reasons.list(admin(), true, Optional.empty()))
        .size(); i < ModerationReasonService.MAX_PER_TENANT; i++) {
      String code = "reason-" + letters(i);
      create(code);
      item(reasons.deactivate(admin(), code, Optional.empty()));
    }
    DomainRuleViolation full = failure(reasons.create(admin(), "one-more", "Mais um",
        List.of(ModerationDecision.REJECT), Optional.empty()), DomainRuleViolation.class);
    assertEquals("moderation-reason-catalog-limit", full.code());
    assertEquals(List.of(String.valueOf(ModerationReasonService.MAX_PER_TENANT)), full.reason().params());
    assertTrue(item(reasons.activate(admin(), "reason-" + letters(10), Optional.empty())).definition().active(),
        "reativar não cria linha e continua possível");
  }

  private void create(String code) {
    item(reasons.create(admin(), code, "Motivo " + code, List.of(ModerationDecision.REJECT), Optional.empty()));
  }

  /** Código só com letras, como o formato exige: 0 vira "a", 26 vira "ba". */
  static String letters(int n) {
    StringBuilder code = new StringBuilder();
    do {
      code.insert(0, (char) ('a' + n % 26));
      n /= 26;
    } while (n > 0);
    return code.toString();
  }
}
