package dev.marketplace.cataloginventory.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

/** Tamanho do balde das séries de atividade da página inicial: dia, mês ou ano, no fuso do painel. */
public enum StatsGranularity {
  DAY("day", ChronoUnit.DAYS), MONTH("month", ChronoUnit.MONTHS), YEAR("year", ChronoUnit.YEARS);

  private final String code;
  private final ChronoUnit unit;

  StatsGranularity(String code, ChronoUnit unit) {
    this.code = code;
    this.unit = unit;
  }

  /** Código usado na API e no {@code date_trunc} do PostgreSQL. */
  public String code() {
    return code;
  }

  /**
   * Granularidade pelo código; ausente vale {@code month}, e código desconhecido lança
   * {@link InvalidInputException}.
   */
  public static StatsGranularity parse(Optional<String> code) {
    return code.map(value -> Arrays.stream(values()).filter(g -> g.code.equals(value)).findFirst()
        .orElseThrow(() -> new InvalidInputException("granularity deve ser day, month ou year")))
        .orElse(MONTH);
  }

  /** Início do balde que contém {@code date}. */
  LocalDate truncate(LocalDate date) {
    return switch (this) {
      case DAY -> date;
      case MONTH -> date.withDayOfMonth(1);
      case YEAR -> date.withDayOfYear(1);
    };
  }

  /** Início do balde seguinte a {@code bucket}. */
  LocalDate next(LocalDate bucket) {
    return bucket.plus(1, unit);
  }

  /** Baldes de {@code first} a {@code last}, os dois incluídos. */
  long count(LocalDate first, LocalDate last) {
    return unit.between(first, last) + 1;
  }
}
