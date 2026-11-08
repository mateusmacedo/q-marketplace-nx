package dev.marketplace.cataloginventory.cache;

import dev.marketplace.cataloginventory.CatalogConfig;
import dev.marketplace.cataloginventory.observability.Traced;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Redis do cache da vitrine e da taxonomia (ADR 024): conteúdo carimbado com as gerações lidas antes da carga do banco,
 * geração incrementada a cada escrita confirmada. Leitura fora do prazo vira {@link Lookup#BYPASS}, e a gravação sem
 * espera ({@link #detach}) que falha é ignorada; a invalidação tem prazo próprio e nova tentativa, e só a segunda falha
 * vai a log {@code WARN}. Nenhuma falha propaga; cada método é um span ({@link Traced}).
 */
@ApplicationScoped
@Startup
@Traced
public class CatalogCache {
    static final String STOREFRONT = "storefront";
    static final String TAXONOMY = "taxonomy";
    static final String REQUESTS = "catalog.cache.requests";
    static final String INVALIDATIONS = "catalog.cache.invalidations";
    static final String WRITES = "catalog.cache.writes";
    private static final Logger LOG = Logger.getLogger(CatalogCache.class);
    private static final AttributeKey<String> CACHE = AttributeKey.stringKey("marketplace.cache.name");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("marketplace.cache.result");

    // WHY: a geração ausente (expirada ou removida por memória) recomeça pelo relógio do Redis, nunca por 1; assim um
    // conteúdo carimbado com uma geração antiga não volta a valer. A loja vem do mapa produto→loja, então a chave da
    // geração dela só existe dentro do script: por isso o no-cluster (ADR 024).
    static final Script READ = new Script("""
            #!lua flags=no-cluster
            local function generation(key)
              local current = redis.call('GET', key)
              if current then return current end
              local now = redis.call('TIME')
              current = now[1] .. string.format('%06d', tonumber(now[2]))
              redis.call('SET', key, current, 'PX', ARGV[1])
              return current
            end
            local stamp = generation(KEYS[2])
            if KEYS[3] then
              local owner = redis.call('GET', KEYS[3])
              if not owner then return {'unmapped'} end
              stamp = stamp .. ':' .. generation(ARGV[2] .. owner)
            end
            stamp = stamp .. '|'
            local value = redis.call('GET', KEYS[1])
            if value and string.sub(value, 1, #stamp) == stamp then
              return {'hit', string.sub(value, #stamp + 1)}
            end
            return {'miss', stamp}
            """);

    // WHY: INCR criaria a geração ausente com 1, valor que um conteúdo antigo pode carregar; ausente, a próxima
    // leitura já recomeça a geração pelo relógio, então não há o que incrementar.
    static final Script INVALIDATE = new Script("""
            #!lua flags=no-cluster
            local bumped = 0
            if redis.call('EXISTS', KEYS[1]) == 1 then bumped = redis.call('INCR', KEYS[1]) end
            if KEYS[2] then redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2]) end
            return bumped
            """);

    /** Script Lua com o SHA-1 que o {@code EVALSHA} usa. */
    record Script(String source, String sha) {
        Script(String source) {
            this(source, sha1(source));
        }

        private static String sha1(String source) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                        .digest(source.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Resultado de uma leitura. {@code HIT} traz o conteúdo; {@code MISS} traz o carimbo das gerações lidas antes da
     * carga, que acompanha a gravação; {@code UNMAPPED} indica a vitrine sem o mapa produto→loja, que a carga grava sem
     * guardar o conteúdo; {@code BYPASS} indica o Redis indisponível ou lento.
     */
    public record Lookup(Result result, String content) {
        public static final Lookup UNMAPPED = new Lookup(Result.UNMAPPED, null);
        public static final Lookup BYPASS = new Lookup(Result.BYPASS, null);

        public enum Result {
            HIT, MISS, UNMAPPED, BYPASS
        }
    }

    private final boolean enabled;
    private final Redis redis;
    private final Duration timeout;
    private final Duration invalidationTimeout;
    private final String ttl;
    private final String generationTtl;
    private final MeterRegistry registry;

    /**
     * Desligado, não resolve o cliente Redis, que fica inativo sem {@code quarkus.redis.hosts}; ligado sem o cliente
     * ativo, a inicialização falha com o motivo dado pelo Quarkus.
     */
    // WHY: o cliente de baixo nível, e não o ReactiveRedisDataSource: no Quarkus 3.40.1.1, o datasource faz a extensão
    // criar o cliente duas vezes, e o segundo pool registra de novo o gauge redis.pool.queue.size (ADR 024).
    CatalogCache(CatalogConfig config, InjectableInstance<Redis> redis, MeterRegistry registry) {
        CatalogConfig.Cache settings = config.cache();
        this.enabled = settings.enabled();
        this.redis = enabled ? redis.getActive() : null;
        this.timeout = settings.timeout();
        this.invalidationTimeout = settings.invalidationTimeout();
        this.ttl = Long.toString(settings.ttl().toMillis());
        this.generationTtl = Long.toString(settings.generationTtl().toMillis());
        this.registry = registry;
        if (enabled)
            for (String cache : List.of(STOREFRONT, TAXONOMY)) {
                List.of("hit", "miss", "bypass").forEach(result -> requests(cache, result));
                List.of("ok", "failed").forEach(result -> {
                    invalidations(cache, result);
                    writes(cache, result);
                });
            }
    }

    /** Indica se o cache está ligado ({@code catalog.cache.enabled}); desligado, os métodos não falam com o Redis. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Lê o conteúdo de {@code valueKey} e o aceita só se o carimbo dele bater com as gerações atuais: a de
     * {@code generationKey} e, com {@code ownerKey}, a da loja cujo id está em {@code ownerKey}, no prefixo
     * {@code ownerGenerationPrefix}. Geração ausente é criada nesta leitura. Conta o resultado em
     * {@code catalog.cache.requests} e nunca falha; desligado, devolve {@code BYPASS} sem contar.
     */
    public Uni<Lookup> read(String cache, String valueKey, String generationKey, String ownerKey,
            String ownerGenerationPrefix) {
        if (!enabled)
            return Uni.createFrom().item(Lookup.BYPASS);
        Span span = Span.current().setAttribute(CACHE, cache);
        List<String> keys = ownerKey == null ? List.of(valueKey, generationKey)
                : List.of(valueKey, generationKey, ownerKey);
        List<String> args = ownerKey == null ? List.of(generationTtl) : List.of(generationTtl, ownerGenerationPrefix);
        return run(READ, keys, args, timeout).map(CatalogCache::lookup)
                .onFailure().recoverWithItem(failure -> {
                    LOG.debugf(failure, "Cache %s indisponível na leitura; a consulta vai ao banco", cache);
                    span.recordException(failure);
                    return Lookup.BYPASS;
                })
                .invoke(lookup -> {
                    String result = lookup.result().name().toLowerCase();
                    span.setAttribute(RESULT, result);
                    requests(cache, lookup.result() == Lookup.Result.UNMAPPED ? "miss" : result).increment();
                });
    }

    /**
     * Guarda {@code content} em {@code valueKey} com o carimbo de um {@code MISS} e a validade
     * {@code catalog.cache.ttl}, contando o resultado em {@code catalog.cache.writes}. Falha, inclusive o prazo, é
     * ignorada: a próxima leitura só volta ao banco.
     */
    public Uni<Void> write(String cache, String valueKey, String stamp, String content) {
        Span span = Span.current().setAttribute(CACHE, cache);
        return command(Command.SET, valueKey, stamp + content, "PX", ttl).replaceWithVoid()
                .invoke(() -> writes(cache, "ok").increment())
                .onFailure().recoverWithUni(failure -> {
                    writes(cache, "failed").increment();
                    return ignored(cache, span, failure);
                });
    }

    /**
     * Assina a gravação sem esperar por ela: a resposta da falta não espera o {@code SET}. O span dela nasce aqui,
     * filho do ativo, e pode terminar depois da resposta; o carimbo impede que um retrato gravado tarde valha.
     */
    static void detach(Uni<Void> write) {
        write.subscribe().with(done -> {
        }, failure -> LOG.debugf(failure, "Gravação no cache ignorada"));
    }

    /** Guarda o mapa produto→loja de uma leitura {@code UNMAPPED}; falha é ignorada. */
    public Uni<Void> remember(UUID tenantId, UUID productId, UUID storeId) {
        Span span = Span.current().setAttribute(CACHE, STOREFRONT);
        return command(Command.SET, CacheKeys.productStore(tenantId, productId), storeId.toString(), "PX",
                generationTtl).replaceWithVoid().onFailure()
                .recoverWithUni(failure -> ignored(STOREFRONT, span, failure));
    }

    /**
     * Invalida a vitrine do produto depois de uma escrita no produto ou numa oferta dele, e renova o mapa
     * produto→loja. Desligado, não faz nada; falha fica em log e em {@code catalog.cache.invalidations}.
     */
    public Uni<Void> productChanged(UUID tenantId, UUID productId, UUID storeId) {
        return invalidate(STOREFRONT, CacheKeys.productGeneration(tenantId, productId),
                CacheKeys.productStore(tenantId, productId), storeId.toString());
    }

    /** Invalida a vitrine de todos os produtos da loja depois de um veredito KYB/KYC aplicado. */
    public Uni<Void> storeChanged(UUID tenantId, UUID storeId) {
        return invalidate(STOREFRONT, CacheKeys.storeGeneration(tenantId, storeId), null, null);
    }

    /** Invalida a taxonomia do tenant depois de criar, revisar ou mover uma categoria. */
    public Uni<Void> taxonomyChanged(UUID tenantId) {
        return invalidate(TAXONOMY, CacheKeys.taxonomyGeneration(tenantId), null, null);
    }

    private Uni<Void> invalidate(String cache, String generationKey, String ownerKey, String owner) {
        if (!enabled)
            return Uni.createFrom().voidItem();
        Span span = Span.current().setAttribute(CACHE, cache);
        List<String> keys = ownerKey == null ? List.of(generationKey) : List.of(generationKey, ownerKey);
        List<String> args = ownerKey == null ? List.of() : List.of(owner, generationTtl);
        // WHY: o script só incrementa e renova o mapa, então repetir depois de um prazo estourado no meio do caminho
        // apenas invalida de novo.
        return run(INVALIDATE, keys, args, invalidationTimeout)
                .onFailure().recoverWithUni(first -> {
                    LOG.debugf("Invalidação do cache %s (%s) falhou na primeira tentativa: %s", cache, generationKey,
                            first.toString());
                    return run(INVALIDATE, keys, args, invalidationTimeout);
                })
                .invoke(bumped -> invalidations(cache, "ok").increment()).replaceWithVoid()
                .onFailure().recoverWithItem(failure -> {
                    // WHY: a escrita no banco segue valendo, e o conteúdo anterior fica no ar até catalog.cache.ttl;
                    // sem a pilha, porque com o Redis fora toda escrita cai aqui (a exceção vai ao span).
                    LOG.warnf("Falha ao invalidar o cache %s (%s): %s; o conteúdo anterior vale até expirar", cache,
                            generationKey, failure.toString());
                    span.recordException(failure);
                    invalidations(cache, "failed").increment();
                    return null;
                });
    }

    private Uni<Response> run(Script script, List<String> keys, List<String> args, Duration deadline) {
        return redis.send(request(Command.EVALSHA, script.sha(), keys, args))
                .onFailure(CatalogCache::missingScript)
                .recoverWithUni(() -> redis.send(request(Command.EVAL, script.source(), keys, args)))
                .ifNoItem().after(deadline).fail();
    }

    private Uni<Response> command(Command command, String... args) {
        if (!enabled)
            return Uni.createFrom().nullItem();
        Request request = Request.cmd(command);
        for (String arg : args)
            request.arg(arg);
        return redis.send(request).ifNoItem().after(timeout).fail();
    }

    private static Request request(Command command, String script, List<String> keys, List<String> args) {
        Request request = Request.cmd(command).arg(script).arg(keys.size());
        keys.forEach(request::arg);
        args.forEach(request::arg);
        return request;
    }

    /** WHY: o Redis esquece os scripts ao reiniciar; o EVAL devolve o script ao cache dele para os próximos EVALSHA. */
    private static boolean missingScript(Throwable failure) {
        return failure.getMessage() != null && failure.getMessage().startsWith("NOSCRIPT");
    }

    private static Lookup lookup(Response response) {
        String result = response.get(0).toString();
        return switch (result) {
            case "hit" -> new Lookup(Lookup.Result.HIT, response.get(1).toString());
            case "miss" -> new Lookup(Lookup.Result.MISS, response.get(1).toString());
            case "unmapped" -> Lookup.UNMAPPED;
            default -> throw new IllegalStateException("Resposta inesperada do script de leitura: " + result);
        };
    }

    private Uni<Void> ignored(String cache, Span span, Throwable failure) {
        LOG.debugf(failure, "Gravação no cache %s ignorada", cache);
        // WHY: sem status de erro; é falha tolerada, a resposta já saiu do banco, e o trace não deve ficar vermelho.
        span.recordException(failure).setAttribute(RESULT, "failed");
        return Uni.createFrom().voidItem();
    }

    private Counter requests(String cache, String result) {
        return Counter.builder(REQUESTS).description("Leituras do cache por resultado (hit, miss ou bypass ao banco)")
                .tags("cache", cache, "result", result).register(registry);
    }

    private Counter writes(String cache, String result) {
        return Counter.builder(WRITES).description("Gravações do cache depois de uma falta, por resultado (ok ou failed)")
                .tags("cache", cache, "result", result).register(registry);
    }

    private Counter invalidations(String cache, String result) {
        return Counter.builder(INVALIDATIONS).description("Invalidações do cache depois de escritas no banco")
                .tags("cache", cache, "result", result).register(registry);
    }
}
