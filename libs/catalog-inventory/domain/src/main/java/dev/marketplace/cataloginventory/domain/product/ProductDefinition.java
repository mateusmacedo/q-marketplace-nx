package dev.marketplace.cataloginventory.domain.product;

import java.util.List;
import java.util.Map;

/**
 * O que o seller define do produto: a moeda das ofertas, o título, a descrição, os atributos do produto e os SKUs. As
 * validações ficam com o {@link Product}.
 */
public record ProductDefinition(String currency, String title, String description, Map<String, String> attributes,
    List<Sku> skus) {
}
