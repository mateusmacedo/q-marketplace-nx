package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.ImportResult.ImportOutcome;
import dev.marketplace.cataloginventory.domain.Money;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductImportServiceTest {
    static final Duration LEASE = Duration.ofMinutes(5);
    final Catalog catalog = new Catalog();
    final ProductImportService imports = catalog.imports;
    final Actor seller = catalog.seller;

    /** Linha de SKU físico da categoria de camisetas, com marca Acme e preço só "por". */
    static ImportRow row(int line, String ref, String sellerSku, String size, String salePrice, int onHand) {
        return row(line, ref, "camisetas", "Camiseta", sellerSku, size, salePrice, onHand);
    }

    static ImportRow row(int line, String ref, String category, String title, String sellerSku, String size,
            String salePrice, int onHand) {
        return new ImportRow(line, ref, category, title, "Algodão", Map.of("marca", "Acme"), sellerSku,
                Map.of("tamanho", size), true, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 300, "BRL", null,
                new BigDecimal(salePrice), onHand, null, null);
    }

    /** Linha com preço e estoque válidos e erro de conversão só nas colunas de produto. */
    static ImportRow productColumnsFailed(int line, String ref, String sellerSku, String productError) {
        return new ImportRow(line, ref, null, null, null, null, sellerSku, null, null, null, null, null, null, "BRL",
                null, new BigDecimal("45"), 7, null, productError);
    }

    /** Envia, reivindica e processa o lote; devolve o relatório do seller. */
    ProductImportService.Report importing(ImportRow... rows) {
        ProductImport job = item(imports.submit(seller, "conteúdo", rows.length));
        ProductImportRepository.Claimed claimed = item(imports.claimNext(LEASE)).orElseThrow();
        assertEquals(job.id(), claimed.job().id());
        item(imports.process(claimed.job(), List.of(rows)));
        return item(imports.get(seller, job.id()));
    }

    static List<ImportOutcome> outcomes(ProductImportService.Report report) {
        return report.lines().stream().map(ImportResult::outcome).toList();
    }

    static UUID skuOf(Product product, String sellerSku) {
        return product.skus().stream().filter(s -> s.sellerSku().equals(sellerSku)).findFirst().orElseThrow().id();
    }

    @Test
    void linesOfTheSameProductCreateOneDraftWithOfferAndStockPerSku() {
        var report = importing(row(2, "camiseta", "CAM-P", "P", "50", 5), row(3, "camiseta", "CAM-M", "M", "55", 0));
        assertEquals(List.of(ImportOutcome.CREATED, ImportOutcome.CREATED), outcomes(report));
        assertEquals(ProductImport.ImportStatus.COMPLETED, report.job().status());
        assertEquals(NOW, report.job().finishedAt());
        UUID productId = report.lines().get(0).productId();
        assertEquals(productId, report.lines().get(1).productId());
        Product product = item(catalog.products.get(seller, productId));
        assertEquals(ProductStatus.DRAFT, product.status());
        assertEquals(List.of("CAM-P", "CAM-M"), product.skus().stream().map(s -> s.sellerSku()).toList());
        UUID p = skuOf(product, "CAM-P"), m = skuOf(product, "CAM-M");
        assertEquals(Money.of("50", "BRL"), item(catalog.offers.get(seller, p)).price().salePrice());
        assertEquals(Money.of("55", "BRL"), item(catalog.offers.get(seller, m)).price().salePrice());
        assertEquals(5, item(catalog.inventory.get(seller, p)).onHand());
        assertEquals(0, item(catalog.inventory.get(seller, m)).onHand());
    }

    @Test
    void existingSkuReceivesOnlyPriceAndStock() {
        Product product = catalog.draft(sku("CAM-P", "P"));
        UUID sku = product.skus().getFirst().id();
        catalog.offer(sku, "50");
        item(catalog.inventory.adjust(seller, sku, Optional.empty(), 3));
        var report = importing(row(2, "outro", "nao-existe", "Outro título", "CAM-P", "G", "45", 7));
        assertEquals(List.of(ImportOutcome.UPDATED), outcomes(report));
        assertEquals(product.id(), report.lines().getFirst().productId());
        assertEquals(Money.of("45", "BRL"), item(catalog.offers.get(seller, sku)).price().salePrice());
        assertEquals(7, item(catalog.inventory.get(seller, sku)).onHand());
        Product unchanged = item(catalog.products.get(seller, product.id()));
        assertEquals("Camiseta", unchanged.title());
        assertEquals(Map.of("tamanho", "P"), unchanged.skus().getFirst().variantAttributes());
    }

    @Test
    void productColumnErrorsOnlyRejectNewSkus() {
        Product product = catalog.draft(sku("CAM-P", "P"));
        String error = "physical: use true ou false, e não 'talvez'";
        var report = importing(productColumnsFailed(2, "camiseta", "CAM-P", error),
                productColumnsFailed(3, "camiseta", "CAM-M", error));
        assertEquals(List.of(ImportOutcome.UPDATED, ImportOutcome.FAILED), outcomes(report));
        assertEquals(product.id(), report.lines().get(0).productId());
        assertEquals(7, item(catalog.inventory.get(seller, product.skus().getFirst().id())).onHand());
        assertEquals(ProductImportService.INVALID_ROW, report.lines().get(1).code());
        assertEquals(error, report.lines().get(1).message());
    }

    @Test
    void equalPriceAndStockLeaveTheSkuUntouched() {
        Product product = catalog.draft(sku("CAM-P", "P"));
        UUID sku = product.skus().getFirst().id();
        catalog.offer(sku, "50");
        item(catalog.inventory.adjust(seller, sku, Optional.empty(), 3));
        int adjustments = catalog.store.adjustments.size();
        var report = importing(row(2, "camiseta", "CAM-P", "P", "50.00", 3));
        assertEquals(List.of(ImportOutcome.UNCHANGED), outcomes(report));
        assertEquals(0, item(catalog.offers.get(seller, sku)).version());
        assertEquals(adjustments, catalog.store.adjustments.size());
    }

    @Test
    void archivedProductFailsWithInvalidTransition() {
        Product product = catalog.draft(sku("CAM-P", "P"));
        item(catalog.products.archive(seller, product.id(), Optional.empty()));
        ImportResult line = importing(row(2, "camiseta", "CAM-P", "P", "50", 3)).lines().getFirst();
        assertEquals(ImportOutcome.FAILED, line.outcome());
        assertEquals("invalid-transition", line.code());
        assertEquals(product.id(), line.productId());
    }

    @Test
    void divergentLinesOfTheSameProductFailTogether() {
        var report = importing(row(2, "camiseta", "CAM-P", "P", "50", 1),
                row(3, "camiseta", "camisetas", "Outro título", "CAM-M", "M", "50", 1));
        assertEquals(List.of(ImportOutcome.FAILED, ImportOutcome.FAILED), outcomes(report));
        report.lines().forEach(line -> assertEquals(ProductImportService.INCONSISTENT_PRODUCT, line.code()));
        assertTrue(catalog.store.products.isEmpty());
    }

    @Test
    void unknownCategoryFailsTheProduct() {
        ImportResult line = importing(row(2, "regata", "regatas", "Regata", "REG-P", "P", "30", 1)).lines()
                .getFirst();
        assertEquals(ImportOutcome.FAILED, line.outcome());
        assertEquals(ProductImportService.CATEGORY_NOT_FOUND, line.code());
        assertTrue(catalog.store.products.isEmpty());
    }

    @Test
    void invalidLineRejectsItsProductButNotTheRestOfTheFile() {
        var report = importing(ImportRow.invalid(2, "camiseta", "CAM-P", "on_hand: número inteiro inválido"),
                row(3, "camiseta", "CAM-M", "M", "50", 1), row(4, "outra", "OUT-P", "P", "40", 1),
                row(5, "outra", "CAM-M", "G", "40", 1));
        assertEquals(List.of(ImportOutcome.FAILED, ImportOutcome.FAILED, ImportOutcome.CREATED, ImportOutcome.FAILED), outcomes(report));
        assertEquals(List.of(ProductImportService.INVALID_ROW, ProductImportService.GROUP_REJECTED),
                report.lines().subList(0, 2).stream().map(ImportResult::code).toList());
        assertEquals(ProductImportService.DUPLICATE_SELLER_SKU, report.lines().get(3).code());
    }

    @Test
    void resumedImportSkipsRecordedLines() {
        ProductImport job = item(imports.submit(seller, "conteúdo", 2));
        ProductImport claimed = item(imports.claimNext(LEASE)).orElseThrow().job();
        assertTrue(item(imports.claimNext(LEASE)).isEmpty(), "lote em processamento não é entregue de novo");
        ImportResult recorded = new ImportResult(2, "CAM-P", ImportOutcome.FAILED, "invalid-row", "gravada antes", null);
        item(catalog.store.recordResults(job.id(), List.of(recorded), NOW));
        catalog.clock.advance(LEASE.plusSeconds(1));
        ProductImport resumed = item(imports.claimNext(LEASE)).orElseThrow().job();
        assertEquals(claimed.id(), resumed.id());
        List<ImportResult> processed = item(imports.process(resumed, List.of(row(2, "camiseta", "CAM-P", "P", "50", 1),
                row(3, "regata", "REG-P", "P", "30", 1))));
        assertEquals(List.of(3), processed.stream().map(ImportResult::lineNumber).toList());
        assertTrue(item(catalog.store.skuIdsBySellerSku(TENANT, catalog.storeId, List.of("CAM-P"))).isEmpty());
        var report = item(imports.get(seller, job.id()));
        assertEquals(recorded, report.lines().getFirst());
        assertEquals(ProductImport.ImportStatus.COMPLETED, report.job().status());
    }

    @Test
    void onlyTheSubmittingStoreSeesTheImport() {
        ProductImport job = item(imports.submit(seller, "conteúdo", 1));
        failure(imports.get(seller(UUID.randomUUID()), job.id()), NotFoundException.class);
        failure(imports.submit(moderator(), "conteúdo", 1), AccessDeniedException.class);
        assertEquals(ProductImport.ImportStatus.PENDING, item(imports.get(seller, job.id())).job().status());
    }
}
