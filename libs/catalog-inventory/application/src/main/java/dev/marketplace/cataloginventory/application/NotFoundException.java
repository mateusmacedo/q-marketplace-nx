package dev.marketplace.cataloginventory.application;

/**
 * Recurso inexistente ou fora do alcance do chamador; os dois casos respondem
 * igual para não revelar existência.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String resource) {
        super(resource + " não encontrado");
    }
}
