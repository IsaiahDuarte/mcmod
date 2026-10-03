package dev.izzy.factorycore.core.resource;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable exact identity. Component encoding belongs to the platform adapter. */
public final class ResourceKey {
  public static final int MAX_COMPONENT_BYTES = 65_536;
  private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
  public static final ResourceKey ENERGY =
      new ResourceKey(ResourceKind.ENERGY, "neoforge:energy", new byte[0]);
  private final ResourceKind kind;
  private final String registryId;
  private final byte[] components;
  private final int hash;

  public ResourceKey(ResourceKind kind, String registryId, byte[] components) {
    this.kind = Objects.requireNonNull(kind);
    this.registryId = Objects.requireNonNull(registryId);
    Objects.requireNonNull(components);
    if (registryId.length() > 256 || !REGISTRY_ID.matcher(registryId).matches()) {
      throw new IllegalArgumentException("Invalid or oversized registry identity");
    }
    if (components.length > MAX_COMPONENT_BYTES) {
      throw new IllegalArgumentException("Resource components exceed 65536-byte technical limit");
    }
    if (kind == ResourceKind.ENERGY
        && (!registryId.equals("neoforge:energy") || components.length != 0)) {
      throw new IllegalArgumentException("Energy must use the canonical FE identity");
    }
    this.components = components.clone();
    hash = 31 * Objects.hash(kind, registryId) + Arrays.hashCode(this.components);
  }

  public ResourceKind kind() {
    return kind;
  }

  public String registryId() {
    return registryId;
  }

  public byte[] components() {
    return components.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ResourceKey key
        && kind == key.kind
        && registryId.equals(key.registryId)
        && Arrays.equals(components, key.components);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return kind + ":" + registryId;
  }
}
