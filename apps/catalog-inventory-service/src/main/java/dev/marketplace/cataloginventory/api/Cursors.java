package dev.marketplace.cataloginventory.api;

import dev.marketplace.cataloginventory.application.InvalidInputException;
import dev.marketplace.cataloginventory.application.Page;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

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
     * está em branco ou passa de 128 caracteres responde 400.
     */
    static Optional<String> decode(String cursor) {
        if (cursor == null || cursor.isEmpty())
            return Optional.empty();
        try {
            String key = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (key.isBlank() || key.length() > 128)
                throw new InvalidInputException("cursor inválido");
            return Optional.of(key);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("cursor inválido");
        }
    }

    /**
     * Como {@link #decode}, e exige que a chave seja um UUID (cursor de produtos);
     * caso contrário
     * responde 400.
     */
    static Optional<String> decodeUuid(String cursor) {
        Optional<String> key = decode(cursor);
        key.ifPresent(k -> {
            try {
                UUID.fromString(k);
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException("cursor inválido");
            }
        });
        return key;
    }
}
