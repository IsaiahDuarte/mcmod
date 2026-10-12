package dev.izzy.factorycore.core.resource;

import java.util.Objects;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Revalidate foreign totals immediately before extraction; all callers share one claim registry.
 */
public final class ExternalClaimPort implements ResourcePort {
  private final ResourcePort delegate;
  private final ExternalClaims claims;
  private final UUID owner;
  private final ToLongFunction<ResourceKey> observed;

  /** Null owner accesses only unclaimed stock. The observation must not mutate or load chunks. */
  public ExternalClaimPort(
      ResourcePort delegate,
      ExternalClaims claims,
      UUID owner,
      ToLongFunction<ResourceKey> observed) {
    this.delegate = Objects.requireNonNull(delegate);
    this.claims = Objects.requireNonNull(claims);
    this.owner = owner;
    this.observed = Objects.requireNonNull(observed);
  }

  @Override
  public UUID backingId() {
    return delegate.backingId();
  }

  @Override
  public long simulateInsert(ResourceKey key, long amount) {
    claims.checkMutable();
    return delegate.simulateInsert(key, amount);
  }

  @Override
  public long insert(ResourceKey key, long amount) {
    claims.checkMutable();
    return delegate.insert(key, amount);
  }

  @Override
  public long extract(ResourceKey key, long amount) {
    return claims.extract(delegate, owner, key, amount, observed);
  }
}
