package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.MachinePresets;
import java.util.*;
import net.minecraft.nbt.*;

/** Strict canonical deployment schema1. No executable authority or resources are encoded. */
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

  static List<MachinePresets.Deployment> decode(CompoundTag root) {
    NetworkStateCodec.fields(root, Set.of("Schema", "Deployments"));
    require(root, "Schema", Tag.TAG_INT);
    require(root, "Deployments", Tag.TAG_LIST);
    if (root.getInt("Schema") != 1)
      throw new IllegalArgumentException("Unknown machine deployment schema");
    var list = root.getList("Deployments", Tag.TAG_COMPOUND);
    if (list.size() != ((ListTag) root.get("Deployments")).size()
        || list.size() > MachinePresets.MAX_DEPLOYMENTS)
      throw new IllegalArgumentException("Machine deployment count");
    var records = new ArrayList<MachinePresets.Deployment>();
    long bytes = 0;
    for (int i = 0; i < list.size(); i++) {
      var tag = list.getCompound(i);
      NetworkStateCodec.fields(
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
        require(tag, name, Tag.TAG_LONG);
      require(tag, "Mode", Tag.TAG_STRING);
      require(tag, "Face", Tag.TAG_STRING);
      require(tag, "Filter", Tag.TAG_COMPOUND);
      var filter = tag.getCompound("Filter");
      NetworkStateCodec.fields(filter, Set.of("Kind", "Mode", "Terms"));
      require(filter, "Kind", Tag.TAG_STRING);
      require(filter, "Mode", Tag.TAG_STRING);
      require(filter, "Terms", Tag.TAG_LIST);
      var kind = ResourceKind.valueOf(filter.getString("Kind"));
      var terms = filter.getList("Terms", Tag.TAG_COMPOUND);
      if (terms.size() != ((ListTag) filter.get("Terms")).size()
          || terms.size() > ResourceFilter.MAX_TERMS)
        throw new IllegalArgumentException("Machine filter count");
      var selections = new ArrayList<ResourceFilter.Term>();
      for (int j = 0; j < terms.size(); j++) {
        var term = terms.getCompound(j);
        require(term, "Type", Tag.TAG_STRING);
        require(term, "Id", Tag.TAG_STRING);
        String type = term.getString("Type");
        NetworkStateCodec.fields(
            term, type.equals("EXACT") ? Set.of("Type", "Id", "Components") : Set.of("Type", "Id"));
        switch (type) {
          case "EXACT" -> {
            require(term, "Components", Tag.TAG_BYTE_ARRAY);
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

  private static void require(CompoundTag tag, String name, int type) {
    if (!tag.contains(name, type))
      throw new IllegalArgumentException("Missing/wrong-type machine field: " + name);
  }
}
