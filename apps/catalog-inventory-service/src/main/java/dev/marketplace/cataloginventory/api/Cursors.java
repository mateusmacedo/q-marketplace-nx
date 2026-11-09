package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.InvalidInputException;
import dev.marketplace.cataloginventory.application.Page;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Cursor opaco (base64url) sobre a chave de ordenação; clientes não devem
 * interpretá-lo.
 */
final class Cursors {
    private Cursors() {
    }

    /** Codifica a chave de ordenação em base64url, sem preenchimento. */
    static String encode(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    /** Cursor da próxima página codificado; nulo na última página. */
    static String next(Page<?> page) {
        return page.nextCursor().map(Cursors::encode).orElse(null);
    }

    /**
     * Decodifica o cursor; ausente ou vazio significa primeira página. Cursor que
     * não é base64url,
     * está em branco, passa de 128 caracteres ou tem o caractere nulo (U+0000)
     * responde 400.
     */
    static Optional<String> decode(String cursor) {
        return decode(cursor, 128);
    }

    /**
     * Como {@link #decode(String)}, com a chave de até {@code maxLength}
     * caracteres, para cursor composto que guarda um valor de coluna, como o
     * título.
     */
    static Optional<String> decode(String cursor, int maxLength) {
        if (cursor == null || cursor.isEmpty())
            return Optional.empty();
        try {
            String key = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            // WHY: a chave vai ao banco como posição, e o PostgreSQL recusa U+0000 em parâmetro de texto com erro de
            // servidor; nenhuma chave emitida o tem, porque nenhuma coluna de texto o guarda.
            if (key.isBlank() || key.length() > maxLength || key.indexOf('\0') >= 0)
                throw new InvalidInputException("cursor inválido");
            return Optional.of(key);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("cursor inválido");
        }
    }
}
