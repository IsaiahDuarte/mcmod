package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NodeRegistry;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Reference-only local schema; malformed or future input must be retained by its holder. */
public final class NodeHandleData {
  public static final String FIELD = "FactoryCoreNode";

  private NodeHandleData() {}

  public static NodeRegistry.Handle read(Tag raw) {
    if (raw == null) return null;
    if (!(raw instanceof CompoundTag data)
        || !data.contains("Schema", Tag.TAG_INT)
        || data.getInt("Schema") != 1
        || !Set.of("Schema", "Lease").containsAll(data.getAllKeys()))
      throw new IllegalArgumentException("Unsupported structural reference data");
    if (!data.contains("Lease")) return null;
    if (!data.contains("Lease", Tag.TAG_COMPOUND))
      throw new IllegalArgumentException("Malformed structural lease");
    var lease = data.getCompound("Lease");
    if (!Set.of("World", "Id", "Generation", "Kind").equals(lease.getAllKeys())
        || !lease.hasUUID("World")
        || !lease.hasUUID("Id")
        || !lease.contains("Generation", Tag.TAG_LONG)
        || !lease.contains("Kind", Tag.TAG_STRING))
      throw new IllegalArgumentException("Malformed structural lease");
    return new NodeRegistry.Handle(
        lease.getUUID("World"),
        lease.getUUID("Id"),
        lease.getLong("Generation"),
        NodeRegistry.Kind.valueOf(lease.getString("Kind")));
  }

  public static CompoundTag write(NodeRegistry.Handle handle) {
    var data = new CompoundTag();
    data.putInt("Schema", 1);
    var lease = new CompoundTag();
    lease.putUUID("World", handle.world());
    lease.putUUID("Id", handle.id());
    lease.putLong("Generation", handle.generation());
    lease.putString("Kind", handle.kind().name());
    data.put("Lease", lease);
    return data;
  }
}
