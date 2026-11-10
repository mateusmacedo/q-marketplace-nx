package dev.marketplace.cataloginventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marketplace.cataloginventory.config.CatalogConfig;
import dev.marketplace.cataloginventory.application.SellerEligibilityRepository;
import dev.marketplace.cataloginventory.application.SellerEligibilityService;
import dev.marketplace.cataloginventory.application.SellerVerdict;
import dev.marketplace.cataloginventory.Kafka;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.groups.UniAwait;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SellerVerdictConsumerTest {
  @Inject
  SellerVerdictConsumer consumer;
  @Inject
  ObjectMapper json;
  @Inject
  CatalogConfig config;
  @Inject
  MeterRegistry registry;

  static String verdict(String type, int version, String eventId, String data, String occurredAt) {
    return Kafka.verdict(eventId, type, version, UUID.randomUUID(), UUID.randomUUID(), data, occurredAt);
  }

  static String valid(String type) {
    return Kafka.verdict(UUID.randomUUID(), type, UUID.randomUUID(), UUID.randomUUID(),
        Instant.parse("2026-11-06T10:00:00Z"));
  }

  @Test
  void mapsKnownVerdictsIgnoresOtherTypesAndRejectsWhatCanNeverBeProcessed() {
    assertEquals(SellerStatus.APPROVED, consumer.parse(valid("SellerApproved")).orElseThrow().status());
    assertEquals(SellerStatus.REJECTED, consumer.parse(valid("SellerRejected")).orElseThrow().status());
    assertEquals(SellerStatus.PENDING, consumer.parse(valid("ReviewRequired")).orElseThrow().status());
    assertTrue(consumer.parse(valid("DocumentsSubmitted")).isEmpty(), "tipo que o catálogo não usa é ignorado");

    String store = "{\"storeId\":\"" + UUID.randomUUID() + "\"}";
    List<String> invalid = List.of("{", "[]", "{\"eventVersion\":1}",
        verdict("SellerApproved", 2, UUID.randomUUID().toString(), store, "2026-11-06T10:00:00Z"),
        verdict("SellerApproved", 1, "nao-e-uuid", store, "2026-11-06T10:00:00Z"),
        verdict("SellerApproved", 1, UUID.randomUUID().toString(), "{}", "2026-11-06T10:00:00Z"),
        verdict("SellerApproved", 1, UUID.randomUUID().toString(), store, "ontem"));
    invalid.forEach(payload -> assertThrows(SellerVerdictConsumer.InvalidMessageException.class,
        () -> consumer.parse(payload),
        payload));
  }

  @Test
  void technicalFailureIsRetriedThenNackedForTheDeadLetterTopic() {
    AtomicInteger attempts = new AtomicInteger();
    SellerEligibilityRepository unavailable = new SellerEligibilityRepository() {
      @Override
      public Uni<SellerStatus> statusOf(UUID tenantId, UUID storeId) {
        return Uni.createFrom().failure(new IllegalStateException("banco indisponível"));
      }

      @Override
      public Uni<Map<UUID, SellerStatus>> statusesOf(UUID tenantId, Collection<UUID> storeIds) {
        return Uni.createFrom().failure(new IllegalStateException("banco indisponível"));
      }

      @Override
      public Uni<SellerVerdict.Outcome> apply(String consumerName, SellerVerdict verdict) {
        attempts.incrementAndGet();
        return Uni.createFrom().failure(new IllegalStateException("banco indisponível"));
      }
    };
    var isolated = new SellerVerdictConsumer(new SellerEligibilityService(unavailable), json, config, registry);
    UniAwait<Void> consumption = isolated.consume(valid("SellerApproved")).await();
    Duration timeout = Duration.ofSeconds(15);
    assertThrows(IllegalStateException.class, () -> consumption.atMost(timeout));
    assertEquals(config.events().consumerRetries() + 1, attempts.get(),
        "tentativa original mais as novas tentativas");
  }
}
