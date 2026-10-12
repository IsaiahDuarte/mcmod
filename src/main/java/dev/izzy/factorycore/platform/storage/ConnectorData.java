package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.NodeHandleData;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Connector schema one owns policy/reference data, never a foreign inventory copy. */
final class ConnectorData {
  static final String FIELD = "FactoryCoreConnector";

  record State(
      NodeRegistry.Handle lease, ConnectorSettings settings, long generation, String fault) {}

  private ConnectorData() {}

  static State read(Tag raw) {
    if (!(raw instanceof CompoundTag tag)
        || !tag.contains("Schema", Tag.TAG_INT)
        || tag.getInt("Schema") != 1
        || !Set.of(
                "Schema",
                "Node",
                "Kind",
                "Access",
                "Priority",
                "FirstSlot",
                "SlotCount",
                "Rate",
                "Generation",
                "Fault")
            .equals(tag.getAllKeys()))
      throw new IllegalArgumentException("Unsupported connector data; preserve for recovery");
    for (var name : new String[] {"Kind", "Access", "Fault"})
      if (!tag.contains(name, Tag.TAG_STRING))
        throw new IllegalArgumentException("Malformed connector string");
    for (var name : new String[] {"Priority", "FirstSlot", "SlotCount", "Rate"})
      if (!tag.contains(name, Tag.TAG_INT))
        throw new IllegalArgumentException("Malformed connector limit");
    if (!tag.contains("Generation", Tag.TAG_LONG)
        || tag.getLong("Generation") <= 0
        || tag.getString("Fault").length() > 512)
      throw new IllegalArgumentException("Malformed connector generation/fault");
    var lease = NodeHandleData.read(tag.get("Node"));
    if (lease == null || lease.kind() != NodeRegistry.Kind.CONNECTOR)
      throw new IllegalArgumentException("Wrong connector lease");
    return new State(
        lease,
        new ConnectorSettings(
            ResourceKind.valueOf(tag.getString("Kind")),
            ConnectorSettings.Access.valueOf(tag.getString("Access")),
            tag.getInt("Priority"),
            tag.getInt("FirstSlot"),
            tag.getInt("SlotCount"),
            tag.getInt("Rate")),
        tag.getLong("Generation"),
        tag.getString("Fault"));
  }

  static CompoundTag write(State state) {
    var tag = new CompoundTag();
    tag.putInt("Schema", 1);
    tag.put("Node", NodeHandleData.write(state.lease()));
    tag.putString("Kind", state.settings().kind().name());
    tag.putString("Access", state.settings().access().name());
    tag.putInt("Priority", state.settings().priority());
    tag.putInt("FirstSlot", state.settings().firstSlot());
    tag.putInt("SlotCount", state.settings().slotCount());
    tag.putInt("Rate", state.settings().rate());
    tag.putLong("Generation", state.generation());
    tag.putString("Fault", state.fault());
    return tag;
  }
}
