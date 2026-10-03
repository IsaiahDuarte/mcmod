package dev.izzy.factorycore.core.resource;

import static dev.izzy.factorycore.core.resource.OperationResult.Reason.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One backing store. All access is confined to its owning thread. */
public final class ResourceLedger {
  public record Stock(long total, long reserved, long available, boolean loaded) {}

  private record Claim(UUID owner, ResourceKey key) {}

  private final UUID id;
  private final ResourceKind kind;
  private final Thread thread = Thread.currentThread();
  private final int catalogLimit;
  private final int reservationLimit;
  private final Map<ResourceKey, Long> contents = new HashMap<>();
  private final Map<ResourceKey, Long> reserved = new HashMap<>();
  private final Map<Claim, Long> claims = new HashMap<>();
  private long capacity;
  private long total;
  private boolean infinite;
  private boolean loaded = true;

  public ResourceLedger(
      UUID id,
      ResourceKind kind,
      long capacity,
      boolean infinite,
      int catalogLimit,
      int reservationLimit) {
    this.id = Objects.requireNonNull(id);
    this.kind = Objects.requireNonNull(kind);
    OperationResult.requireQuantity(capacity);
    if (catalogLimit <= 0 || reservationLimit <= 0 || (infinite && kind != ResourceKind.ITEM)) {
      throw new IllegalArgumentException("Invalid storage limits or unsupported infinite tier");
    }
    this.capacity = capacity;
    this.infinite = infinite;
    this.catalogLimit = catalogLimit;
    this.reservationLimit = reservationLimit;
  }

  public UUID id() {
    return id;
  }

  public ResourceKind kind() {
    return kind;
  }

  private void checkThread() {
    if (Thread.currentThread() != thread) {
      throw new IllegalStateException("Mutable ledger access requires its owning thread");
    }
  }

  public Stock stock(ResourceKey key) {
    checkThread();
    long count = contents.getOrDefault(key, 0L);
    long held = reserved.getOrDefault(key, 0L);
    return new Stock(count, held, loaded ? count - held : 0, loaded);
  }

  public long total() {
    checkThread();
    return total;
  }

  public long space(ResourceKey key) {
    checkThread();
    if (!loaded
        || key.kind() != kind
        || (!contents.containsKey(key) && contents.size() >= catalogLimit)) {
      return 0;
    }
    return (infinite ? Long.MAX_VALUE : capacity) - total;
  }

  public Map<ResourceKey, Long> snapshot() {
    checkThread();
    return Map.copyOf(contents);
  }

  /**
   * Capture on the owner thread; the immutable result can be serialized away from world mutation.
   */
  public LedgerState persistentState() {
    checkThread();
    List<LedgerState.Reservation> reservations =
        claims.entrySet().stream()
            .map(
                entry ->
                    new LedgerState.Reservation(
                        entry.getKey().owner(), entry.getKey().key(), entry.getValue()))
            .toList();
    return new LedgerState(
        id, kind, capacity, infinite, catalogLimit, reservationLimit, contents, reservations);
  }

  /**
   * Rebuild on the new owner thread; the caller must revalidate before making this ledger loaded.
   */
  public static ResourceLedger restore(LedgerState state) {
    Objects.requireNonNull(state);
    var ledger =
        new ResourceLedger(
            state.id(),
            state.kind(),
            state.capacity(),
            state.infinite(),
            state.catalogLimit(),
            state.reservationLimit());
    for (var entry : state.contents().entrySet()) {
      if (ledger.insert(entry.getKey(), entry.getValue()).amount() != entry.getValue()) {
        throw new IllegalStateException("Validated snapshot stock could not be restored");
      }
    }
    for (var reservation : state.reservations()) {
      if (ledger.reserve(reservation.owner(), reservation.key(), reservation.amount()).amount()
          != reservation.amount()) {
        throw new IllegalStateException("Validated snapshot reservation could not be restored");
      }
    }
    ledger.setLoaded(false);
    return ledger;
  }

  public void setLoaded(boolean loaded) {
    checkThread();
    this.loaded = loaded;
  }

  public OperationResult insert(ResourceKey key, long requested) {
    checkThread();
    OperationResult.requireQuantity(requested);
    if (key.kind() != kind) return new OperationResult(0, WRONG_RESOURCE);
    if (!loaded) return new OperationResult(0, OFFLINE);
    if (requested == 0) return new OperationResult(0, OK);
    if (!contents.containsKey(key) && contents.size() >= catalogLimit) {
      return new OperationResult(0, CATALOG_LIMIT);
    }
    long amount = Math.min(requested, space(key));
    if (amount > 0) {
      contents.merge(key, amount, Math::addExact);
      total = Math.addExact(total, amount);
    }
    return new OperationResult(
        amount, amount == requested ? OK : infinite ? TECHNICAL_LIMIT : FULL);
  }

  /** Null owner means background access; an owner may extract only its own reserved claim. */
  public OperationResult extract(ResourceKey key, long requested, UUID owner) {
    checkThread();
    OperationResult.requireQuantity(requested);
    if (key.kind() != kind) return new OperationResult(0, WRONG_RESOURCE);
    if (!loaded) return new OperationResult(0, OFFLINE);
    var claim = owner == null ? null : new Claim(owner, key);
    long available = owner == null ? stock(key).available() : claims.getOrDefault(claim, 0L);
    long amount = Math.min(requested, available);
    if (amount > 0) {
      subtract(contents, key, amount);
      total -= amount;
      if (claim != null) {
        subtract(claims, claim, amount);
        subtract(reserved, key, amount);
      }
    }
    return new OperationResult(
        amount, amount == requested ? OK : stock(key).reserved() > 0 ? RESERVED : SHORTAGE);
  }

  /** All-or-nothing claim; repeated calls add to the existing owner's reservation. */
  public OperationResult reserve(UUID owner, ResourceKey key, long requested) {
    checkThread();
    Objects.requireNonNull(owner);
    OperationResult.requireQuantity(requested);
    if (key.kind() != kind) return new OperationResult(0, WRONG_RESOURCE);
    if (!loaded) return new OperationResult(0, OFFLINE);
    if (requested == 0) return new OperationResult(0, OK);
    var claim = new Claim(owner, key);
    if (!claims.containsKey(claim) && claims.size() >= reservationLimit) {
      return new OperationResult(0, RESERVATION_LIMIT);
    }
    if (stock(key).available() < requested) return new OperationResult(0, SHORTAGE);
    claims.merge(claim, requested, Math::addExact);
    reserved.merge(key, requested, Math::addExact);
    return new OperationResult(requested, OK);
  }

  public long release(UUID owner, ResourceKey key) {
    checkThread();
    var claim = new Claim(Objects.requireNonNull(owner), key);
    long amount = claims.getOrDefault(claim, 0L);
    claims.remove(claim);
    if (amount > 0) subtract(reserved, key, amount);
    return amount;
  }

  public OperationResult resize(long newCapacity, boolean newInfinite) {
    checkThread();
    OperationResult.requireQuantity(newCapacity);
    if (newInfinite && kind != ResourceKind.ITEM) return new OperationResult(0, WRONG_RESOURCE);
    if (!newInfinite && newCapacity < total) return new OperationResult(0, FULL);
    capacity = newCapacity;
    infinite = newInfinite;
    return new OperationResult(0, OK);
  }

  private static <K> void subtract(Map<K, Long> map, K key, long amount) {
    long remaining = Math.subtractExact(map.getOrDefault(key, 0L), amount);
    if (remaining == 0) map.remove(key);
    else map.put(key, remaining);
  }
}
