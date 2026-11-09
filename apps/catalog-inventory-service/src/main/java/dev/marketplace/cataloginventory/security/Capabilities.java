package dev.marketplace.cataloginventory.security;

import dev.marketplace.cataloginventory.application.Capability;

/**
 * Nomes das {@link Capability} para o {@code @PermissionsAllowed} dos endpoints, que exige constante em tempo de
 * compilação. O endpoint declara a capacidade, e quem a tem é decisão só do {@link CallerContext}; o teste confere que
 * cada nome é uma capacidade.
 */
public final class Capabilities {
    public static final String MANAGE_OWN_PRODUCTS = "MANAGE_OWN_PRODUCTS";
    public static final String MODERATE_PRODUCTS = "MODERATE_PRODUCTS";
    public static final String MANAGE_TAXONOMY = "MANAGE_TAXONOMY";
    public static final String READ_TENANT_CATALOG = "READ_TENANT_CATALOG";
    public static final String READ_SELLER_ELIGIBILITY = "READ_SELLER_ELIGIBILITY";
    public static final String MANAGE_RESERVATIONS = "MANAGE_RESERVATIONS";
    public static final String MANAGE_MODERATION_REASONS = "MANAGE_MODERATION_REASONS";

    private Capabilities() {
    }
}
