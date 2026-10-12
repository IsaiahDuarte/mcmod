package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Canonical nested schema-one owned slots; physical references contain no stock copies. */
final class BufferStateCodec {
  private BufferStateCodec() {}

  static CompoundTag encode(List<ItemBuffer.State> buffers) {
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
    return root;
  }

  static List<ItemBuffer.State> decode(CompoundTag root) {
    NetworkStateCodec.fields(root, Set.of("Schema", "Buffers"));
    if (!root.contains("Schema", Tag.TAG_INT)
        || root.getInt("Schema") != 1
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
      NetworkStateCodec.fields(record, Set.of("Backing", "Slots"));
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
        NetworkStateCodec.fields(entry, Set.of("Slot", "Registry", "Components", "Count", "Limit"));
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
    return List.copyOf(states);
  }
}
