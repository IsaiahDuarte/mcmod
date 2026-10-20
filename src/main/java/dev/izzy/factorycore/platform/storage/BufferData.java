package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Physical reference/configuration only; owned item slots remain in canonical SavedData. Schema two
 * adds the installed {@link ThroughputTier} (ADR 0054) and is written only for an upgraded buffer,
 * so base buffers keep their schema-one bytes and older builds reject upgraded data into recovery.
 */
public final class BufferData {
  public static final String FIELD = "FactoryCoreBuffer";
  private static final Set<String> SCHEMA_ONE =
      Set.of("Schema", "World", "Id", "Generation", "Principal", "Configuration");
  private static final Set<String> SCHEMA_TWO =
      Set.of("Schema", "World", "Id", "Generation", "Principal", "Configuration", "Throughput");

  public record State(
      NodeRegistry.Handle lease, UUID principal, long configuration, ThroughputTier tier) {
    public State(NodeRegistry.Handle lease, UUID principal, long configuration) {
      this(lease, principal, configuration, ThroughputTier.BASE);
    }

    public State {
      Objects.requireNonNull(lease);
      Objects.requireNonNull(principal);
      Objects.requireNonNull(tier);
      if (lease.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE || configuration <= 0)
        throw new IllegalArgumentException("Invalid buffered interface reference");
    }
  }

  private BufferData() {}

  public static CompoundTag write(State state) {
    var tag = new CompoundTag();
    boolean upgraded = state.tier() != ThroughputTier.BASE;
    tag.putInt("Schema", upgraded ? 2 : 1);
    tag.putUUID("World", state.lease().world());
    tag.putUUID("Id", state.lease().id());
    tag.putLong("Generation", state.lease().generation());
    tag.putUUID("Principal", state.principal());
    tag.putLong("Configuration", state.configuration());
    if (upgraded) tag.putString("Throughput", state.tier().name());
    return tag;
  }

  public static State read(Tag raw) {
    if (!(raw instanceof CompoundTag tag))
      throw new IllegalArgumentException("Buffer reference is not a compound");
    int schema = tag.contains("Schema", Tag.TAG_INT) ? tag.getInt("Schema") : 0;
    NetworkStateCodec.fields(tag, schema == 2 ? SCHEMA_TWO : SCHEMA_ONE);
    if (schema != 1 && schema != 2
        || !tag.hasUUID("World")
        || !tag.hasUUID("Id")
        || !tag.hasUUID("Principal")
        || !tag.contains("Generation", Tag.TAG_LONG)
        || !tag.contains("Configuration", Tag.TAG_LONG)
        || schema == 2 && !tag.contains("Throughput", Tag.TAG_STRING))
      throw new IllegalArgumentException("Unsupported/malformed buffer reference");
    var tier =
        schema == 2 ? ThroughputTier.parse(tag.getString("Throughput")) : ThroughputTier.BASE;
    if (schema == 2 && tier == ThroughputTier.BASE)
      throw new IllegalArgumentException("Upgraded buffer schema needs a nonbase tier");
    return new State(
        new NodeRegistry.Handle(
            tag.getUUID("World"),
            tag.getUUID("Id"),
            tag.getLong("Generation"),
            NodeRegistry.Kind.BUFFERED_INTERFACE),
        tag.getUUID("Principal"),
        tag.getLong("Configuration"),
        tier);
  }
}
