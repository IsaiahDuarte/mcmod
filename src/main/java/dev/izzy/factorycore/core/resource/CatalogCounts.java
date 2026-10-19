package dev.izzy.factorycore.core.resource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Incremental exact advisory totals; each backing/key's full reservation is applied only once. */
public final class CatalogCounts {
  public static final int MAX_CONTRIBUTIONS = 65536;
  public static final long MAX_BYTES = 16L * 1024 * 1024;

  public record Row(
      ResourceKey key,
      BigInteger total,
      BigInteger reserved,
      BigInteger available,
      BigInteger capacity) {}

  public record Page(List<Row> rows, ResourceKey next) {
    public Page {
      rows = List.copyOf(rows);
    }
  }

  private record Source(UUID backing, ResourceKey key) {}

  private record Contribution(long total, long reserved, Long capacity) {}

  private static final class Aggregate {
    BigInteger total = BigInteger.ZERO;
    BigInteger reserved = BigInteger.ZERO;
    BigInteger available = BigInteger.ZERO;
    BigInteger capacity = BigInteger.ZERO;
    int unknownCapacity;
  }

  private final Thread thread = Thread.currentThread();
  private final Map<Source, Contribution> sources = new HashMap<>();
  private final TreeMap<ResourceKey, Aggregate> rows = new TreeMap<>();
  private long bytes;

  public OperationResult.Reason observe(
      UUID backing, ResourceKey key, long delta, long reserved, Long capacity) {
    checkThread();
    Objects.requireNonNull(backing);
    Objects.requireNonNull(key);
    OperationResult.requireQuantity(delta);
    OperationResult.requireQuantity(reserved);
    if (capacity != null && capacity < 0)
      throw new IllegalArgumentException("Negative observed capacity");
    var source = new Source(backing, key);
    var previous = sources.get(source);
    long charge = 256L + 3L * key.registryId().length() + key.componentByteCount();
    if (previous == null && (sources.size() == MAX_CONTRIBUTIONS || bytes > MAX_BYTES - charge))
      return OperationResult.Reason.TECHNICAL_LIMIT;
    long total;
    try {
      total = Math.addExact(previous == null ? 0 : previous.total(), delta);
    } catch (ArithmeticException overflow) {
      return OperationResult.Reason.TECHNICAL_LIMIT;
    }
    if (capacity != null && capacity < total)
      throw new IllegalArgumentException("Observed capacity below total");
    long oldReserved = previous == null ? 0 : previous.reserved();
    long oldAvailable = previous == null ? 0 : Math.max(0, previous.total() - oldReserved);
    var aggregate = rows.computeIfAbsent(key, ignored -> new Aggregate());
    aggregate.total = aggregate.total.add(BigInteger.valueOf(delta));
    aggregate.reserved = aggregate.reserved.add(BigInteger.valueOf(reserved - oldReserved));
    aggregate.available =
        aggregate.available.add(BigInteger.valueOf(Math.max(0, total - reserved) - oldAvailable));
    if (previous != null) {
      if (previous.capacity() == null) aggregate.unknownCapacity--;
      else
        aggregate.capacity = aggregate.capacity.subtract(BigInteger.valueOf(previous.capacity()));
    }
    if (capacity == null) aggregate.unknownCapacity++;
    else aggregate.capacity = aggregate.capacity.add(BigInteger.valueOf(capacity));
    sources.put(source, new Contribution(total, reserved, capacity));
    if (previous == null) bytes += charge;
    return OperationResult.Reason.OK;
  }

  public long additionalBytes(UUID backing, ResourceKey key) {
    checkThread();
    Objects.requireNonNull(backing);
    Objects.requireNonNull(key);
    return sources.containsKey(new Source(backing, key))
        ? 0
        : 256L + 3L * key.registryId().length() + key.componentByteCount();
  }

  public long bytes() {
    checkThread();
    return bytes;
  }

  /** Current exact aggregate, for bounded derived indexes on the same owner thread. */
  public Row row(ResourceKey key) {
    checkThread();
    var value = rows.get(Objects.requireNonNull(key));
    return value == null
        ? null
        : new Row(
            key,
            value.total,
            value.reserved,
            value.available,
            value.unknownCapacity == 0 ? value.capacity : null);
  }

  public Page page(ResourceKey after, int limit) {
    checkThread();
    if (limit < 1 || limit > 32)
      throw new IllegalArgumentException("Invalid catalog result page size");
    var iterator = (after == null ? rows : rows.tailMap(after, false)).entrySet().iterator();
    var result = new ArrayList<Row>();
    long charge = 0;
    ResourceKey last = null;
    boolean more = false;
    while (iterator.hasNext()) {
      var entry = iterator.next();
      long size =
          256L + 3L * entry.getKey().registryId().length() + entry.getKey().componentByteCount();
      if (result.size() == limit || charge > ResourceLedger.CATALOG_PAGE_BYTES - size) {
        more = true;
        break;
      }
      last = entry.getKey();
      var value = entry.getValue();
      result.add(
          new Row(
              last,
              value.total,
              value.reserved,
              value.available,
              value.unknownCapacity == 0 ? value.capacity : null));
      charge += size;
    }
    return new Page(result, more ? last : null);
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Catalog counts require owner thread");
  }
}
