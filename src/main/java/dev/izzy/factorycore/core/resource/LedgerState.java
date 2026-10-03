package dev.izzy.factorycore.core.resource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable exact owned stock and claims; loaded availability is deliberately transient. */
public record LedgerState(
    UUID id,
    ResourceKind kind,
    long capacity,
    boolean infinite,
    int catalogLimit,
    int reservationLimit,
    Map<ResourceKey, Long> contents,
    List<Reservation> reservations) {
  public record Reservation(UUID owner, ResourceKey key, long amount) {
    public Reservation {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(key);
      if (amount <= 0) {
        throw new IllegalArgumentException("Reservation must be positive");
      }
    }
  }

  private record Claim(UUID owner, ResourceKey key) {}

  public LedgerState {
    Objects.requireNonNull(id);
    Objects.requireNonNull(kind);
    contents = Map.copyOf(contents);
    reservations = List.copyOf(reservations);
    if (capacity < 0
        || catalogLimit <= 0
        || reservationLimit <= 0
        || contents.size() > catalogLimit
        || reservations.size() > reservationLimit
        || (infinite && kind != ResourceKind.ITEM)) {
      throw new IllegalArgumentException("Invalid ledger configuration");
    }
    try {
      long total = 0;
      for (var entry : contents.entrySet()) {
        if (entry.getKey().kind() != kind || entry.getValue() <= 0) {
          throw new IllegalArgumentException("Invalid stock entry");
        }
        total = Math.addExact(total, entry.getValue());
      }
      if (!infinite && total > capacity) {
        throw new IllegalArgumentException("Stock exceeds capacity");
      }
      var claimed = new HashMap<ResourceKey, Long>();
      var unique = new HashSet<Claim>();
      for (var reservation : reservations) {
        if (reservation.key().kind() != kind
            || !unique.add(new Claim(reservation.owner(), reservation.key()))) {
          throw new IllegalArgumentException("Wrong-kind or duplicate claim");
        }
        long amount = claimed.merge(reservation.key(), reservation.amount(), Math::addExact);
        if (amount > contents.getOrDefault(reservation.key(), 0L)) {
          throw new IllegalArgumentException("Claims exceed stock");
        }
      }
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Ledger quantities exceed signed-long limit", overflow);
    }
  }
}
