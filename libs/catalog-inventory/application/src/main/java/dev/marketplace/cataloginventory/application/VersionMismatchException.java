package dev.marketplace.cataloginventory.application;

/** A versão informada pelo cliente (If-Match) não é a atual. */
public class VersionMismatchException extends RuntimeException {
    public VersionMismatchException(long current) {
        super("Versão desatualizada; a versão atual é " + current);
    }
}
