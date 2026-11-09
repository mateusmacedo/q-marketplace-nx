package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.application.CatalogStatsRepository.CategoryCounts;
import dev.marketplace.cataloginventory.application.CatalogStatsRepository.TransitionCount;
import dev.marketplace.cataloginventory.application.CatalogStatsRepository.Window;
import dev.marketplace.cataloginventory.domain.product.ProductStatus;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Números da página inicial do painel, por papel: a loja do seller, a fila do moderador e a taxonomia do
 * administrador. Os baldes seguem o fuso {@link #ZONE}; o passado sai do histórico de transições dos produtos, e o
 * que não tem histórico, como o saldo, só tem o valor atual.
 */
public final class CatalogStatsService {
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    /** Meses dos minigráficos dos indicadores: o corrente e os anteriores. */
    public static final int INDICATOR_MONTHS = 7;
    public static final int MAX_POINTS = 366;
    /** Primeira data aceita em {@code from} e {@code to}: as datas do ISO 8601 com ano de 4 dígitos. */
    static final LocalDate EARLIEST = LocalDate.of(1, 1, 1);
    /** Última data aceita em {@code from} e {@code to}. */
    static final LocalDate LATEST = LocalDate.of(9999, 12, 31);
    static final int DEFAULT_DAYS = 30;
    static final int DEFAULT_YEARS = 5;

    /** Valor de um indicador no fim do mês, ou no instante da leitura no mês corrente; vazio sem histórico. */
    public record MonthValue(LocalDate month, Optional<Long> value) {
    }

    /** Valor atual, o do mês anterior, quando há histórico, e os meses do minigráfico, do mais antigo ao corrente. */
    public record Indicator(long value, Optional<Long> previous, List<MonthValue> months) {
        public Optional<Long> change() {
            return previous.map(before -> value - before);
        }
    }

    /**
     * Transições num balde: entradas na fila de moderação (envio, edição de produto publicado e desbloqueio),
     * aprovações, rejeições e bloqueios.
     */
    public record ActivityPoint(LocalDate period, long submitted, long approved, long rejected, long blocked) {
    }

    /** Série de {@code from} até {@code to}, os dois incluídos, com um ponto por balde, mesmo sem transição. */
    public record Activity(StatsGranularity granularity, LocalDate from, LocalDate to, List<ActivityPoint> points) {
    }

    /**
     * Loja do seller: produtos por estado como a resposta do produto os mostra ({@code ACTIVE} com saldo e
     * {@code OUT_OF_STOCK} sem), {@code total} sem os arquivados, os quatro indicadores e a série de atividade.
     */
    public record StoreStats(UUID storeId, Map<ProductStatus, Long> counts, long total, Indicator active,
            Indicator draft, Indicator outOfStock, Indicator blocked, Activity activity) {
    }

    /** Tenant do moderador: a fila, as decisões do mês e a série de atividade. */
    public record ModerationStats(Indicator queue, Indicator approved, Indicator rejected, Indicator blocked,
            Activity activity) {
    }

    /** Os {@link #INDICATOR_MONTHS} meses dos indicadores, do mais antigo ao corrente. */
    private record Months(List<YearMonth> list) {
        static Months endingAt(Instant now) {
            YearMonth current = YearMonth.from(now.atZone(ZONE));
            List<YearMonth> months = new ArrayList<>();
            for (int back = INDICATOR_MONTHS - 1; back >= 0; back--)
                months.add(current.minusMonths(back));
            return new Months(List.copyOf(months));
        }

        Window window() {
            return new Window(StatsGranularity.MONTH, ZONE, start(list.getFirst().atDay(1)),
                    start(list.getLast().plusMonths(1).atDay(1)));
        }
    }

    /** Baldes da série, de {@code first} a {@code last}, pelo início de cada um. */
    private record Span(StatsGranularity granularity, LocalDate first, LocalDate last) {
        Window window() {
            return new Window(granularity, ZONE, start(first), start(granularity.next(last)));
        }

        List<LocalDate> buckets() {
            List<LocalDate> buckets = new ArrayList<>();
            for (LocalDate bucket = first; !bucket.isAfter(last); bucket = granularity.next(bucket))
                buckets.add(bucket);
            return buckets;
        }
    }

    private final CatalogStatsRepository stats;
    private final Clock clock;

    public CatalogStatsService(CatalogStatsRepository stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    /**
     * Números da loja do seller, com a série na granularidade pedida ({@code month} quando ausente) entre
     * {@code from} e {@code to}, que vão juntos; sem eles, vale o período padrão ({@link #span}). Exige
     * {@code MANAGE_OWN_PRODUCTS} e loja no token.
     */
    public Uni<StoreStats> store(Actor actor, Optional<String> granularity, Optional<LocalDate> from,
            Optional<LocalDate> to) {
        return Uni.createFrom().deferred(() -> {
            UUID store = ProductAccess.requireSeller(actor);
            Instant now = clock.instant();
            Months months = Months.endingAt(now);
            Span span = span(StatsGranularity.parse(granularity), from, to, today(now));
            return stats.store(actor.tenantId(), store, months.window(), span.window())
                    .map(snapshot -> {
                        long recordedActive = snapshot.statuses().getOrDefault(ProductStatus.ACTIVE, 0L);
                        Map<ProductStatus, Long> counts = new EnumMap<>(ProductStatus.class);
                        for (ProductStatus status : ProductStatus.values())
                            counts.put(status, snapshot.statuses().getOrDefault(status, 0L));
                        counts.put(ProductStatus.ACTIVE, recordedActive - snapshot.outOfStock());
                        counts.put(ProductStatus.OUT_OF_STOCK, snapshot.outOfStock());
                        long total = counts.entrySet().stream().filter(e -> e.getKey() != ProductStatus.ARCHIVED)
                                .mapToLong(Map.Entry::getValue).sum();
                        Map<YearMonth, Map<ProductStatus, Long>> net = netFlows(snapshot.months());
                        return new StoreStats(store, Collections.unmodifiableMap(counts), total,
                                withStock(counts.get(ProductStatus.ACTIVE),
                                        status(recordedActive, ProductStatus.ACTIVE, months, net)),
                                recorded(counts, ProductStatus.DRAFT, months, net),
                                untracked(snapshot.outOfStock(), months),
                                recorded(counts, ProductStatus.BLOCKED, months, net),
                                activity(span, snapshot.activity()));
                    });
        });
    }

    /**
     * Números da moderação no tenant, com a série como em {@link #store}. Exige {@code MODERATE_PRODUCTS}.
     */
    public Uni<ModerationStats> moderation(Actor actor, Optional<String> granularity, Optional<LocalDate> from,
            Optional<LocalDate> to) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            Instant now = clock.instant();
            Months months = Months.endingAt(now);
            Span span = span(StatsGranularity.parse(granularity), from, to, today(now));
            return stats.moderation(actor.tenantId(), months.window(), span.window())
                    .map(snapshot -> new ModerationStats(
                            status(snapshot.queue(), ProductStatus.PENDING_MODERATION, months,
                                    netFlows(snapshot.months())),
                            monthly(ProductTransition.APPROVE, months, snapshot.months()),
                            monthly(ProductTransition.REJECT, months, snapshot.months()),
                            monthly(ProductTransition.BLOCK, months, snapshot.months()),
                            activity(span, snapshot.activity())));
        });
    }

    /**
     * Só o tamanho atual da fila de moderação do tenant, sem os indicadores nem a série, para a leitura frequente.
     * Exige {@code MODERATE_PRODUCTS}.
     */
    public Uni<Long> moderationQueue(Actor actor) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MODERATE_PRODUCTS);
            return stats.moderationQueue(actor.tenantId());
        });
    }

    /** Contagens da taxonomia do tenant. Exige {@code MANAGE_TAXONOMY}. */
    public Uni<CategoryCounts> categories(Actor actor) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_TAXONOMY);
            return stats.categories(actor.tenantId());
        });
    }

    /**
     * Baldes da série. Com {@code from} e {@code to}, os baldes que os contêm, até {@link #MAX_POINTS}; sem eles, os
     * últimos 30 dias até hoje ({@code day}), os 12 meses do ano corrente ({@code month}) ou os últimos 5 anos até o
     * corrente ({@code year}). Só um dos dois, data fora de {@link #EARLIEST} a {@link #LATEST}, {@code from} depois
     * de {@code to} ou período longo demais lançam {@link InvalidInputException}.
     */
    static Span span(StatsGranularity granularity, Optional<LocalDate> from, Optional<LocalDate> to,
            LocalDate today) {
        if (from.isPresent() != to.isPresent())
            throw new InvalidInputException("from e to vão juntos; sem os dois, vale o período padrão");
        // WHY: fora dessa faixa, o fim do último balde passa do maior LocalDate ou o instante enviado ao banco sai do
        // alcance do timestamptz, e a entrada do cliente virava erro 500.
        if (Stream.of(from, to).flatMap(Optional::stream).anyMatch(d -> d.isBefore(EARLIEST) || d.isAfter(LATEST)))
            throw new InvalidInputException("from e to devem estar entre " + EARLIEST + " e " + LATEST);
        Span span = from.map(start -> new Span(granularity, granularity.truncate(start),
                granularity.truncate(to.orElseThrow())))
                .orElseGet(() -> switch (granularity) {
                    case DAY -> new Span(granularity, today.minusDays(DEFAULT_DAYS - 1L), today);
                    case MONTH -> new Span(granularity, today.withDayOfYear(1), today.withDayOfYear(1).withMonth(12));
                    case YEAR -> new Span(granularity, today.withDayOfYear(1).minusYears(DEFAULT_YEARS - 1L),
                            today.withDayOfYear(1));
                });
        if (span.first().isAfter(span.last()))
            throw new InvalidInputException("from deve ser anterior ou igual a to");
        if (granularity.count(span.first(), span.last()) > MAX_POINTS)
            throw new InvalidInputException("O período passa de " + MAX_POINTS + " pontos");
        return span;
    }

    /** Indicador de estado gravado, cujo valor atual é o próprio estado gravado. */
    private static Indicator recorded(Map<ProductStatus, Long> counts, ProductStatus status, Months months,
            Map<YearMonth, Map<ProductStatus, Long>> net) {
        return status(counts.get(status), status, months, net);
    }

    /**
     * Indicador de estado: {@code recordedNow} no mês corrente e, nos anteriores, quantos produtos estavam gravados
     * em {@code status} no fim do mês, voltando de {@code recordedNow} pelo saldo de entradas e saídas dos meses
     * seguintes.
     */
    private static Indicator status(long recordedNow, ProductStatus status, Months months,
            Map<YearMonth, Map<ProductStatus, Long>> net) {
        List<YearMonth> list = months.list();
        long[] atEnd = new long[list.size()];
        atEnd[list.size() - 1] = recordedNow;
        for (int i = list.size() - 2; i >= 0; i--)
            atEnd[i] = atEnd[i + 1] - net.getOrDefault(list.get(i + 1), Map.of()).getOrDefault(status, 0L);
        List<MonthValue> points = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            points.add(new MonthValue(list.get(i).atDay(1), Optional.of(atEnd[i])));
        return new Indicator(recordedNow, Optional.of(atEnd[list.size() - 2]), List.copyOf(points));
    }

    /**
     * Indicador do {@code ACTIVE} com saldo: {@code current} como valor, e a série do {@code ACTIVE} gravado, com ou
     * sem saldo, sem o valor do mês anterior.
     */
    private static Indicator withStock(long current, Indicator recordedActive) {
        // WHY: o saldo não guarda passado; comparar o com saldo de agora com o gravado do mês anterior mostraria os
        // produtos sem saldo como queda, todo mês.
        return new Indicator(current, Optional.empty(), recordedActive.months());
    }

    /** Indicador sem histórico: só o valor atual, no mês corrente. */
    private static Indicator untracked(long current, Months months) {
        List<MonthValue> points = new ArrayList<>();
        months.list().forEach(month -> points.add(new MonthValue(month.atDay(1), Optional.empty())));
        points.set(points.size() - 1, new MonthValue(months.list().getLast().atDay(1), Optional.of(current)));
        return new Indicator(current, Optional.empty(), List.copyOf(points));
    }

    /** Indicador de fluxo: quantas vezes {@code transition} foi gravada em cada mês; o corrente vai até agora. */
    private static Indicator monthly(ProductTransition transition, Months months, List<TransitionCount> counts) {
        Map<YearMonth, Long> perMonth = new HashMap<>();
        counts.stream().filter(c -> c.transition() == transition)
                .forEach(c -> perMonth.merge(YearMonth.from(c.period()), c.count(), Long::sum));
        List<MonthValue> points = months.list().stream()
                .map(month -> new MonthValue(month.atDay(1), Optional.of(perMonth.getOrDefault(month, 0L))))
                .toList();
        return new Indicator(points.getLast().value().orElseThrow(), points.get(points.size() - 2).value(), points);
    }

    /**
     * Saldo de cada mês por estado: produtos que entraram nele menos os que saíram. A criação só entra em
     * {@code DRAFT}, porque o produto não existia antes.
     */
    private static Map<YearMonth, Map<ProductStatus, Long>> netFlows(List<TransitionCount> counts) {
        Map<YearMonth, Map<ProductStatus, Long>> net = new HashMap<>();
        for (TransitionCount count : counts) {
            Map<ProductStatus, Long> month = net.computeIfAbsent(YearMonth.from(count.period()),
                    key -> new EnumMap<>(ProductStatus.class));
            month.merge(count.to(), count.count(), Long::sum);
            if (count.transition() != ProductTransition.CREATE)
                month.merge(count.from(), -count.count(), Long::sum);
        }
        return net;
    }

    private static Activity activity(Span span, List<TransitionCount> counts) {
        Map<LocalDate, long[]> perBucket = new HashMap<>();
        for (TransitionCount count : counts) {
            long[] values = perBucket.computeIfAbsent(count.period(), key -> new long[4]);
            if (count.to() == ProductStatus.PENDING_MODERATION)
                values[0] += count.count();
            switch (count.transition()) {
                case APPROVE -> values[1] += count.count();
                case REJECT -> values[2] += count.count();
                case BLOCK -> values[3] += count.count();
                default -> {
                }
            }
        }
        List<ActivityPoint> points = span.buckets().stream().map(bucket -> {
            long[] values = perBucket.getOrDefault(bucket, new long[4]);
            return new ActivityPoint(bucket, values[0], values[1], values[2], values[3]);
        }).toList();
        return new Activity(span.granularity(), span.first(), span.granularity().next(span.last()).minusDays(1),
                points);
    }

    private static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
