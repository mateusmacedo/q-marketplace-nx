package dev.marketplace.cataloginventory.imports;

import dev.marketplace.cataloginventory.application.ImportRow;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Planilha de importação de produtos: CSV RFC 4180 em UTF-8 (BOM opcional)
 * com o cabeçalho {@link #COLUMNS}, exato e nesta ordem. Valida a estrutura do
 * arquivo e converte cada registro de dados em {@link ImportRow}.
 */
public final class ProductCsv {
    public static final List<String> COLUMNS = List.of("product_ref", "category_code", "title", "description",
            "attributes", "seller_sku", "variant_attributes", "physical", "height_cm", "width_cm", "length_cm",
            "weight_g", "currency", "list_price", "sale_price", "on_hand");
    /** Colunas que o SKU já existente na loja usa; erro só nas demais não o afeta. */
    private static final Set<String> PRICE_AND_STOCK_COLUMNS = Set.of("seller_sku", "currency", "list_price",
            "sale_price", "on_hand");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,9}");
    private static final int SHOWN_MAX_LENGTH = 64;

    /** Arquivo aprovado: texto sem BOM e quantidade de linhas de dados. */
    public record Validated(String content, int totalLines) {
    }

    private ProductCsv() {
    }

    /**
     * Decodifica e valida codificação, sintaxe, cabeçalho e quantidade de
     * linhas; recusa com {@link InvalidImportFileException} e os motivos
     * encontrados.
     */
    public static Validated validate(byte[] body, int maxRows) {
        String content = decode(body);
        List<CsvParser.Record> records;
        try {
            records = CsvParser.parse(content);
        } catch (CsvParser.SyntaxException e) {
            throw new InvalidImportFileException("CSV malformado. " + e.getMessage(),
                    List.of("malformed-csv:" + e.line()));
        }
        if (records.isEmpty())
            throw new InvalidImportFileException("Arquivo vazio", List.of("empty-file"));
        List<String> reasons = new ArrayList<>(headerReasons(records.getFirst().fields()));
        int rows = records.size() - 1;
        if (rows == 0)
            reasons.add("no-rows");
        if (rows > maxRows)
            reasons.add("too-many-rows:" + maxRows);
        if (!reasons.isEmpty())
            throw new InvalidImportFileException("Arquivo de importação inválido. Cabeçalho esperado: "
                    + String.join(",", COLUMNS) + "; de 1 a " + maxRows + " linhas de dados", reasons);
        return new Validated(content, rows);
    }

    /** Converte as linhas de dados de um conteúdo já validado, na ordem do arquivo. */
    public static List<ImportRow> rows(String content) {
        return CsvParser.parse(content).stream().skip(1).map(ProductCsv::toRow).toList();
    }

    private static String decode(byte[] body) {
        if (body == null || body.length == 0)
            throw new InvalidImportFileException("Arquivo vazio", List.of("empty-file"));
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidImportFileException("O arquivo deve estar em UTF-8", List.of("invalid-encoding"));
        }
        // WHY: o PostgreSQL não armazena o caractere NUL em colunas text.
        if (text.indexOf('\0') >= 0)
            throw new InvalidImportFileException("O arquivo contém o caractere NUL", List.of("invalid-encoding"));
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * Motivos de um cabeçalho diferente de {@link #COLUMNS}: coluna repetida,
     * desconhecida ou ausente, ou {@code column-order} quando só a ordem difere.
     */
    private static List<String> headerReasons(List<String> header) {
        List<String> names = header.stream().map(String::strip).toList();
        if (names.equals(COLUMNS))
            return List.of();
        List<String> reasons = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name))
                reasons.add("duplicate-column:" + shown(name));
            else if (!COLUMNS.contains(name))
                reasons.add("unknown-column:" + shown(name));
        }
        COLUMNS.stream().filter(column -> !names.contains(column)).forEach(column -> reasons.add("missing-column:"
                + column));
        if (reasons.isEmpty())
            reasons.add("column-order");
        return reasons;
    }

    /**
     * Converte o registro; coluna a mais ou a menos e valor malformado em
     * coluna de preço ou estoque viram {@link ImportRow#invalid} com todos os
     * erros, e erro só em coluna de produto vai para {@code productError}.
     */
    private static ImportRow toRow(CsvParser.Record record) {
        if (record.fields().size() != COLUMNS.size())
            return ImportRow.invalid(record.line(), null, null,
                    "Linha com " + record.fields().size() + " colunas; o cabeçalho tem " + COLUMNS.size());
        Fields f = new Fields(record.fields());
        String productRef = f.text("product_ref");
        String categoryCode = f.text("category_code");
        String title = f.text("title");
        String description = f.text("description");
        Map<String, String> attributes = f.pairs("attributes");
        String sellerSku = f.required("seller_sku");
        Map<String, String> variantAttributes = f.pairs("variant_attributes");
        Boolean physical = f.bool("physical");
        BigDecimal heightCm = f.decimal("height_cm", false);
        BigDecimal widthCm = f.decimal("width_cm", false);
        BigDecimal lengthCm = f.decimal("length_cm", false);
        Integer weightGrams = f.integer("weight_g", false);
        String currency = f.required("currency");
        BigDecimal listPrice = f.decimal("list_price", false);
        BigDecimal salePrice = f.decimal("sale_price", true);
        Integer onHand = f.integer("on_hand", true);
        String errors = f.errors.isEmpty() ? null : String.join("; ", f.errors);
        if (f.failed.stream().anyMatch(PRICE_AND_STOCK_COLUMNS::contains))
            return ImportRow.invalid(record.line(), productRef, sellerSku, errors);
        return new ImportRow(record.line(), productRef, categoryCode, title, description, attributes, sellerSku,
                variantAttributes, physical, heightCm, widthCm, lengthCm, weightGrams, currency, listPrice, salePrice,
                onHand, null, errors);
    }

    private static String shown(String value) {
        return value.length() <= SHOWN_MAX_LENGTH ? value : value.substring(0, SHOWN_MAX_LENGTH) + "…";
    }

    /** Leitura tipada dos campos de um registro, acumulando os erros de conversão. */
    private static final class Fields {
        private final List<String> values;
        private final List<String> errors = new ArrayList<>();
        private final Set<String> failed = new HashSet<>();

        Fields(List<String> values) {
            this.values = values;
        }

        private void fail(String column, String message) {
            failed.add(column);
            errors.add(column + ": " + message);
        }

        /** Valor sem espaços nas pontas; vazio vira nulo. */
        String text(String column) {
            String value = values.get(COLUMNS.indexOf(column)).strip();
            return value.isEmpty() ? null : value;
        }

        String required(String column) {
            String value = text(column);
            if (value == null)
                fail(column, "obrigatório");
            return value;
        }

        BigDecimal decimal(String column, boolean required) {
            String value = required ? required(column) : text(column);
            if (value == null)
                return null;
            if (DECIMAL.matcher(value).matches())
                return new BigDecimal(value);
            fail(column, "número inválido '" + shown(value) + "'; use ponto como separador decimal");
            return null;
        }

        Integer integer(String column, boolean required) {
            String value = required ? required(column) : text(column);
            if (value == null)
                return null;
            if (INTEGER.matcher(value).matches())
                return Integer.valueOf(value);
            fail(column, "número inteiro inválido '" + shown(value) + "'");
            return null;
        }

        Boolean bool(String column) {
            String value = text(column);
            if (value == null)
                return null;
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))
                return Boolean.valueOf(value);
            fail(column, "use true ou false, e não '" + shown(value) + "'");
            return null;
        }

        /** Pares {@code chave=valor} separados por ';', sem escape; vazio vira mapa vazio. */
        Map<String, String> pairs(String column) {
            String value = text(column);
            Map<String, String> pairs = new LinkedHashMap<>();
            if (value == null)
                return pairs;
            for (String part : value.split(";")) {
                String pair = part.strip();
                if (pair.isEmpty())
                    continue;
                int separator = pair.indexOf('=');
                String key = separator < 0 ? "" : pair.substring(0, separator).strip();
                if (key.isEmpty())
                    fail(column, "par sem chave=valor em '" + shown(pair) + "'");
                else if (pairs.put(key, pair.substring(separator + 1).strip()) != null)
                    fail(column, "chave repetida '" + shown(key) + "'");
            }
            return pairs;
        }
    }
}
