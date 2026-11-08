package dev.marketplace.cataloginventory.imports;

import dev.marketplace.cataloginventory.application.ProductImport;
import io.opentelemetry.api.trace.Span;
import dev.marketplace.cataloginventory.observability.Telemetry;
import dev.marketplace.cataloginventory.observability.Lifecycle;
import dev.marketplace.cataloginventory.CatalogConfig;
import dev.marketplace.cataloginventory.application.ImportResult;
import dev.marketplace.cataloginventory.application.ProductImportRepository;
import dev.marketplace.cataloginventory.application.ProductImportService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ApplicationNotRunning;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

/**
 * Processa em segundo plano os lotes de importação de produtos, um por
 * execução. Vários pods trabalham juntos sem repetir lote, porque a
 * reivindicação usa {@code FOR UPDATE SKIP LOCKED}; lote interrompido é
 * retomado depois do lease, a partir das linhas ainda sem resultado.
 */
@ApplicationScoped
public class ProductImportWorker {
    private static final Logger LOG = Logger.getLogger(ProductImportWorker.class);
    private final ProductImportService imports;
    private final Lifecycle lifecycle;
    private final CatalogConfig config;
    private final Map<ImportResult.ImportOutcome, Counter> lines = new EnumMap<>(ImportResult.ImportOutcome.class);

    ProductImportWorker(ProductImportService imports, Lifecycle lifecycle, CatalogConfig config,
            MeterRegistry registry) {
        this.imports = imports;
        this.lifecycle = lifecycle;
        this.config = config;
        for (ImportResult.ImportOutcome outcome : ImportResult.ImportOutcome.values())
            lines.put(outcome, Counter.builder("catalog.imports.lines")
                    .description("Linhas de importação de produtos processadas, por resultado")
                    .tag("outcome", outcome.name()).register(registry));
    }

    /**
     * Reivindica um lote, converte o CSV no pool de CPU ({@code Offload}) e
     * processa as linhas pendentes, contando-as em {@code catalog.imports.lines}.
     * Falha é registrada; {@code concurrentExecution = SKIP} impede sobreposição
     * no pod.
     */
    @Scheduled(identity = "product-import", every = "${catalog.imports.interval}", concurrentExecution = SKIP,
            skipExecutionIf = ApplicationNotRunning.class)
    public Uni<Void> run() {
        return lifecycle.job(() -> imports.claimNext(config.imports().lease())
                .flatMap(claimed -> claimed.map(this::process).orElseGet(() -> Uni.createFrom().voidItem()))
                .onFailure().invoke(e -> LOG.warn("Falha ao processar importação de produtos; o lote será retomado "
                        + "depois do lease", e))
                .onFailure().recoverWithNull()
                .replaceWithVoid());
    }

    /**
     * Processa o lote numa etapa própria da execução, com tenant, loja e lote no span e no MDC (ADR 021); o lote
     * liga esta etapa à requisição que o enviou, que tem o mesmo {@code marketplace.import.id}. As linhas processadas
     * nesta execução vão no atributo {@code marketplace.import.lines} da etapa.
     */
    private Uni<Void> process(ProductImportRepository.Claimed claimed) {
        ProductImport job = claimed.job();
        return lifecycle.step("product-import process", () -> {
                    Telemetry.tag(Telemetry.TENANT, job.tenantId());
                    Telemetry.tag(Telemetry.STORE, job.storeId());
                    Telemetry.tag("marketplace.import.id", job.id());
                    return lifecycle.nested(ProductCsv.class, "rows", () -> Offload.cpu(() -> ProductCsv.rows(claimed.content())))
                            .flatMap(rows -> imports.process(job, rows))
                            .invoke(results -> {
                                count(claimed, results);
                                Span.current().setAttribute("marketplace.import.lines", results.size());
                            })
                            .replaceWithVoid();
                });
    }

    private void count(ProductImportRepository.Claimed claimed, List<ImportResult> results) {
        results.forEach(result -> lines.get(result.outcome()).increment());
        LOG.infof("Importação %s concluída: %d linha(s) processada(s) nesta execução", claimed.job().id(),
                results.size());
    }
}
