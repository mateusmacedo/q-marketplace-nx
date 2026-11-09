package dev.marketplace.cataloginventory.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Forma de comparação do texto de produto, usada na busca {@code q} e na ordem
 * por título: minúsculas, sem acentos, com as variantes de compatibilidade
 * (ligaturas, larguras) reduzidas à letra comum e espaços simples.
 */
public final class ProductText {
    /** Pontos de código da chave do título: o dobro do título máximo. */
    public static final int TITLE_KEY_MAX_CODE_POINTS = 400;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ProductText() {
    }

    /**
     * Chave de busca e de ordem do título: a forma normalizada, cortada em
     * {@link #TITLE_KEY_MAX_CODE_POINTS}.
     */
    public static String titleKey(String title) {
        // WHY: a decomposição de compatibilidade pode multiplicar o tamanho (ﷺ vira 18 letras), e a chave vai no
        // cursor; o corte limita o cursor sem quebrar um par substituto.
        String key = normalize(title);
        if (key.codePointCount(0, key.length()) <= TITLE_KEY_MAX_CODE_POINTS)
            return key;
        return key.substring(0, key.offsetByCodePoints(0, TITLE_KEY_MAX_CODE_POINTS));
    }

    /**
     * Normaliza o texto; o resultado vazio indica texto sem letra, número nem
     * símbolo comparável.
     */
    public static String normalize(String text) {
        // WHY: minúsculas antes da decomposição, porque o İ vira i com ponto combinante, que a remoção das marcas tira;
        // e depois dela, porque a compatibilidade pode devolver maiúscula (ℌ vira H).
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);
        return SPACES.matcher(MARKS.matcher(decomposed).replaceAll("")).replaceAll(" ").strip();
    }
}
