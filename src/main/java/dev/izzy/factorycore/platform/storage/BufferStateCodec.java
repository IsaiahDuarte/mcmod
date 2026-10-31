package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.BufferPolicy;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Canonical owned slots and optional deployments; physical references own no stock. Table schema 2
 * adds deployments, 3 their policies (policy schema 1), 4 Export/Stock and goals (policy 2), and 5
 * (Q73) per-slot Stock goals (policy 2 or 3).
 */
final class BufferStateCodec {
  private BufferStateCodec() {}

  static CompoundTag encode(List<ItemBuffer.State> buffers) {
    return encode(buffers, List.of());
  }

  static CompoundTag encode(
      List<ItemBuffer.State> buffers, List<BufferRegistry.Deployment> deployments) {
    var root = new CompoundTag();
    root.putInt("Schema", 1);
    var records = new ListTag();
    for (var buffer : buffers) {
      var record = new CompoundTag();
      record.putUUID("Backing", buffer.backing());
      var slots = new ListTag();
      for (int index = 0; index < ItemBuffer.SLOTS; index++) {
        var slot = buffer.slots().get(index);
        if (slot.key() == null) continue;
        var entry = new CompoundTag();
        entry.putInt("Slot", index);
        entry.putString("Registry", slot.key().registryId());
        entry.putByteArray("Components", slot.key().components());
        entry.putInt("Count", slot.count());
        entry.putInt("Limit", slot.limit());
        slots.add(entry);
      }
      record.put("Slots", slots);
      records.add(record);
    }
    root.put("Buffers", records);
    if (!deployments.isEmpty()) {
      boolean policies =
          deployments.stream().anyMatch(d -> !d.policy().equals(BufferPolicy.DEFAULT));
      boolean extended =
          deployments.stream()
              .anyMatch(
                  d ->
                      d.mode() == BufferRegistry.Mode.EXPORT
                          || d.mode() == BufferRegistry.Mode.STOCK
                          || d.policy().target() != 64);
      boolean slotGoals = deployments.stream().anyMatch(d -> !d.policy().slotTargets().isEmpty());
      root.putInt("Schema", slotGoals ? 5 : extended ? 4 : policies ? 3 : 2);
      extended |= slotGoals;
      var encoded = new ListTag();
      for (var deployment : deployments) {
        var entry = new CompoundTag();
        entry.putUUID("Backing", deployment.backing());
        entry.putLong("Revision", deployment.revision());
        entry.putLong("LeaseGeneration", deployment.leaseGeneration());
        entry.putUUID("Principal", deployment.principal());
        entry.putUUID("Network", deployment.network());
        entry.putUUID("Segment", deployment.segment());
        entry.putString("Mode", deployment.mode().name());
        if (policies || extended)
          entry.put("Policy", BufferPolicyCodec.encode(deployment.policy(), extended));
        encoded.add(entry);
      }
      root.put("Deployments", encoded);
    }
    return root;
  }

  static List<ItemBuffer.State> decode(CompoundTag root) {
    Nbt.fields(
        root,
        root.getInt("Schema") >= 2
            ? Set.of("Schema", "Buffers", "Deployments")
            : Set.of("Schema", "Buffers"));
    if (!root.contains("Schema", Tag.TAG_INT)
        || (root.getInt("Schema") < 1 || root.getInt("Schema") > 5)
        || !root.contains("Buffers", Tag.TAG_LIST))
      throw new IllegalArgumentException("Unsupported buffer schema");
    var records = root.getList("Buffers", Tag.TAG_COMPOUND);
    if (records.size() != ((ListTag) root.get("Buffers")).size()
        || records.size() > BufferRegistry.MAX_BUFFERS)
      throw new IllegalArgumentException("Invalid buffer list");
    var states = new ArrayList<ItemBuffer.State>();
    long bytes = 0;
    for (int index = 0; index < records.size(); index++) {
      var record = records.getCompound(index);
      Nbt.fields(record, Set.of("Backing", "Slots"));
      if (!record.hasUUID("Backing") || !record.contains("Slots", Tag.TAG_LIST))
        throw new IllegalArgumentException("Malformed buffer owner");
      var encoded = record.getList("Slots", Tag.TAG_COMPOUND);
      if (encoded.size() != ((ListTag) record.get("Slots")).size()
          || encoded.size() > ItemBuffer.SLOTS)
        throw new IllegalArgumentException("Invalid buffer slot list");
      var slots = new ItemBuffer.Slot[ItemBuffer.SLOTS];
      Arrays.fill(slots, ItemBuffer.Slot.empty());
      for (int slotIndex = 0; slotIndex < encoded.size(); slotIndex++) {
        var entry = encoded.getCompound(slotIndex);
        Nbt.fields(entry, Set.of("Slot", "Registry", "Components", "Count", "Limit"));
        if (!entry.contains("Slot", Tag.TAG_INT)
            || !entry.contains("Registry", Tag.TAG_STRING)
            || !entry.contains("Components", Tag.TAG_BYTE_ARRAY)
            || !entry.contains("Count", Tag.TAG_INT)
            || !entry.contains("Limit", Tag.TAG_INT))
          throw new IllegalArgumentException("Malformed buffered item slot");
        int position = entry.getInt("Slot");
        if (position < 0 || position >= ItemBuffer.SLOTS || slots[position].key() != null)
          throw new IllegalArgumentException("Invalid/duplicate buffered item slot");
        slots[position] =
            new ItemBuffer.Slot(
                new ResourceKey(
                    ResourceKind.ITEM,
                    entry.getString("Registry"),
                    entry.getByteArray("Components")),
                entry.getInt("Count"),
                entry.getInt("Limit"));
      }
      var state = new ItemBuffer.State(record.getUUID("Backing"), Arrays.asList(slots));
      bytes += BufferRegistry.charge(state);
      if (bytes > BufferRegistry.MAX_BYTES)
        throw new IllegalArgumentException("Buffer metadata byte limit");
      states.add(state);
    }
    long policyBytes = deployments(root).stream().mapToLong(d -> d.policy().bytes()).sum();
    if (bytes > BufferRegistry.MAX_BYTES - policyBytes)
      throw new IllegalArgumentException("Combined buffer metadata limit");
    return List.copyOf(states);
  }

