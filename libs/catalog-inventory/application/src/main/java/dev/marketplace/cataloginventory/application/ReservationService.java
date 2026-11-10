package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.InvalidTransitionException;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.inventory.*;
import io.smallrye.mutiny.Uni;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reservas de estoque usadas pelo checkout e pelo OMS: criação idempotente,
 * confirmação (baixa), liberação,
 * extensão e expiração. Resultados de negócio (falta de saldo, preço mudou) são
 * recusas tipadas, nunca retentadas.
 */
public final class ReservationService {
  public record Settings(Duration defaultTtl, Duration maxTtl, Duration maxLifetime) {
  }

  public record Request(String idempotencyKey, String orderRef, Optional<Duration> ttl, List<RequestedItem> items) {
  }

  /**
   * {@code replayed} indica que a chave já tinha sido usada com a mesma
   * requisição.
   */
  public record Result(Reservation reservation, boolean replayed) {
  }

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");
  private final ReservationRepository reservations;
  private final InventoryRepository inventory;
  private final Settings settings;
  private final Clock clock;

  public ReservationService(ReservationRepository reservations, InventoryRepository inventory, Settings settings,
      Clock clock) {
    this.reservations = reservations;
    this.inventory = inventory;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * Reserva o estoque dos itens, idempotente por {@code idempotencyKey} (exige
   * {@code MANAGE_RESERVATIONS}; chave
   * inválida: {@link InvalidInputException}). Mesma chave e mesma requisição
   * devolvem a original ({@code replayed});
   * requisição diferente: {@code idempotency-key-reused}. Tudo ou nada: item
   * recusado (SKU desconhecido, produto
   * inativo, seller não aprovado, preço mudado, saldo insuficiente) lança
   * {@code ReservationRefusal}.
   */
  public Uni<Result> reserve(Actor actor, Request request) {
    return Uni.createFrom().deferred(() -> {
      actor.require(Capability.MANAGE_RESERVATIONS);
      if (request.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(request.idempotencyKey()).matches())
        throw new InvalidInputException("Idempotency-Key é obrigatório (1 a 255 caracteres visíveis)");
      String hash = hash(request);
      return findByKey(actor, request).flatMap(existing -> existing
          .map(found -> Uni.createFrom().item(replay(found, hash)))
          .orElseGet(() -> open(actor, request, hash)));
    });
  }

  /**
   * Busca a reserva do tenant do chamador. Exige {@code MANAGE_RESERVATIONS};
   * inexistente ou de outro tenant lança
   * {@link NotFoundException}.
   */
  public Uni<Reservation> get(Actor actor, UUID id) {
    return Uni.createFrom().deferred(() -> {
      actor.require(Capability.MANAGE_RESERVATIONS);
      return load(actor, id);
    });
  }

  /**
   * Idempotente: liberar de novo (ou após expirar) não devolve estoque duas
   * vezes.
   */
  public Uni<Reservation> release(Actor actor, UUID id) {
    return Uni.createFrom().deferred(() -> {
      actor.require(Capability.MANAGE_RESERVATIONS);
      return reservations.release(actor.tenantId(), id, clock.instant()).flatMap(released -> released.isPresent()
          ? Uni.createFrom().item(released.get())
          : load(actor, id).map(current -> switch (current.status()) {
            case RELEASED, EXPIRED -> current;
            case COMMITTED -> throw new InvalidTransitionException("RELEASE", current.status().name());
            case ACTIVE -> throw new ConcurrentUpdateException();
          }));
    });
  }

  /**
   * Baixa definitiva (pagamento confirmado); idempotente para reserva já
   * confirmada.
   */
  public Uni<Reservation> commit(Actor actor, UUID id) {
    return Uni.createFrom().deferred(() -> {
      actor.require(Capability.MANAGE_RESERVATIONS);
      Instant now = clock.instant();
      return reservations.commit(actor.tenantId(), id, now).flatMap(committed -> committed.isPresent()
          ? Uni.createFrom().item(committed.get())
          : load(actor, id).map(current -> {
            if (current.status() == ReservationStatus.COMMITTED)
              return current;
            if (current.status() == ReservationStatus.ACTIVE && current.overdueAt(now))
              throw expired(current);
            if (current.status() == ReservationStatus.ACTIVE)
              throw new ConcurrentUpdateException();
            throw notActive(current);
          }));
    });
  }

  /**
   * Redefine o vencimento da reserva ativa e não vencida para agora mais
   * {@code ttl} (não soma ao prazo atual). Exige
   * {@code MANAGE_RESERVATIONS}. {@code ttl} inválido: {@code invalid-value};
   * reserva não ativa:
   * {@code reservation-not-active}; vencida: {@code reservation-expired}; além do
   * tempo de vida máximo:
   * {@code reservation-lifetime-exceeded}.
   */
  public Uni<Reservation> extend(Actor actor, UUID id, Duration ttl) {
    return Uni.createFrom().deferred(() -> {
      actor.require(Capability.MANAGE_RESERVATIONS);
      if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(settings.maxTtl()) > 0)
        throw DomainRuleViolation
            .invalid("Prazo deve ser positivo e de até " + settings.maxTtl().toMinutes() + " minutos");
      Instant now = clock.instant();
      return load(actor, id).flatMap(current -> {
        if (current.status() != ReservationStatus.ACTIVE)
          throw notActive(current);
        if (current.overdueAt(now))
          throw expired(current);
        Instant newExpiry = now.plus(ttl);
        if (newExpiry.isAfter(current.createdAt().plus(settings.maxLifetime())))
          throw lifetimeExceeded(current);
        return reservations.extend(actor.tenantId(), id, newExpiry, now)
            .map(extended -> extended.orElseThrow(() -> expired(current)));
      });
    });
  }

