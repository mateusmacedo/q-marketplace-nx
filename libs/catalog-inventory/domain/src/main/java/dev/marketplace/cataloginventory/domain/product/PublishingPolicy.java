package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static dev.marketplace.cataloginventory.domain.DomainReasons.*;
import static dev.marketplace.cataloginventory.domain.product.PublishingIssue.at;

/**
 * Regras de conteúdo do produto contra os atributos efetivos da categoria.
 * Retorna motivos estáveis (ex.: {@code missing-attribute:cor}) para o seller e
 * o backoffice, em vez de falhar no primeiro; o motivo que conhece o atributo
 * já leva o texto com o rótulo dele.
 */
public final class PublishingPolicy {
  private PublishingPolicy() {
  }

  /**
   * Valores presentes que a categoria não aceita; vale para rascunhos e produtos
   * publicados.
   */
  public static List<Reason> valueViolations(Map<String, String> productAttributes, List<Sku> skus,
      List<AttributeDefinition> effective) {
    return reasons(valueIssues(productAttributes, skus, effective));
  }

  /** Como {@link #valueViolations}, com o campo de cada pendência. */
  public static List<PublishingIssue> valueIssues(Map<String, String> productAttributes, List<Sku> skus,
      List<AttributeDefinition> effective) {
    List<PublishingIssue> issues = new ArrayList<>(scope(productAttributes, effective, null));
    for (Sku sku : skus)
      issues.addAll(scope(sku.variantAttributes(), effective, sku.sellerSku()));
    return issues;
  }

  /**
   * Tudo o que impede o produto de ir para moderação ou de ser aprovado; todo SKU
   * precisa de oferta.
   */
  public static List<Reason> publishingViolations(Map<String, String> productAttributes, List<Sku> skus,
      List<AttributeDefinition> effective, boolean categoryActive,
      Set<UUID> offeredSkus) {
    return reasons(publishingIssues(productAttributes, skus, effective, categoryActive, offeredSkus));
  }

  /** Como {@link #publishingViolations}, na mesma ordem, com o campo de cada pendência. */
  public static List<PublishingIssue> publishingIssues(Map<String, String> productAttributes, List<Sku> skus,
      List<AttributeDefinition> effective, boolean categoryActive,
      Set<UUID> offeredSkus) {
    List<PublishingIssue> issues = new ArrayList<>();
    if (!categoryActive)
      issues.add(at("categoryId", Reason.of(CATEGORY_INACTIVE)));
    if (skus.isEmpty())
      issues.add(at("skus", Reason.of(NO_SKU)));
    for (Sku sku : skus)
      if (!offeredSkus.contains(sku.id()))
        issues.add(at(PublishingIssue.sku(sku.sellerSku()) + ".offer",
            Reason.of(MISSING_OFFER, sku.sellerSku())));
    issues.addAll(valueIssues(productAttributes, skus, effective));
    for (AttributeDefinition d : effective) {
      if (!d.required())
        continue;
      if (!d.variant() && !productAttributes.containsKey(d.code()))
        issues.add(at(PublishingIssue.attribute(d.code()), Reason.of(MISSING_ATTRIBUTE, d.code())
            .withMessage("Preencha o atributo " + d.label() + ".")));
      if (d.variant()) {
        for (Sku sku : skus) {
          if (!sku.variantAttributes().containsKey(d.code()))
            issues.add(at(PublishingIssue.variant(sku.sellerSku(), d.code()),
                Reason.of(MISSING_VARIANT_ATTRIBUTE, sku.sellerSku(), d.code())
                    .withMessage("Preencha " + d.label() + " no SKU " + sku.sellerSku() + ".")));
        }
      }
    }
    Set<Map<String, String>> combinations = new HashSet<>();
    for (Sku sku : skus) {
      if (!combinations.add(sku.variantAttributes()))
        issues.add(at(PublishingIssue.variant(sku.sellerSku()), Reason.of(DUPLICATE_VARIANT, sku.sellerSku())));
    }
    return issues;
  }

  /**
   * Compara os valores com as definições do escopo: produto com {@code sku}
   * nulo, variante do SKU caso contrário. Gera {@code unknown-attribute} sem
   * definição e {@code invalid-attribute} para valor recusado; nas variantes o
   * SKU vem antes do código do atributo nos parâmetros.
   */
  private static List<PublishingIssue> scope(Map<String, String> values, List<AttributeDefinition> effective,
      String sku) {
    boolean variant = sku != null;
    List<PublishingIssue> issues = new ArrayList<>();
    values.forEach((code, value) -> {
      AttributeDefinition definition = effective.stream()
          .filter(d -> d.code().equals(code) && d.variant() == variant).findFirst().orElse(null);
      String field = variant ? PublishingIssue.variant(sku, code) : PublishingIssue.attribute(code);
      if (definition == null)
        issues.add(at(field,
            variant ? Reason.of(UNKNOWN_ATTRIBUTE, sku, code) : Reason.of(UNKNOWN_ATTRIBUTE, code)));
      else if (!definition.accepts(value))
        issues.add(at(field, variant
            ? Reason.of(INVALID_ATTRIBUTE, sku, code).withMessage(
                "O valor de " + definition.label() + " no SKU " + sku + " não é aceito pela categoria.")
            : Reason.of(INVALID_ATTRIBUTE, code).withMessage(
                "O valor do atributo " + definition.label() + " não é aceito pela categoria.")));
    });
    return issues;
  }

  private static List<Reason> reasons(List<PublishingIssue> issues) {
    return issues.stream().map(PublishingIssue::reason).toList();
  }
}
