package dev.izzy.factorycore.core.resource;

import static dev.izzy.factorycore.core.resource.OperationResult.Reason.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared foreign claim metadata; never owns or caches foreign resource quantities. */
public final class ExternalClaims {
  public static final int MAX_CLAIMS = 4096;
  public static final long MAX_BYTES = 4 * 1024 * 1024;

  public record Claim(UUID backing, UUID owner, ResourceKey key, long amount) {
    public Claim {
      Objects.requireNonNull(backing);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(key);
      if (amount <= 0) throw new IllegalArgumentException("Claim must be positive");
    }
  }

  private record Stock(UUID backing, ResourceKey key) {}

  private record Identity(Stock stock, UUID owner) {}

  private final Thread thread = Thread.currentThread();
  private final Map<Identity, Long> claims = new HashMap<>();
  private final Map<Stock, Long> reserved = new HashMap<>();
  private final Map<UUID, Integer> backingClaims = new HashMap<>();
  private final Runnable changed;
  private long bytes;
  private boolean extracting;

  public ExternalClaims() {
    this(List.of(), () -> {});
  }

  /** Restore holds even when foreign stock is absent; loading never releases orphan claims. */
  public ExternalClaims(List<Claim> state) {
    this(state, () -> {});
  }

  public ExternalClaims(List<Claim> state, Runnable changed) {
    this.changed = Objects.requireNonNull(changed);
    for (var entry : state) {
      var stock = new Stock(entry.backing(), entry.key());
      var identity = new Identity(stock, entry.owner());
      long charge = charge(entry.key());
      if (claims.containsKey(identity) || claims.size() >= MAX_CLAIMS || bytes > MAX_BYTES - charge)
        throw new IllegalArgumentException("Duplicate or excessive external claims");
      try {
        reserved.merge(stock, entry.amount(), Math::addExact);
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException("External claims overflow", overflow);
      }
      claims.put(identity, entry.amount());
      backingClaims.merge(entry.backing(), 1, Integer::sum);
      bytes += charge;
    }
  }

  public List<Claim> snapshot() {
    checkThread();
    return claims.entrySet().stream()
        .map(
            entry ->
                new Claim(
                    entry.getKey().stock().backing(), entry.getKey().owner(),
                    entry.getKey().stock().key(), entry.getValue()))
        .toList();
  }

  /** Constant-time metadata query; orphaned restored holds remain visible without reading stock. */
  public boolean hasBacking(UUID backing) {
    checkThread();
    return backingClaims.containsKey(Objects.requireNonNull(backing));
  }

  public long reserved(UUID backing, ResourceKey key) {
    checkThread();
    return reserved.getOrDefault(stock(backing, key), 0L);
  }

  public long held(UUID backing, UUID owner, ResourceKey key) {
    checkThread();
    return claims.getOrDefault(
        new Identity(stock(backing, key), Objects.requireNonNull(owner)), 0L);
  }

  /** Observed total must be freshly validated by the caller; reservation is all or nothing. */
  public OperationResult reserve(
      UUID backing, UUID owner, ResourceKey key, long requested, long observed) {
    checkMutable();
    OperationResult.requireQuantity(requested);
    OperationResult.requireQuantity(observed);
    var stock = stock(backing, key);
    var identity = new Identity(stock, Objects.requireNonNull(owner));
    if (requested == 0) return new OperationResult(0, OK);
    if (!claims.containsKey(identity)
        && (claims.size() >= MAX_CLAIMS || bytes > MAX_BYTES - charge(key)))
      return new OperationResult(0, RESERVATION_LIMIT);
    if (requested > available(backing, null, key, observed))
      return new OperationResult(0, SHORTAGE);
    if (!claims.containsKey(identity)) {
      bytes += charge(key);
      backingClaims.merge(backing, 1, Integer::sum);
    }
    claims.merge(identity, requested, Math::addExact);
    reserved.merge(stock, requested, Math::addExact);
    changed.run();
    return new OperationResult(requested, OK);
  }

  /** A shortage never grants access to another owner's held quantity. Null owner is background. */
  public long available(UUID backing, UUID owner, ResourceKey key, long observed) {
    checkThread();
    OperationResult.requireQuantity(observed);
    var stock = stock(backing, key);
    long totalHeld = reserved.getOrDefault(stock, 0L);
    long own = owner == null ? 0 : claims.getOrDefault(new Identity(stock, owner), 0L);
    long others = totalHeld - own;
    long unclaimedByOthers = observed > others ? observed - others : 0;
    return owner == null ? unclaimedByOthers : Math.min(own, unclaimedByOthers);
  }

  /** Debit only a known actual extraction. Unknown external outcomes leave the claim held. */
  public void consume(UUID backing, UUID owner, ResourceKey key, long actual) {
    checkMutable();
    OperationResult.requireQuantity(actual);
    var stock = stock(backing, key);
    var identity = new Identity(stock, Objects.requireNonNull(owner));
    long old = claims.getOrDefault(identity, 0L);
    if (actual > old) throw new IllegalArgumentException("Actual extraction exceeds held claim");
    if (actual == 0) return;
    long remaining = old - actual;
    if (remaining == 0) {
      claims.remove(identity);
      backingClaims.compute(backing, (unused, count) -> count == 1 ? null : count - 1);
      bytes -= charge(key);
    } else claims.put(identity, remaining);
    long total = reserved.get(stock) - actual;
    if (total == 0) reserved.remove(stock);
    else reserved.put(stock, total);
    changed.run();
  }

  public long release(UUID backing, UUID owner, ResourceKey key) {
    long amount = held(backing, owner, key);
    consume(backing, owner, key, amount);
    return amount;
  }

  public long metadataBytes() {
    checkThread();
    return bytes;
  }

  long extract(
      ResourcePort delegate,
      UUID owner,
      ResourceKey key,
      long requested,
      java.util.function.ToLongFunction<ResourceKey> observed) {
    checkMutable();
    OperationResult.requireQuantity(requested);
    if (requested == 0) return 0;
    long actual;
    extracting = true;
    try {
      long allowed =
          Math.min(
              requested, available(delegate.backingId(), owner, key, observed.applyAsLong(key)));
      if (allowed == 0) return 0;
      actual = delegate.extract(key, allowed);
      if (actual < 0 || actual > allowed)
        throw new IllegalStateException("Invalid claimed extraction amount");
    } finally {
      extracting = false;
    }
    if (owner != null) consume(delegate.backingId(), owner, key, actual);
    return actual;
  }

  void checkMutable() {
    checkThread();
    if (extracting) throw new IllegalStateException("Claim mutation or extraction cannot reenter");
  }

  private static Stock stock(UUID backing, ResourceKey key) {
    return new Stock(Objects.requireNonNull(backing), Objects.requireNonNull(key));
  }

  private static long charge(ResourceKey key) {
    return 256L + 3L * key.registryId().length() + key.componentByteCount();
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Claims require their owning thread");
  }
}
