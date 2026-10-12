package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Physical reference/configuration only; owned item slots remain in canonical SavedData. */
public final class BufferData {
  public static final String FIELD = "FactoryCoreBuffer";

  public record State(NodeRegistry.Handle lease, UUID principal, long configuration) {
    public State {
      Objects.requireNonNull(lease);
      Objects.requireNonNull(principal);
      if (lease.kind() != NodeRegistry.Kind.BUFFERED_INTERFACE || configuration <= 0)
        throw new IllegalArgumentException("Invalid buffered interface reference");
    }
  }

  private BufferData() {}

  public static CompoundTag write(State state) {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.putUUID("World", state.lease().world());
    tag.putUUID("Id", state.lease().id());
    tag.putLong("Generation", state.lease().generation());
    tag.putUUID("Principal", state.principal());
    tag.putLong("Configuration", state.configuration());
    return tag;
  }

  public static State read(Tag raw) {
    if (!(raw instanceof CompoundTag tag))
      throw new IllegalArgumentException("Buffer reference is not a compound");
    NetworkStateCodec.fields(
        tag, Set.of("Schema", "World", "Id", "Generation", "Principal", "Configuration"));
    if (!tag.contains("Schema", Tag.TAG_INT)
        || tag.getInt("Schema") != 1
        || !tag.hasUUID("World")
        || !tag.hasUUID("Id")
        || !tag.hasUUID("Principal")
        || !tag.contains("Generation", Tag.TAG_LONG)
        || !tag.contains("Configuration", Tag.TAG_LONG))
      throw new IllegalArgumentException("Unsupported/malformed buffer reference");
    return new State(
        new NodeRegistry.Handle(
            tag.getUUID("World"),
            tag.getUUID("Id"),
            tag.getLong("Generation"),
            NodeRegistry.Kind.BUFFERED_INTERFACE),
        tag.getUUID("Principal"),
        tag.getLong("Configuration"));
  }
}
