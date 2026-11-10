package dev.marketplace.cataloginventory;

import dev.marketplace.cataloginventory.application.InventoryRepository;
import dev.marketplace.cataloginventory.application.ReservationRepository;
import dev.marketplace.cataloginventory.application.ReservationService;
import dev.marketplace.cataloginventory.config.CatalogConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/** Raiz de composição das reservas de estoque usadas pelo checkout e pelo OMS, com os prazos da configuração. */
public class ReservationBeans {
  @Produces
  @ApplicationScoped
  ReservationService reservationService(ReservationRepository reservations, InventoryRepository inventory,
      CatalogConfig config, Clock clock) {
    CatalogConfig.Reservations r = config.reservations();
    return new ReservationService(reservations, inventory,
        new ReservationService.Settings(r.defaultTtl(), r.maxTtl(), r.maxLifetime()), clock);
  }
}
