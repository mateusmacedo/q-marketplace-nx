package dev.marketplace.cataloginventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Acesso de teste ao Kafka dos Dev Services: publica vereditos como o contexto
 * KYB/KYC e lê os eventos do catálogo.
 */
public final class Kafka {
    static final String KYB_TOPIC = "kyb-kyc.seller.v1";
    static final String KYB_DLQ = "catalog-inventory.kyb-seller.dlq";
    static final String PRODUCT_TOPIC = "catalog-inventory.product.v1";
    static final String OFFER_TOPIC = "catalog-inventory.offer.v1";
    static final String INVENTORY_TOPIC = "catalog-inventory.inventory.v1";
    static final String STORE_TOPIC = "catalog-inventory.store.v1";
    static final ObjectMapper JSON = new ObjectMapper();
    static final Duration WAIT = Duration.ofSeconds(20);
    private static KafkaCompanion companion;
    private static String bootstrap;

    private Kafka() {
    }

    /**
     * WHY: um perfil de teste diferente pode subir outro broker; o companion
     * acompanha o endereço da aplicação atual.
     */
    static synchronized KafkaCompanion companion() {
        String current = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class);
        if (companion == null || !current.equals(bootstrap)) {
            if (companion != null)
                companion.close();
            companion = new KafkaCompanion(current);
            bootstrap = current;
        }
        return companion;
    }

    /** Veredito válido da loja, decidido em {@code decidedAt}. */
    public static String verdict(UUID eventId, String eventType, UUID tenant, UUID store, Instant decidedAt) {
        return verdict(eventId.toString(), eventType, 1, tenant, store, "{\"storeId\":\"" + store + "\"}",
                decidedAt.toString());
    }

    /**
     * Veredito com os campos que os testes de contrato corrompem de propósito:
     * identificador, versão do evento, {@code data} e instante em texto livre.
     */
    public static String verdict(String eventId, String eventType, int eventVersion, UUID tenant, UUID store,
            String data, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":%d,"source":"kyb-kyc","tenantId":"%s","aggregateType":"store",
                 "aggregateId":"%s","occurredAt":"%s","correlationId":"teste","data":%s}"""
                .formatted(eventId, eventType, eventVersion, tenant, store, occurredAt, data);
    }

    static void publishVerdict(UUID store, String payload) {
        companion().produceStrings().fromRecords(new ProducerRecord<>(KYB_TOPIC, store.toString(), payload))
                .awaitCompletion();
    }

    /** Lê do início do tópico até {@code count} mensagens que passam no filtro. */
    static List<ConsumerRecord<String, String>> consume(String topic, Predicate<ConsumerRecord<String, String>> filter,
            int count) {
        try (var task = companion().consumeStrings().withGroupId(UUID.randomUUID().toString())
                .withOffsetReset("earliest")
                .fromTopics(topic, records -> records.filter(filter))) {
            return task.awaitRecords(count, WAIT).getRecords();
        }
    }

    static ConsumerRecord<String, String> ofType(List<ConsumerRecord<String, String>> records, String type) {
        return records.stream().filter(r -> type.equals(header(r, "event-type"))).findFirst()
                .orElseThrow(() -> new AssertionError("Sem evento " + type));
    }

    static Predicate<ConsumerRecord<String, String>> ofTenant(UUID tenant) {
        return r -> tenant.toString().equals(envelope(r).path("tenantId").asText());
    }

    static JsonNode envelope(ConsumerRecord<String, String> record) {
        try {
            return JSON.readTree(record.value());
        } catch (IOException e) {
            throw new AssertionError("Evento não é JSON: " + record.value(), e);
        }
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
