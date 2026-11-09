package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.Product;
import java.util.Arrays;
import java.util.Optional;

/**
 * Ordem da listagem de produtos: coluna e sentido, com o id no mesmo sentido
 * como desempate. O texto de cada ordem é o valor do parâmetro {@code sort} da
 * API; o prefixo {@code -} indica ordem decrescente.
 */
public enum ProductSort {
    UPDATED_AT("updatedAt", Column.UPDATED_AT), UPDATED_AT_DESC("-updatedAt", Column.UPDATED_AT),
    CREATED_AT("createdAt", Column.CREATED_AT), CREATED_AT_DESC("-createdAt", Column.CREATED_AT),
    TITLE("title", Column.TITLE), TITLE_DESC("-title", Column.TITLE);

    /** Ordem sem {@code sort}: atualizados por último primeiro. */
    public static final ProductSort DEFAULT = UPDATED_AT_DESC;

    /**
     * Coluna ordenada; o título ordena por {@link ProductText#titleKey}, sem
     * diferenciar maiúsculas e acentos.
     */
    public enum Column {
        UPDATED_AT, CREATED_AT, TITLE
    }

    private final String token;
    private final Column column;

    ProductSort(String token, Column column) {
        this.token = token;
        this.column = column;
    }

    /** Valor do parâmetro {@code sort} que escolhe esta ordem. */
    public String token() {
        return token;
    }

    public Column column() {
        return column;
    }

    public boolean descending() {
        return token.startsWith("-");
    }

    /**
     * Converte o parâmetro {@code sort}; ausente ou em branco vale
     * {@link #DEFAULT} e valor desconhecido lança {@link InvalidInputException}.
     */
    public static ProductSort parse(Optional<String> value) {
        if (value.isEmpty() || value.get().isBlank())
            return DEFAULT;
        return Arrays.stream(values()).filter(sort -> sort.token.equals(value.get())).findFirst()
                .orElseThrow(() -> new InvalidInputException("sort inválido: " + value.get()
                        + "; use updatedAt, -updatedAt, createdAt, -createdAt, title ou -title"));
    }

    /**
     * Valor da coluna ordenada no produto, como o cursor o guarda: instante ISO
     * 8601 nas datas e {@link ProductText#titleKey} no título.
     */
    String keyOf(Product product) {
        return switch (column) {
            case UPDATED_AT -> product.updatedAt().toString();
            case CREATED_AT -> product.createdAt().toString();
            case TITLE -> ProductText.titleKey(product.title());
        };
    }
}
