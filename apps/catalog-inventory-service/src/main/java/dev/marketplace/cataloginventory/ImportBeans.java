package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.application.CategoryRepository;
import dev.marketplace.cataloginventory.application.ImportedProductCreation;
import dev.marketplace.cataloginventory.application.ImportedSkuUpdate;
import dev.marketplace.cataloginventory.application.InventoryService;
import dev.marketplace.cataloginventory.application.OfferRepository;
import dev.marketplace.cataloginventory.application.OfferService;
import dev.marketplace.cataloginventory.application.ProductImportRepository;
import dev.marketplace.cataloginventory.application.ProductImportService;
import dev.marketplace.cataloginventory.application.ProductRepository;
import dev.marketplace.cataloginventory.application.ProductService;
import dev.marketplace.cataloginventory.cache.CacheBacked;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * Raiz de composição da importação por planilha: o lote, a atualização de SKU existente e a criação de produto a
 * partir de um grupo de linhas, as duas pelos casos de uso de produto, oferta e estoque.
 */
public class ImportBeans {
  @Produces
  @ApplicationScoped
  ImportedSkuUpdate importedSkuUpdate(ProductRepository products, OfferRepository offers, OfferService offerService,
      InventoryService inventoryService) {
    return new ImportedSkuUpdate(products, offers, offerService, inventoryService);
  }

  @Produces
  @ApplicationScoped
  ImportedProductCreation importedProductCreation(@CacheBacked CategoryRepository categories,
      ProductService productService, OfferService offerService, InventoryService inventoryService) {
    return new ImportedProductCreation(categories, productService, offerService, inventoryService);
  }

  @Produces
  @ApplicationScoped
  ProductImportService productImportService(ProductImportRepository imports, ProductRepository products,
      ImportedSkuUpdate skuUpdate, ImportedProductCreation productCreation, Clock clock) {
    return new ProductImportService(imports, products, skuUpdate, productCreation, clock);
  }
}
