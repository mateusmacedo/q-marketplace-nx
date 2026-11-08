package dev.marketplace.cataloginventory.application;

public class DuplicateException extends RuntimeException {
    private final String code;

    public DuplicateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
