package dev.marketplace.cataloginventory.security;

import dev.marketplace.cataloginventory.observability.Refusal;

public class TenantNotAuthorizedException extends RuntimeException implements Refusal {
    public TenantNotAuthorizedException(String message) {
        super(message);
    }
}