  /** Executado pelo varredor do sistema; não depende de chamador. */
  public Uni<Integer> expireDue(int batch) {
    return reservations.expireDue(clock.instant(), batch);
  }

  private Uni<Reservation> load(Actor actor, UUID id) {
    return reservations.find(actor.tenantId(), id)
        .map(found -> found.orElseThrow(() -> new NotFoundException("Reserva")));
  }

  private Uni<Optional<Reservation>> findByKey(Actor actor, Request request) {
    return reservations.findByIdempotencyKey(actor.tenantId(), request.idempotencyKey());
  }

  /**
   * Abre a reserva nova. Se outra chamada com a mesma chave gravou primeiro
   * (corrida), devolve a dela como replay, ou {@link ConcurrentUpdateException}
   * se ela não aparecer.
   */
  private Uni<Result> open(Actor actor, Request request, String hash) {
    List<UUID> skuIds = request.items() == null
        ? List.of()
        : request.items().stream().map(RequestedItem::skuId).toList();
    return inventory.candidates(actor.tenantId(), skuIds).flatMap(candidates -> {
      Instant now = clock.instant();
      Reservation reservation = ReservationPolicy.open(UUID.randomUUID(), actor.tenantId(),
          request.idempotencyKey(), hash,
          request.orderRef(), request.items(), candidates, now,
          request.ttl().orElse(settings.defaultTtl()), settings.maxTtl());
      return reservations.insert(reservation).replaceWith(new Result(reservation, false))
          .onFailure(DuplicateException.class)
          .recoverWithUni(() -> findByKey(actor, request)
              .map(raced -> replay(raced.orElseThrow(ConcurrentUpdateException::new), hash)));
    });
  }

  /**
   * Reaproveita a reserva já criada com a chave quando a requisição é idêntica
   * (mesmo hash); com requisição diferente
   * lança {@code idempotency-key-reused}.
   */
  private static Result replay(Reservation existing, String hash) {
    if (!existing.requestHash().equals(hash))
      throw new DomainRuleViolation(ApplicationReasons.IDEMPOTENCY_KEY_REUSED,
          "Idempotency-Key já usado com outra requisição");
    return new Result(existing, true);
  }

  private static ReservationRefusal expired(Reservation r) {
    return new ReservationRefusal(ApplicationReasons.RESERVATION_EXPIRED,
        "O prazo da reserva venceu; refaça a reserva",
        List.of(Reason.of(ApplicationReasons.RESERVATION_EXPIRED, r.id())));
  }

  private static ReservationRefusal notActive(Reservation r) {
    return new ReservationRefusal(ApplicationReasons.RESERVATION_NOT_ACTIVE,
        "Reserva não está ativa (" + r.status() + ")",
        List.of(Reason.of(ApplicationReasons.RESERVATION_NOT_ACTIVE, r.id())));
  }

  private static ReservationRefusal lifetimeExceeded(Reservation r) {
    return new ReservationRefusal(ApplicationReasons.RESERVATION_LIFETIME_EXCEEDED,
        "A reserva atingiu o prazo máximo total",
        List.of(Reason.of(ApplicationReasons.RESERVATION_LIFETIME_EXCEEDED, r.id())));
  }

  /**
   * Hash canônico da requisição: a mesma chave só pode repetir exatamente o mesmo
   * pedido.
   */
  static String hash(Request request) {
    StringBuilder canonical = new StringBuilder(String.valueOf(request.orderRef())).append('|')
        .append(request.ttl().map(Duration::toSeconds).map(String::valueOf).orElse("default"));
    if (request.items() != null) {
      request.items().stream().sorted(Comparator.comparing(RequestedItem::skuId))
          .forEach(i -> canonical.append('|')
              .append(i.skuId()).append(':').append(i.quantity()).append(':')
              .append(i.expectedUnitPrice().amount().toPlainString())
              .append(':').append(i.expectedUnitPrice().currency()));
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
