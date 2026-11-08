package dev.marketplace.cataloginventory.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.marketplace.cataloginventory.application.ImportResult;
import dev.marketplace.cataloginventory.application.ImportResult.ImportOutcome;
import dev.marketplace.cataloginventory.application.ProductImport;
import dev.marketplace.cataloginventory.application.ProductImportService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos HTTP da importação de produtos por planilha CSV. */
public final class ProductImportModels {
    private ProductImportModels() {
    }

    public record ImportCounts(int created, int updated, int unchanged, int failed) {
        static ImportCounts of(List<ImportResult> lines) {
            return new ImportCounts(count(lines, ImportOutcome.CREATED), count(lines, ImportOutcome.UPDATED),
                    count(lines, ImportOutcome.UNCHANGED), count(lines, ImportOutcome.FAILED));
        }

        private static int count(List<ImportResult> lines, ImportOutcome outcome) {
            return (int) lines.stream().filter(line -> line.outcome() == outcome).count();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportLineResponse(
            @Schema(description = "Linha do arquivo onde o registro começa; o cabeçalho é a linha 1") int lineNumber,
            @Schema(description = "SKU do seller lido da linha; ausente se a coluna veio vazia", nullable = true) String sellerSku,
            ImportOutcome outcome,
            @Schema(description = "Motivo estável da recusa; só em FAILED", nullable = true) String code,
            @Schema(description = "Descrição da recusa; só em FAILED", nullable = true) String message,
            @Schema(description = "Produto criado ou alterado pela linha, quando conhecido", nullable = true) UUID productId) {
        static ImportLineResponse of(ImportResult r) {
            return new ImportLineResponse(r.lineNumber(), r.sellerSku(), r.outcome(), r.code(), r.message(),
                    r.productId());
        }
    }

    public record ImportResponse(UUID id, ProductImport.ImportStatus status,
            @Schema(description = "Linhas de dados do arquivo, sem o cabeçalho") int totalLines,
            @Schema(description = "Linhas que já têm resultado") int processedLines, ImportCounts counts,
            Instant createdAt,
            @Schema(description = "Início ou último progresso do processamento", nullable = true) Instant startedAt,
            @Schema(description = "Conclusão do processamento", nullable = true) Instant finishedAt,
            @Schema(description = "Resultados em ordem de linha") List<ImportLineResponse> lines) {
        static ImportResponse of(ProductImport job, List<ImportResult> lines) {
            return new ImportResponse(job.id(), job.status(), job.totalLines(), lines.size(), ImportCounts.of(lines),
                    job.createdAt(), job.startedAt(), job.finishedAt(),
                    lines.stream().map(ImportLineResponse::of).toList());
        }

        static ImportResponse of(ProductImportService.Report report) {
            return of(report.job(), report.lines());
        }
    }
}
