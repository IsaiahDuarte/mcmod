package dev.izzy.factorycore.core.resource;

import java.util.Objects;

/**
 * Best-effort physical stock goal; reservations at the destination still count as resident stock.
 */
public record StockTarget(ResourceKind kind, long target, long quantum) {
  public StockTarget {
    Objects.requireNonNull(kind);
    OperationResult.requireQuantity(target);
    if (quantum < 1 || quantum > ThroughputTier.maxQuantum(kind))
      throw new IllegalArgumentException("Stock request exceeds shared transfer quantum");
  }

  /**
   * Next bounded request, in items/mB/FE. Include all known inbound staging for this target/key.
   * This is planning only: execution must revalidate stock, claims, capacity, scope and authority.
   */
  public long missing(long resident, long inbound) {
    OperationResult.requireQuantity(resident);
    OperationResult.requireQuantity(inbound);
    if (resident >= target) return 0;
    long remaining = target - resident;
    return inbound >= remaining ? 0 : Math.min(quantum, remaining - inbound);
  }
}
