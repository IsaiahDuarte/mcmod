package dev.izzy.factorycore.core.resource;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit aggregate query over a bounded caller-selected list of backing stores. */
public final class StorageTotals {
  private StorageTotals() {}

  public static BigInteger total(List<ResourceLedger> stores, ResourceKey key) {
    Map<UUID, ResourceLedger> unique = new HashMap<>();
    BigInteger total = BigInteger.ZERO;
    for (var store : stores) {
      var previous = unique.putIfAbsent(store.id(), store);
      if (previous == store) continue;
      if (previous != null)
        throw new IllegalArgumentException("Conflicting backing store identity: " + store.id());
      total = total.add(BigInteger.valueOf(store.stock(key).total()));
    }
    return total;
  }
}
