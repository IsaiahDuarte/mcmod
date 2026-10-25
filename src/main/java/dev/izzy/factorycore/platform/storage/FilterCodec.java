package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Strict kind-aware {@link ResourceFilter} NBT ({@code Kind}, {@code Mode}, {@code Terms}) shared
 * by machine preset deployments and Storage-mode insertion filters. Unknown fields or term types
 * are rejected rather than broadening a selector.
 */
final class FilterCodec {
  private FilterCodec() {}

  static CompoundTag encode(ResourceFilter value) {
    var filter = new CompoundTag();
    filter.putString("Kind", value.kind().name());
    filter.putString("Mode", value.mode().name());
    var terms = new ListTag();
    for (var term : value.terms()) {
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
    return filter;
  }

  /** {@code owner} names the record kind in error messages. */
  static ResourceFilter decode(CompoundTag filter, String owner) {
    Nbt.fields(filter, Set.of("Kind", "Mode", "Terms"));
    Nbt.require(filter, "Kind", Tag.TAG_STRING, owner);
    Nbt.require(filter, "Mode", Tag.TAG_STRING, owner);
    Nbt.require(filter, "Terms", Tag.TAG_LIST, owner);
    var kind = ResourceKind.valueOf(filter.getString("Kind"));
    var terms = filter.getList("Terms", Tag.TAG_COMPOUND);
    if (terms.size() != ((ListTag) filter.get("Terms")).size()
        || terms.size() > ResourceFilter.MAX_TERMS)
      throw new IllegalArgumentException(owner + " filter count");
    var selections = new ArrayList<ResourceFilter.Term>();
    for (int j = 0; j < terms.size(); j++) {
      var term = terms.getCompound(j);
      Nbt.require(term, "Type", Tag.TAG_STRING, owner);
      Nbt.require(term, "Id", Tag.TAG_STRING, owner);
      String type = term.getString("Type");
      Nbt.fields(
          term, type.equals("EXACT") ? Set.of("Type", "Id", "Components") : Set.of("Type", "Id"));
      switch (type) {
        case "EXACT" -> {
          Nbt.require(term, "Components", Tag.TAG_BYTE_ARRAY, owner);
          selections.add(
              new ResourceFilter.Exact(
                  new ResourceKey(kind, term.getString("Id"), term.getByteArray("Components"))));
        }
        case "REGISTRY" -> selections.add(new ResourceFilter.Registry(term.getString("Id")));
        case "TAG" -> selections.add(new ResourceFilter.Tag(term.getString("Id")));
        default -> throw new IllegalArgumentException("Unknown " + owner + " selector");
      }
    }
    return new ResourceFilter(
        kind, ResourceFilter.Mode.valueOf(filter.getString("Mode")), selections);
  }
}
