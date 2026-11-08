package dev.marketplace.cataloginventory.imports;

import dev.marketplace.cataloginventory.application.ImportRow;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ProductCsvTest {
    static final String HEADER = String.join(",", ProductCsv.COLUMNS);

    static List<String> reasons(byte[] body, int maxRows) {
        return assertThrows(InvalidImportFileException.class, () -> ProductCsv.validate(body, maxRows)).reasons();
    }

    static List<String> reasons(String csv) {
        return reasons(csv.getBytes(UTF_8), 10);
    }

    static ImportRow onlyRow(String line) {
        return ProductCsv.rows(ProductCsv.validate((HEADER + "\n" + line).getBytes(UTF_8), 10).content()).getFirst();
    }

    @Test
    void bomIsStrippedAndTheRowIsConverted() {
        String csv = "﻿" + HEADER + "\r\ncamiseta,camisetas,Camiseta,\"Algodão, macia\",marca=Acme; ,CAM-P,"
                + "tamanho=P,TRUE,10,20,30.5,350,BRL,79.90,59.90,5\r\n\r\n";
        ProductCsv.Validated file = ProductCsv.validate(csv.getBytes(UTF_8), 10);
        assertEquals(1, file.totalLines());
        assertTrue(file.content().startsWith("product_ref,"));
        ImportRow row = ProductCsv.rows(file.content()).getFirst();
        assertNull(row.error());
        assertNull(row.productError());
        assertEquals(2, row.lineNumber());
        assertEquals("Algodão, macia", row.description());
        assertEquals(Map.of("marca", "Acme"), row.attributes());
        assertEquals(Map.of("tamanho", "P"), row.variantAttributes());
        assertEquals(Boolean.TRUE, row.physical());
        assertEquals(new BigDecimal("30.5"), row.lengthCm());
        assertEquals(new BigDecimal("79.90"), row.listPrice());
        assertEquals(5, row.onHand());
    }

    @Test
    void blankOptionalColumnsBecomeNull() {
        ImportRow row = onlyRow(",,,,,CAM-P,,,,,,,BRL,,59.90,0");
        assertNull(row.error());
        assertNull(row.productRef());
        assertNull(row.physical());
        assertNull(row.listPrice());
        assertEquals(Map.of(), row.attributes());
    }

    @Test
    void malformedValuesInvalidateOnlyTheRow() {
        ImportRow row = onlyRow("camiseta,camisetas,Camiseta,,marca,CAM-P,cor=azul;cor=verde,talvez,10,20,30,350,BRL,,"
                + "59;90,9x");
        assertEquals("camiseta", row.productRef());
        assertEquals("CAM-P", row.sellerSku());
        for (String column : List.of("attributes:", "variant_attributes:", "physical:", "sale_price:", "on_hand:"))
            assertTrue(row.error().contains(column), row.error());
        assertTrue(onlyRow("camiseta,camisetas").error().contains("2 colunas"));
        assertTrue(onlyRow("camiseta,camisetas,Camiseta,,,,,,,,,,,,,").error().contains("seller_sku: obrigatório"));
    }

    @Test
    void productColumnErrorsKeepPriceAndStock() {
        ImportRow row = onlyRow("camiseta,camisetas,Camiseta,,marca,CAM-P,,talvez,1x,,,,BRL,,59.90,3");
        assertNull(row.error());
        assertEquals(new BigDecimal("59.90"), row.salePrice());
        assertEquals(3, row.onHand());
        for (String column : List.of("attributes:", "physical:", "height_cm:"))
            assertTrue(row.productError().contains(column), row.productError());
    }

    @Test
    void structuralProblemsRejectTheWholeFile() {
        assertEquals(List.of("empty-file"), reasons(new byte[0], 10));
        assertEquals(List.of("empty-file"), reasons("﻿\n\n"));
        assertEquals(List.of("no-rows"), reasons(HEADER + "\n"));
        assertEquals(List.of("column-order"), reasons(HEADER.replace("title,description", "description,title")
                + "\nx"));
        List<String> unknown = reasons("sku,price\nA,1");
        assertTrue(unknown.containsAll(List.of("unknown-column:sku", "missing-column:product_ref")), unknown.toString());
        assertEquals(List.of("too-many-rows:2"), reasons((HEADER + "\n" + "a\n".repeat(3)).getBytes(UTF_8), 2));
        assertEquals(List.of("malformed-csv:2"), reasons(HEADER + "\n\"aberto\n"));
        assertEquals(List.of("invalid-encoding"), reasons(new byte[] { 'a', (byte) 0xC3, 0x28 }, 10));
    }
}
