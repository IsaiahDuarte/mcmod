package dev.izzy.factorycore.core.resource;

import java.util.Objects;
import java.util.UUID;

/** Reservation-aware digital endpoint; null claim owner selects unreserved stock. */
public final class LedgerPort implements ResourcePort {
  private final ResourceLedger ledger;
  private final UUID claimOwner;

  public LedgerPort(ResourceLedger ledger, UUID claimOwner) {
    this.ledger = Objects.requireNonNull(ledger);
    this.claimOwner = claimOwner;
  }

  @Override
  public UUID backingId() {
    return ledger.id();
  }

  @Override
  public long simulateInsert(ResourceKey key, long offered) {
    OperationResult.requireQuantity(offered);
    return Math.min(offered, ledger.space(key));
  }

  @Override
  public long extract(ResourceKey key, long requested) {
    return ledger.extract(key, requested, claimOwner).amount();
  }

  @Override
  public long insert(ResourceKey key, long offered) {
    return ledger.insert(key, offered).amount();
  }
}
