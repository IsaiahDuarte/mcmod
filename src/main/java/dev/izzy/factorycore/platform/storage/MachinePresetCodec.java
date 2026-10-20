package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.MachinePresets;
import java.util.*;
import net.minecraft.nbt.*;

/** Strict deployment/fence schemas1–2. No executable authority or resources are encoded. */
final class MachinePresetCodec {
  private MachinePresetCodec() {}

  static CompoundTag encode(List<MachinePresets.Deployment> records) {
    var root = new CompoundTag();
    root.putInt("Schema", 1);
    var list = new ListTag();
    for (var value : records) {
      var tag = new CompoundTag();
      tag.putUUID("Node", value.node());
      tag.putLong("Lease", value.leaseGeneration());
      tag.putLong("Revision", value.revision());
      tag.putLong("Configuration", value.configuration());
      tag.putString("Face", value.face().name());
      tag.putUUID("Principal", value.authority().principal());
      tag.putUUID("Network", value.authority().network());
      tag.putUUID("Segment", value.authority().segment());
      tag.putLong("Permissions", value.authority().permissionGeneration());
      tag.putString("Mode", value.mode().name());
      tag.putLong("Target", value.target());
      var filter = new CompoundTag();
      filter.putString("Kind", value.filter().kind().name());
      filter.putString("Mode", value.filter().mode().name());
      var terms = new ListTag();
      for (var term : value.filter().terms()) {
        var entry = new CompoundTag();
        if (term instanceof ResourceFilter.Exact exact) {
          entry.putString("Type", "EXACT");
          entry.putString("Id", exact.key().registryId());
          entry.putByteArray("Components", exact.key().components());
        } else if (term instanceof ResourceFilter.Registry registry) {
          entry.putString("Type", "REGISTRY");
          entry.putString("Id", registry.id());
        } else {
          entry.putString("Type", "TAG");
          entry.putString("Id", ((ResourceFilter.Tag) term).id());
        }
        terms.add(entry);
      }
      filter.put("Terms", terms);
      tag.put("Filter", filter);
      list.add(tag);
    }
    root.put("Deployments", list);
    return root;
  }

  static CompoundTag encode(
      List<MachinePresets.Deployment> records, Map<UUID, Long> configurations) {
    var root = encode(records);
    root.putInt("Schema", 2);
    var list = new ListTag();
    for (var value : new TreeMap<>(configurations).entrySet()) {
      var tag = new CompoundTag();
      tag.putUUID("Node", value.getKey());
      tag.putLong("Configuration", value.getValue());
      list.add(tag);
    }
    root.put("Configurations", list);
    return root;
  }

  /**
   * Null identifies schema1, whose fences are derived from records and accepted transfer contexts.
   */
  static Map<UUID, Long> configurations(CompoundTag root) {
    if (root.getInt("Schema") == 1) return null;
    Nbt.require(root, "Configurations", Tag.TAG_LIST, "machine");
    var list = root.getList("Configurations", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Configurations")).size()
        || list.size() > NodeRegistry.MAX_NODES)
      throw new IllegalArgumentException("Invalid machine fence count");
    var result = new HashMap<UUID, Long>();
    for (int i = 0; i < list.size(); i++) {
      var value = list.getCompound(i);
      Nbt.fields(value, Set.of("Node", "Configuration"));
      Nbt.require(value, "Configuration", Tag.TAG_LONG, "machine");
      if (!value.hasUUID("Node")
          || value.getLong("Configuration") <= 0
          || result.putIfAbsent(value.getUUID("Node"), value.getLong("Configuration")) != null)
        throw new IllegalArgumentException("Invalid/duplicate machine fence");
    }
    return Map.copyOf(result);
  }

