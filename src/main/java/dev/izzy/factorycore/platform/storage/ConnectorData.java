package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.NodeHandleData;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Connector schema one (machine interface: two) owns policy/reference data, never a foreign
 * inventory copy. Schemas three/four add the installed {@link ThroughputTier} (ADR 0054) and are
 * written only for an upgraded port, so every base port keeps its exact earlier bytes and older
 * builds reject upgraded data into recovery instead of silently dropping the paid tier.
 */
final class ConnectorData {
  static final String FIELD = "FactoryCoreConnector";

  record State(
      NodeRegistry.Handle lease,
      ConnectorSettings settings,
      long generation,
      String fault,
      String label,
      ThroughputTier tier) {
    State(NodeRegistry.Handle lease, ConnectorSettings settings, long generation, String fault) {
      this(lease, settings, generation, fault, "");
    }

    State(
        NodeRegistry.Handle lease,
        ConnectorSettings settings,
        long generation,
        String fault,
        String label) {
      this(lease, settings, generation, fault, label, ThroughputTier.BASE);
    }

    State {
      Objects.requireNonNull(label);
      Objects.requireNonNull(tier);
      if (!settings.fits(tier))
        throw new IllegalArgumentException("Port rate exceeds its installed throughput tier");
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
        || tag.getInt("Schema") < 1
        || tag.getInt("Schema") > 4
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
    int schema = tag.getInt("Schema");
    boolean machine = schema == 2 || schema == 4;
    var lease = NodeHandleData.read(tag.get("Node"));
    if (lease == null
        || lease.kind()
            != (machine ? NodeRegistry.Kind.MACHINE_INTERFACE : NodeRegistry.Kind.CONNECTOR))
      throw new IllegalArgumentException("Wrong connector/machine lease");
    if (machine && !tag.contains("Label", Tag.TAG_STRING))
      throw new IllegalArgumentException("Missing machine label");
    var tier = ThroughputTier.BASE;
    if (schema >= 3) {
      if (!tag.contains("Throughput", Tag.TAG_STRING))
        throw new IllegalArgumentException("Missing throughput tier");
      tier = ThroughputTier.parse(tag.getString("Throughput"));
      if (tier == ThroughputTier.BASE)
        throw new IllegalArgumentException("Upgraded port schema needs a nonbase tier");
    }
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
        machine ? tag.getString("Label") : "",
        tier);
  }

  private static Set<String> fields(int schema) {
    var fields =
        new HashSet<>(
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
    if (schema == 2 || schema == 4) fields.add("Label");
    if (schema >= 3) fields.add("Throughput");
    return Set.copyOf(fields);
  }

  static CompoundTag write(State state) {
    var tag = new CompoundTag();
    boolean machine = state.lease().kind() == NodeRegistry.Kind.MACHINE_INTERFACE;
    boolean upgraded = state.tier() != ThroughputTier.BASE;
    tag.putInt("Schema", (machine ? 2 : 1) + (upgraded ? 2 : 0));
    if (machine) tag.putString("Label", state.label());
    if (upgraded) tag.putString("Throughput", state.tier().name());
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
