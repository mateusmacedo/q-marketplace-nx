package dev.marketplace.cataloginventory.domain.category;

import java.util.List;

/**
 * O que se define da categoria ao criá-la ou restaurá-la: o código, o nome e os atributos próprios, sem os herdados.
 * As validações ficam com a {@link Category}.
 */
public record CategoryDefinition(String code, String name, List<AttributeDefinition> attributes) {
}