  static List<MachinePresets.Deployment> decode(CompoundTag root) {
    Nbt.fields(
        root,
        root.getInt("Schema") == 2
            ? Set.of("Schema", "Deployments", "Configurations")
            : Set.of("Schema", "Deployments"));
    Nbt.require(root, "Schema", Tag.TAG_INT, "machine");
    Nbt.require(root, "Deployments", Tag.TAG_LIST, "machine");
    if (root.getInt("Schema") != 1 && root.getInt("Schema") != 2)
      throw new IllegalArgumentException("Unknown machine deployment schema");
    var list = root.getList("Deployments", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Deployments")).size()
        || list.size() > MachinePresets.MAX_DEPLOYMENTS)
      throw new IllegalArgumentException("Machine deployment count");
    var records = new ArrayList<MachinePresets.Deployment>();
    long bytes = 0;
    for (int i = 0; i < list.size(); i++) {
      var tag = list.getCompound(i);
      Nbt.fields(
          tag,
          Set.of(
              "Node",
              "Lease",
              "Revision",
              "Configuration",
              "Face",
              "Principal",
              "Network",
              "Segment",
              "Permissions",
              "Mode",
              "Target",
              "Filter"));
      for (String name : List.of("Node", "Principal", "Network", "Segment"))
        if (!tag.hasUUID(name)) throw new IllegalArgumentException("Machine deployment UUID");
      for (String name : List.of("Lease", "Revision", "Configuration", "Permissions", "Target"))
        Nbt.require(tag, name, Tag.TAG_LONG, "machine");
      Nbt.require(tag, "Mode", Tag.TAG_STRING, "machine");
      Nbt.require(tag, "Face", Tag.TAG_STRING, "machine");
      Nbt.require(tag, "Filter", Tag.TAG_COMPOUND, "machine");
      var filter = tag.getCompound("Filter");
      Nbt.fields(filter, Set.of("Kind", "Mode", "Terms"));
      Nbt.require(filter, "Kind", Tag.TAG_STRING, "machine");
      Nbt.require(filter, "Mode", Tag.TAG_STRING, "machine");
      Nbt.require(filter, "Terms", Tag.TAG_LIST, "machine");
      var kind = ResourceKind.valueOf(filter.getString("Kind"));
      var terms = filter.getList("Terms", Tag.TAG_COMPOUND);
      if (terms.size() != ((ListTag) filter.get("Terms")).size()
          || terms.size() > ResourceFilter.MAX_TERMS)
        throw new IllegalArgumentException("Machine filter count");
      var selections = new ArrayList<ResourceFilter.Term>();
      for (int j = 0; j < terms.size(); j++) {
        var term = terms.getCompound(j);
        Nbt.require(term, "Type", Tag.TAG_STRING, "machine");
        Nbt.require(term, "Id", Tag.TAG_STRING, "machine");
        String type = term.getString("Type");
        Nbt.fields(
            term, type.equals("EXACT") ? Set.of("Type", "Id", "Components") : Set.of("Type", "Id"));
        switch (type) {
          case "EXACT" -> {
            Nbt.require(term, "Components", Tag.TAG_BYTE_ARRAY, "machine");
            selections.add(
                new ResourceFilter.Exact(
                    new ResourceKey(kind, term.getString("Id"), term.getByteArray("Components"))));
          }
          case "REGISTRY" -> selections.add(new ResourceFilter.Registry(term.getString("Id")));
          case "TAG" -> selections.add(new ResourceFilter.Tag(term.getString("Id")));
          default -> throw new IllegalArgumentException("Unknown machine selector");
        }
      }
      var value =
          new MachinePresets.Deployment(
              tag.getUUID("Node"),
              tag.getLong("Lease"),
              tag.getLong("Revision"),
              tag.getLong("Configuration"),
              MachinePresets.Face.valueOf(tag.getString("Face")),
              new MachinePresets.Authority(
                  tag.getUUID("Principal"),
                  tag.getUUID("Network"),
                  tag.getUUID("Segment"),
                  tag.getLong("Permissions")),
              MachinePresets.Mode.valueOf(tag.getString("Mode")),
              new ResourceFilter(
                  kind, ResourceFilter.Mode.valueOf(filter.getString("Mode")), selections),
              tag.getLong("Target"));
      bytes = Math.addExact(bytes, value.bytes());
      if (bytes > MachinePresets.MAX_BYTES)
        throw new IllegalArgumentException("Machine deployment metadata limit");
      records.add(value);
    }
    return List.copyOf(records);
  }
}
