package dev.marketplace.cataloginventory.security;

public class TenantNotAuthorizedException extends RuntimeException {
    public TenantNotAuthorizedException(String message) {
        super(message);
    }
}