  static List<BufferRegistry.Deployment> deployments(CompoundTag root) {
    if (root.getInt("Schema") == 1) return List.of();
    if ((root.getInt("Schema") < 2 || root.getInt("Schema") > 5)
        || !root.contains("Deployments", Tag.TAG_LIST))
      throw new IllegalArgumentException("Unsupported buffer deployments");
    var entries = root.getList("Deployments", Tag.TAG_COMPOUND);
    if (entries.size() != ((ListTag) root.get("Deployments")).size()
        || entries.size() > BufferRegistry.MAX_BUFFERS)
      throw new IllegalArgumentException("Invalid deployment list");
    var result = new ArrayList<BufferRegistry.Deployment>();
    var owners = new HashSet<UUID>();
    long policyBytes = 0;
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.getCompound(index);
      Nbt.fields(
          entry,
          root.getInt("Schema") >= 3
              ? Set.of(
                  "Backing",
                  "Revision",
                  "LeaseGeneration",
                  "Principal",
                  "Network",
                  "Segment",
                  "Mode",
                  "Policy")
              : Set.of(
                  "Backing",
                  "Revision",
                  "LeaseGeneration",
                  "Principal",
                  "Network",
                  "Segment",
                  "Mode"));
      for (var field : new String[] {"Backing", "Principal", "Network", "Segment"})
        if (!entry.hasUUID(field))
          throw new IllegalArgumentException("Malformed deployment identity");
      if (!entry.contains("Revision", Tag.TAG_LONG)
          || !entry.contains("LeaseGeneration", Tag.TAG_LONG)
          || !entry.contains("Mode", Tag.TAG_STRING)
          || !owners.add(entry.getUUID("Backing")))
        throw new IllegalArgumentException("Malformed/duplicate deployment");
      var policy = BufferPolicy.DEFAULT;
      if (root.getInt("Schema") >= 3) {
        if (!entry.contains("Policy", Tag.TAG_COMPOUND))
          throw new IllegalArgumentException("Missing buffer policy");
        var encodedPolicy = entry.getCompound("Policy");
        int table = root.getInt("Schema"), version = encodedPolicy.getInt("Schema");
        if (table == 5 ? version != 2 && version != 3 : version != (table == 4 ? 2 : 1))
          throw new IllegalArgumentException("Policy/table version mismatch");
        policy = BufferPolicyCodec.decode(encodedPolicy);
      }
      policyBytes += policy.bytes();
      if (policyBytes > BufferRegistry.MAX_BYTES)
        throw new IllegalArgumentException("Buffer policy metadata limit");
      var mode = BufferRegistry.Mode.valueOf(entry.getString("Mode"));
      if (root.getInt("Schema") < 4
          && (mode == BufferRegistry.Mode.EXPORT || mode == BufferRegistry.Mode.STOCK))
        throw new IllegalArgumentException("Mode requires buffer table four");
      result.add(
          new BufferRegistry.Deployment(
              entry.getUUID("Backing"),
              entry.getLong("Revision"),
              entry.getLong("LeaseGeneration"),
              entry.getUUID("Principal"),
              entry.getUUID("Network"),
              entry.getUUID("Segment"),
              mode,
              policy));
    }
    return List.copyOf(result);
  }
}
