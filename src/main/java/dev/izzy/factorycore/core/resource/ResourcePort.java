package dev.izzy.factorycore.core.resource;

import java.util.UUID;

/**
 * Bounded synchronous endpoint. Simulation is advisory; actual calls return units
 * extracted/accepted. Calls must not mutate supplied identities. Throwing after a possible external
 * mutation is uncertain.
 */
public interface ResourcePort {
  UUID backingId();

  long simulateInsert(ResourceKey key, long offered);

  long extract(ResourceKey key, long requested);

  long insert(ResourceKey key, long offered);
}
