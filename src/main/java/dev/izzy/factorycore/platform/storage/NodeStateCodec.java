package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Required schema-three structural ownership table. */
final class NodeStateCodec {
  private NodeStateCodec() {}

  static List<NodeRegistry.Stored> decode(CompoundTag root) {
    require(root, "Nodes", Tag.TAG_LIST);
    var list = root.getList("Nodes", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Nodes")).size() || list.size() > NodeRegistry.MAX_NODES)
      throw new IllegalArgumentException("Invalid structural list");
    var records = new ArrayList<NodeRegistry.Stored>(list.size());
    for (int i = 0; i < list.size(); i++) {
      var entry = list.getCompound(i);
      NetworkStateCodec.fields(
          entry, Set.of("Id", "Kind", "Generation", "Conflict", "Position", "Policy"));
      if (!entry.hasUUID("Id")) throw new IllegalArgumentException("Missing structural UUID");
      require(entry, "Kind", Tag.TAG_STRING);
      var kind = NodeRegistry.Kind.valueOf(entry.getString("Kind"));
      require(entry, "Generation", Tag.TAG_LONG);
      require(entry, "Conflict", Tag.TAG_BYTE);
      if (entry.getByte("Conflict") < 0 || entry.getByte("Conflict") > 1)
        throw new IllegalArgumentException("Invalid structural conflict flag");
      NodeRegistry.Position position = null;
      if (entry.contains("Position")) {
        require(entry, "Position", Tag.TAG_COMPOUND);
        var placed = entry.getCompound("Position");
        NetworkStateCodec.fields(placed, Set.of("Dimension", "Block"));
        require(placed, "Dimension", Tag.TAG_STRING);
        require(placed, "Block", Tag.TAG_LONG);
        position =
            new NodeRegistry.Position(placed.getString("Dimension"), placed.getLong("Block"));
      }
      GatewayPolicy policy = null;
      if (kind == NodeRegistry.Kind.GATEWAY) {
        require(entry, "Policy", Tag.TAG_COMPOUND);
        var encoded = entry.getCompound("Policy");
        NetworkStateCodec.fields(encoded, Set.of("Branch", "Inherited", "Kinds"));
        require(encoded, "Branch", Tag.TAG_INT);
        require(encoded, "Inherited", Tag.TAG_INT);
        require(encoded, "Kinds", Tag.TAG_INT);
        int mask = encoded.getInt("Kinds");
        if (mask < 0 || (mask & ~7) != 0)
          throw new IllegalArgumentException("Unknown resource-kind mask");
        var kinds = EnumSet.noneOf(ResourceKind.class);
        for (var resource : ResourceKind.values())
          if ((mask & bit(resource)) != 0) kinds.add(resource);
        policy =
            new GatewayPolicy(
                NetworkStateCodec.decodePermissions(encoded.getInt("Branch"), true),
                NetworkStateCodec.decodePermissions(encoded.getInt("Inherited"), true),
                kinds);
      } else if (entry.contains("Policy"))
        throw new IllegalArgumentException("Controller has gateway policy");
      records.add(
          new NodeRegistry.Stored(
              entry.getUUID("Id"),
              kind,
              entry.getLong("Generation"),
              position,
              entry.getBoolean("Conflict"),
              policy));
    }
    return List.copyOf(records);
  }

  static ListTag encode(List<NodeRegistry.Stored> records) {
    var list = new ListTag();
    for (var record : records) {
      var entry = new CompoundTag();
      entry.putUUID("Id", record.id());
      entry.putString("Kind", record.kind().name());
      entry.putLong("Generation", record.generation());
      entry.putBoolean("Conflict", record.conflict());
      if (record.position() != null) {
        var position = new CompoundTag();
        position.putString("Dimension", record.position().dimension());
        position.putLong("Block", record.position().block());
        entry.put("Position", position);
      }
      if (record.policy() != null) {
        var policy = new CompoundTag();
        policy.putInt(
            "Branch", NetworkStateCodec.encodePermissions(record.policy().branchPermissions()));
        policy.putInt(
            "Inherited",
            NetworkStateCodec.encodePermissions(record.policy().inheritedPermissions()));
        int mask = 0;
        for (var kind : record.policy().inheritedKinds()) mask |= bit(kind);
        policy.putInt("Kinds", mask);
        entry.put("Policy", policy);
      }
      list.add(entry);
    }
    return list;
  }

  private static int bit(ResourceKind kind) {
    return switch (kind) {
      case ITEM -> 1;
      case FLUID -> 2;
      case ENERGY -> 4;
    };
  }

  private static void require(CompoundTag tag, String field, int type) {
    if (!tag.contains(field, type))
      throw new IllegalArgumentException("Missing/wrong-type structural field: " + field);
  }
}
