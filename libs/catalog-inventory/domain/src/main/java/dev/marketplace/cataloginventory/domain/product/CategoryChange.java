package dev.marketplace.cataloginventory.domain.product;

import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Efeito de trocar a categoria, pela regra da gravação
 * ({@link PublishingPolicy#valueViolations}). Só os SKUs com variação recusada
 * impedem a gravação; os obrigatórios novos viram pendências de publicação.
 */
public record CategoryChange(List<RemovedAttribute> removedAttributes, List<AttributeDefinition> newRequired,
        List<BlockingSku> blockingSkus) {
    /** Atributo do produto que a categoria nova recusa, com o rótulo e o valor de antes. */
    public record RemovedAttribute(String code, String label, String value, Reason reason) {
    }

    /** SKU cuja variação a categoria nova não aceita, com os motivos. */
    public record BlockingSku(String sellerSku, List<Reason> reasons) {
        public BlockingSku {
            reasons = List.copyOf(reasons);
        }
    }

    public CategoryChange {
        removedAttributes = List.copyOf(removedAttributes);
        newRequired = List.copyOf(newRequired);
        blockingSkus = List.copyOf(blockingSkus);
    }

    /**
     * Compara {@code attributes} e as variações de {@code skus} com os atributos
     * efetivos da categoria nova ({@code target}); {@code current} são os da
     * categoria atual, de onde vêm os rótulos dos atributos que somem.
     */
    public static CategoryChange of(Map<String, String> attributes, List<Sku> skus,
            List<AttributeDefinition> current, List<AttributeDefinition> target) {
        List<RemovedAttribute> removed = new ArrayList<>();
        for (Reason reason : PublishingPolicy.valueViolations(attributes, List.of(), target)) {
            String code = reason.params().getFirst();
            removed.add(new RemovedAttribute(code, label(current, code), attributes.get(code), reason));
        }
        List<AttributeDefinition> required = target.stream().filter(AttributeDefinition::required)
                .filter(t -> current.stream().noneMatch(
                        c -> c.required() && c.variant() == t.variant() && c.code().equals(t.code())))
                .toList();
        List<BlockingSku> blocking = new ArrayList<>();
        for (Sku sku : skus) {
            List<Reason> reasons = PublishingPolicy.valueViolations(Map.of(), List.of(sku), target);
            if (!reasons.isEmpty())
                blocking.add(new BlockingSku(sku.sellerSku(), reasons));
        }
        return new CategoryChange(removed, required, blocking);
    }

    private static String label(List<AttributeDefinition> current, String code) {
        return current.stream().filter(d -> !d.variant() && d.code().equals(code)).map(AttributeDefinition::label)
                .findFirst().orElse(code);
    }
}
