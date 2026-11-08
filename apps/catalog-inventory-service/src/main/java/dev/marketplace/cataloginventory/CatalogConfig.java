package dev.marketplace.cataloginventory;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

/**
 * Configuração tipada do serviço, validada pelo SmallRye Config na
 * inicialização. Cada chave aceita a variável de ambiente
 * correspondente (por exemplo, {@code CATALOG_EVENTS_PRODUCT_TOPIC}). Os
 * intervalos dos agendadores não têm default: o
 * valor fica só em {@code application.properties}, de onde o {@code @Scheduled}
 * o lê por expressão.
 */
@ConfigMapping(prefix = "catalog")
public interface CatalogConfig {
    Security security();

    Persistence persistence();

    Reservations reservations();

    Events events();

    Imports imports();

    Metrics metrics();

    Cache cache();

    interface Security {
        /** Claim do JWT que carrega o tenant do chamador. */
        @WithDefault("tenant_id")
        String tenantClaim();

        /** Claim do JWT que carrega a loja do seller. */
        @WithDefault("store_id")
        String storeClaim();
    }

    interface Persistence {
        /** Prazo máximo de uma operação no banco antes de responder 503. */
        @WithDefault("2s")
        Duration timeout();
    }

    interface Reservations {
        /** Prazo padrão de uma reserva quando o chamador não informa. */
        @WithDefault("15m")
        Duration defaultTtl();

        /** Maior prazo aceito numa criação ou extensão. */
        @WithDefault("2h")
        Duration maxTtl();

        /** Vida máxima de uma reserva, somando extensões. */
        @WithDefault("24h")
        Duration maxLifetime();

        /** Quantas reservas vencidas o varredor expira por execução. */
        @WithDefault("200")
        int sweepBatch();

        /**
         * Intervalo do varredor no formato do {@code @Scheduled} (por exemplo,
         * {@code 5s}); {@code off} desliga.
         */
        String sweepInterval();
    }

    interface Events {
        @WithDefault("catalog-inventory.product.v1")
        String productTopic();

        @WithDefault("catalog-inventory.offer.v1")
        String offerTopic();

        @WithDefault("catalog-inventory.inventory.v1")
        String inventoryTopic();

        @WithDefault("catalog-inventory.store.v1")
        String storeTopic();

        /** Quantos eventos o relay publica por lote, cada lote numa transação. */
        @WithDefault("100")
        int relayBatch();

        /**
         * Máximo de lotes publicados numa execução do relay enquanto os lotes vêm
         * cheios; a execução publica pelo menos um.
         */
        @WithDefault("20")
        int relayMaxBatches();

        /** Intervalo do relay no formato do {@code @Scheduled}. */
        String relayInterval();

        /**
         * Prazo para o Kafka confirmar cada publicação; esgotado, o evento volta para a
         * fila.
         */
        @WithDefault("10s")
        Duration publishTimeout();

        /**
         * Maior espera entre as tentativas de um mesmo evento; a espera dobra a cada
         * falha, a partir de 1 s, e não pausa os eventos de outras chaves.
         */
        @WithDefault("60s")
        Duration maxBackoff();

        /**
         * Tentativas de publicar um evento antes de estacioná-lo; o estacionado sai da
         * fila até o reenvio manual e retém os seguintes da sua chave.
         */
        @WithDefault("20")
        int maxAttempts();

        /** Por quanto tempo eventos já publicados ficam na outbox. */
        @WithDefault("7d")
        Duration outboxRetention();

        /**
         * Por quanto tempo uma entrega consumida segue deduplicada; deve superar a
         * janela de reentrega do produtor.
         */
        @WithDefault("30d")
        Duration inboxRetention();

        /**
         * Intervalo da limpeza da outbox e da inbox no formato do {@code @Scheduled};
         * {@code off} desliga.
         */
        String purgeInterval();

        /** Quantos registros a limpeza remove por instrução, em cada tabela. */
        @WithDefault("10000")
        int purgeBatch();

        /**
         * Prazo de uma execução da limpeza, somado nas duas tabelas: repete lotes
         * enquanto vêm cheios e o prazo não passou; cada tabela remove pelo menos um
         * lote. Fica abaixo do {@code purgeInterval} para a limpeza ser contínua.
         */
        @WithDefault("50s")
        Duration purgeMaxDuration();

        /**
         * Novas tentativas de um veredito KYB com falha técnica antes de enviá-lo à
         * fila de descarte.
         */
        @WithDefault("10")
        int consumerRetries();
    }

    interface Imports {
        /**
         * Intervalo do processador de importações de produtos no formato do
         * {@code @Scheduled}; {@code off} desliga.
         */
        String interval();

        /** Máximo de linhas de dados por arquivo de importação. */
        @WithDefault("5000")
        int maxRows();

        /** Tempo sem progresso após o qual um lote em processamento pode ser retomado. */
        @WithDefault("5m")
        Duration lease();
    }

    interface Metrics {
        /**
         * Intervalo da atualização dos gauges de SKUs no formato do
         * {@code @Scheduled}; {@code off} desliga.
         */
        String refreshInterval();
    }

    /** Cache Redis da vitrine e da taxonomia (ADR 024). */
    interface Cache {
        /**
         * Liga o cache; desligado, nenhuma leitura nem invalidação passa pelo Redis e o serviço lê tudo do banco.
         * Ligado, exige o cliente Redis configurado ({@code QUARKUS_REDIS_HOSTS}).
         */
        @WithDefault("true")
        boolean enabled();

        /** Validade de cada conteúdo guardado, o teto de conteúdo velho quando uma invalidação se perde. */
        @WithDefault("60s")
        Duration ttl();

        /** Validade das gerações e do mapa produto→loja; ao expirar, a próxima leitura recomeça a geração. */
        @WithDefault("24h")
        Duration generationTtl();

        /** Prazo de cada leitura e gravação no Redis; estourado, a leitura vai ao banco e a gravação é ignorada. */
        @WithDefault("100ms")
        Duration timeout();

        /**
         * Prazo de cada tentativa de invalidação, maior que o da leitura porque a perda deixa conteúdo velho até
         * {@link #ttl()}; a invalidação tenta de novo uma vez e só então conta como falha.
         */
        @WithDefault("1s")
        Duration invalidationTimeout();
    }
}
