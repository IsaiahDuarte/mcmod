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
      NodeRegistry.Handle lease,
      ConnectorSettings settings,
      long generation,
      String fault,
      String label) {
    State(NodeRegistry.Handle lease, ConnectorSettings settings, long generation, String fault) {
      this(lease, settings, generation, fault, "");
    }

    State {
      java.util.Objects.requireNonNull(label);
      if (label.length() > 64
          || label.chars().anyMatch(Character::isISOControl)
          || lease.kind() == NodeRegistry.Kind.CONNECTOR && !label.isEmpty())
        throw new IllegalArgumentException("Invalid local machine label");
    }
  }

  private ConnectorData() {}

  static State read(Tag raw) {
    if (!(raw instanceof CompoundTag tag)
        || !tag.contains("Schema", Tag.TAG_INT)
        || (tag.getInt("Schema") != 1 && tag.getInt("Schema") != 2)
        || !fields(tag.getInt("Schema")).equals(tag.getAllKeys()))
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
    if (lease == null
        || lease.kind()
            != (tag.getInt("Schema") == 1
                ? NodeRegistry.Kind.CONNECTOR
                : NodeRegistry.Kind.MACHINE_INTERFACE))
      throw new IllegalArgumentException("Wrong connector/machine lease");
    if (tag.getInt("Schema") == 2 && !tag.contains("Label", Tag.TAG_STRING))
      throw new IllegalArgumentException("Missing machine label");
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
        tag.getString("Fault"),
        tag.getInt("Schema") == 2 ? tag.getString("Label") : "");
  }

  private static Set<String> fields(int schema) {
    var fields =
        new java.util.HashSet<>(
            Set.of(
                "Schema",
                "Node",
                "Kind",
                "Access",
                "Priority",
                "FirstSlot",
                "SlotCount",
                "Rate",
                "Generation",
                "Fault"));
    if (schema == 2) fields.add("Label");
    return Set.copyOf(fields);
  }

  static CompoundTag write(State state) {
    var tag = new CompoundTag();
    tag.putInt("Schema", state.lease().kind() == NodeRegistry.Kind.MACHINE_INTERFACE ? 2 : 1);
    if (state.lease().kind() == NodeRegistry.Kind.MACHINE_INTERFACE)
      tag.putString("Label", state.label());
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
