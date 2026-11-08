package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Regras de conteúdo do produto contra os atributos efetivos da categoria.
 * Retorna motivos estáveis
 * (ex.: {@code missing-attribute:cor}) para o seller e o backoffice, em vez de
 * falhar no primeiro.
 */
public final class PublishingPolicy {
    private PublishingPolicy() {
    }

    /**
     * Valores presentes que a categoria não aceita; vale para rascunhos e produtos
     * publicados.
     */
    public static List<String> valueViolations(Map<String, String> productAttributes, List<Sku> skus,
            List<AttributeDefinition> effective) {
        List<String> reasons = new ArrayList<>(scope(productAttributes, effective, false, ""));
        for (Sku sku : skus)
            reasons.addAll(scope(sku.variantAttributes(), effective, true, sku.sellerSku() + ":"));
        return reasons;
    }

    /**
     * Tudo o que impede o produto de ir para moderação ou de ser aprovado; todo SKU
     * precisa de oferta.
     */
    public static List<String> publishingViolations(Map<String, String> productAttributes, List<Sku> skus,
            List<AttributeDefinition> effective, boolean categoryActive,
            Set<UUID> offeredSkus) {
        List<String> reasons = new ArrayList<>();
        if (!categoryActive)
            reasons.add("category-inactive");
        if (skus.isEmpty())
            reasons.add("no-sku");
        for (Sku sku : skus)
            if (!offeredSkus.contains(sku.id()))
                reasons.add("missing-offer:" + sku.sellerSku());
        reasons.addAll(valueViolations(productAttributes, skus, effective));
        for (AttributeDefinition d : effective) {
            if (!d.required())
                continue;
            if (!d.variant() && !productAttributes.containsKey(d.code()))
                reasons.add("missing-attribute:" + d.code());
            if (d.variant()) {
                for (Sku sku : skus) {
                    if (!sku.variantAttributes().containsKey(d.code()))
                        reasons.add("missing-variant-attribute:" + sku.sellerSku() + ":" + d.code());
                }
            }
        }
        Set<Map<String, String>> combinations = new HashSet<>();
        for (Sku sku : skus) {
            if (!combinations.add(sku.variantAttributes()))
                reasons.add("duplicate-variant:" + sku.sellerSku());
        }
        return reasons;
    }

    /**
     * Compara os valores com as definições do escopo ({@code variant}: produto
     * ou variante). Gera {@code unknown-attribute:<chave>} sem definição e
     * {@code invalid-attribute:<chave>} para valor recusado; nas variantes a
     * chave leva o prefixo do SKU.
     */
    private static List<String> scope(Map<String, String> values, List<AttributeDefinition> effective, boolean variant,
            String prefix) {
        List<String> reasons = new ArrayList<>();
        values.forEach((code, value) -> {
            AttributeDefinition definition = effective.stream()
                    .filter(d -> d.code().equals(code) && d.variant() == variant).findFirst().orElse(null);
            if (definition == null)
                reasons.add("unknown-attribute:" + prefix + code);
            else if (!definition.accepts(value))
                reasons.add("invalid-attribute:" + prefix + code);
        });
        return reasons;
    }
}
