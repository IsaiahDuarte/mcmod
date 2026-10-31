package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.BufferPolicy;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.nbt.*;

/**
 * Nested policy; rejects unknown fields/types instead of silently broadening selectors. Schema 1
 * has no goal (64), 2 adds "Target", 3 (Q73) adds "Slots", one goal per filter term (−1: the goal).
 */
final class BufferPolicyCodec {
  private BufferPolicyCodec() {}

  static CompoundTag encode(BufferPolicy policy) {
    return encode(policy, policy.target() != 64);
  }

  static CompoundTag encode(BufferPolicy policy, boolean extended) {
    var tag = new CompoundTag();
    boolean slots = !policy.slotTargets().isEmpty();
    tag.putInt("Schema", slots ? 3 : extended ? 2 : 1);
    if (extended || slots) tag.putInt("Target", policy.target());
    if (slots)
      tag.putIntArray("Slots", policy.slotTargets().stream().mapToInt(Integer::intValue).toArray());
    tag.putInt("Rate", policy.rate());
    tag.putString("Mode", policy.filter().mode().name());
    var terms = new ListTag();
    for (var term : policy.filter().terms()) {
      var entry = new CompoundTag();
      if (term instanceof ResourceFilter.Exact exact) {
        entry.putString("Type", "EXACT");
        entry.putString("Id", exact.key().registryId());
        entry.putByteArray("Components", exact.key().components());
      } else if (term instanceof ResourceFilter.Registry registry) {
        entry.putString("Type", "REGISTRY");
        entry.putString("Id", registry.id());
      } else if (term instanceof ResourceFilter.Tag tagTerm) {
        entry.putString("Type", "TAG");
        entry.putString("Id", tagTerm.id());
      } else throw new IllegalArgumentException("Program-only filter terms are never saved");
      terms.add(entry);
    }
    tag.put("Terms", terms);
    return tag;
  }

  static BufferPolicy decode(CompoundTag tag) {
    int schema = tag.getInt("Schema");
    Nbt.fields(
        tag,
        switch (schema) {
          case 3 -> Set.of("Schema", "Rate", "Mode", "Terms", "Target", "Slots");
          case 2 -> Set.of("Schema", "Rate", "Mode", "Terms", "Target");
          default -> Set.of("Schema", "Rate", "Mode", "Terms");
        });
    if (!tag.contains("Schema", Tag.TAG_INT)
        || schema < 1
        || schema > 3
        || !tag.contains("Rate", Tag.TAG_INT)
        || !tag.contains("Mode", Tag.TAG_STRING)
        || !tag.contains("Terms", Tag.TAG_LIST))
      throw new IllegalArgumentException("Unsupported buffer policy");
    if (schema >= 2 && !tag.contains("Target", Tag.TAG_INT))
      throw new IllegalArgumentException("Missing stock target");
    if (schema == 3 && !tag.contains("Slots", Tag.TAG_INT_ARRAY))
      throw new IllegalArgumentException("Missing slot goals");
    var terms = tag.getList("Terms", Tag.TAG_COMPOUND);
    if (terms.size() != ((ListTag) tag.get("Terms")).size()
        || terms.size() > ResourceFilter.MAX_TERMS)
      throw new IllegalArgumentException("Invalid filter terms");
    var result = new ArrayList<ResourceFilter.Term>();
    for (int i = 0; i < terms.size(); i++) {
      var entry = terms.getCompound(i);
      String type = entry.getString("Type");
      Nbt.fields(
          entry, type.equals("EXACT") ? Set.of("Type", "Id", "Components") : Set.of("Type", "Id"));
      if (!entry.contains("Type", Tag.TAG_STRING) || !entry.contains("Id", Tag.TAG_STRING))
        throw new IllegalArgumentException("Malformed filter term");
      switch (type) {
        case "EXACT" -> {
          if (!entry.contains("Components", Tag.TAG_BYTE_ARRAY))
            throw new IllegalArgumentException("Missing exact components");
          result.add(
              new ResourceFilter.Exact(
                  new ResourceKey(
                      ResourceKind.ITEM, entry.getString("Id"), entry.getByteArray("Components"))));
        }
        case "REGISTRY" -> result.add(new ResourceFilter.Registry(entry.getString("Id")));
        case "TAG" -> result.add(new ResourceFilter.Tag(entry.getString("Id")));
        default -> throw new IllegalArgumentException("Unknown filter term type");
      }
    }
    return new BufferPolicy(
        new ResourceFilter(
            ResourceKind.ITEM, ResourceFilter.Mode.valueOf(tag.getString("Mode")), result),
        tag.getInt("Rate"),
        schema >= 2 ? tag.getInt("Target") : 64,
        schema == 3
            ? java.util.Arrays.stream(tag.getIntArray("Slots")).boxed().toList()
            : java.util.List.of());
  }
}
