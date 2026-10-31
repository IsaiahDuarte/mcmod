package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * B1 ghost-slot filter editing shared by the Buffer, Machine preset and Storage screens. The
 * screens show each term as a slot with an icon; a click with an item adds it as an Item term and a
 * click on a slot cycles how it matches: Item (registry ID) → Exact (components included) → for a
 * damageable item, Worn below 99/75/50/25% durability (Q76) → each of the item's tags → Item. All
 * edits still build an ordinary bounded {@link ResourceFilter}.
 */
public final class FilterEdits {
  public static final int ITEM = 0, EXACT = 1, TAG = 2, WORN = 3;

  /** Tags offered per item when cycling; more are skipped. */
  public static final int MAX_TAGS = 32;

  /** One filter slot for a screen: match kind, the shown resource ID and a readable label. */
  public record Rule(int type, String icon, String label) {
    public Rule {
      if (type < ITEM || type > WORN || icon.length() > 256 || label.length() > 256)
        throw new IllegalArgumentException("Invalid filter rule");
    }

    public static void write(RegistryFriendlyByteBuf buffer, Rule rule) {
      buffer.writeVarInt(rule.type);
      buffer.writeUtf(rule.icon, 256);
      buffer.writeUtf(rule.label, 256);
    }

    public static Rule read(RegistryFriendlyByteBuf buffer) {
      return new Rule(buffer.readVarInt(), buffer.readUtf(256), buffer.readUtf(256));
    }
  }

  private FilterEdits() {}

  /** Slot rows for {@code filter}; a tag slot shows its first member as the icon. */
  public static List<Rule> rules(ResourceFilter filter, PlatformResourceCodec codec) {
    var rules = new ArrayList<Rule>(filter.terms().size());
    for (var term : filter.terms()) {
      if (ResourceFilter.programOnly(term))
        throw new IllegalArgumentException("Program-only filter terms have no slot rows");
      Rule rule;
      if (term instanceof ResourceFilter.Registry registry)
        rule = new Rule(ITEM, registry.id(), "ID " + registry.id());
      else if (term instanceof ResourceFilter.Tag tag)
        rule = new Rule(TAG, firstMember(filter.kind(), tag.id()), "#" + tag.id());
      else if (term instanceof ResourceFilter.Worn worn)
        rule =
            new Rule(
                WORN, worn.id(), "ID " + worn.id() + " · durability below " + worn.below() + "%");
      else {
        var key = ((ResourceFilter.Exact) term).key();
        rule =
            new Rule(EXACT, key.registryId(), "= " + key.registryId() + " · " + name(codec, key));
      }
      rules.add(clean(rule));
    }
    return rules;
  }

  private static Rule clean(Rule rule) {
    String label = rule.label().replaceAll("[\\p{Cntrl}§]", "");
    return new Rule(
        rule.type(), rule.icon(), label.length() > 256 ? label.substring(0, 256) : label);
  }

  private static String name(PlatformResourceCodec codec, ResourceKey key) {
    try {
      return switch (key.kind()) {
        case ITEM -> codec.itemStack(key, 1).getHoverName().getString();
        case FLUID -> codec.fluidStack(key, 1).getHoverName().getString();
        case ENERGY -> "Energy";
      };
    } catch (IllegalArgumentException unsupported) {
      return key.registryId();
    }
  }

  private static String firstMember(ResourceKind kind, String tag) {
    var location = ResourceLocation.tryParse(tag);
    if (location == null) return "";
    if (kind == ResourceKind.FLUID)
      return BuiltInRegistries.FLUID
          .getTag(TagKey.create(Registries.FLUID, location))
          .flatMap(set -> set.stream().findFirst())
          .map(holder -> BuiltInRegistries.FLUID.getKey(holder.value()).toString())
          .orElse("");
    return BuiltInRegistries.ITEM
        .getTag(TagKey.create(Registries.ITEM, location))
        .flatMap(set -> set.stream().findFirst())
        .map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()).toString())
        .orElse("");
  }

  /**
   * The Item term for the cursor stack: its item ID, or for a fluid filter the contained fluid's
   * ID. Null when there is nothing usable (empty cursor, energy, or no fluid inside).
   */
  public static ResourceFilter.Term carried(ResourceKind kind, ItemStack stack) {
    if (stack.isEmpty() || kind == ResourceKind.ENERGY) return null;
    if (kind == ResourceKind.ITEM)
      return new ResourceFilter.Registry(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    var contents = FluidUtil.getFluidContained(stack.copyWithCount(1));
    if (contents.isEmpty() || contents.get().getFluid() == Fluids.EMPTY) return null;
    return new ResourceFilter.Registry(
        BuiltInRegistries.FLUID.getKey(contents.get().getFluid()).toString());
  }

  /**
   * The next match for {@code term}, anchored on {@code shown}, the resource ID the screen shows
   * for that slot: Item → Exact (that resource with default components) → its tags in order → Item.
   * Returns null if {@code shown} does not belong to the term (a stale screen), so nothing is
   * changed.
   */
  public static ResourceFilter.Term cycle(
      ResourceKind kind, ResourceFilter.Term term, String shown, PlatformResourceCodec codec) {
    var location = ResourceLocation.tryParse(shown);
    if (location == null || kind == ResourceKind.ENERGY) return null;
    boolean known =
        kind == ResourceKind.ITEM
            ? BuiltInRegistries.ITEM.containsKey(location)
            : BuiltInRegistries.FLUID.containsKey(location);
    if (!known) return null;
    var tags = tags(kind, location);
    if (term instanceof ResourceFilter.Registry registry) {
      if (!registry.id().equals(shown)) return null;
      return new ResourceFilter.Exact(defaultKey(kind, location, codec));
    }
    var afterWorn =
        tags.isEmpty() ? new ResourceFilter.Registry(shown) : new ResourceFilter.Tag(tags.get(0));
    if (term instanceof ResourceFilter.Exact exact) {
      if (!exact.key().registryId().equals(shown)) return null;
      return damageable(kind, location)
          ? new ResourceFilter.Worn(shown, ResourceFilter.Worn.STEPS.getFirst())
          : afterWorn;
    }
    if (term instanceof ResourceFilter.Worn worn) {
      int step = ResourceFilter.Worn.STEPS.indexOf(worn.below());
      if (!worn.id().equals(shown)) return null;
      return step >= 0 && step + 1 < ResourceFilter.Worn.STEPS.size()
          ? new ResourceFilter.Worn(shown, ResourceFilter.Worn.STEPS.get(step + 1))
          : afterWorn;
    }
    if (!(term instanceof ResourceFilter.Tag tagTerm)) return null;
    var tag = tagTerm.id();
    int index = tags.indexOf(tag);
    if (index < 0) return null;
    return index + 1 < tags.size()
        ? new ResourceFilter.Tag(tags.get(index + 1))
        : new ResourceFilter.Registry(shown);
  }

  private static boolean damageable(ResourceKind kind, ResourceLocation location) {
    return kind == ResourceKind.ITEM
        && new ItemStack(BuiltInRegistries.ITEM.get(location)).isDamageableItem();
  }

  private static List<String> tags(ResourceKind kind, ResourceLocation location) {
    var stream =
        kind == ResourceKind.ITEM
            ? BuiltInRegistries.ITEM.wrapAsHolder(BuiltInRegistries.ITEM.get(location)).tags()
            : BuiltInRegistries.FLUID.wrapAsHolder(BuiltInRegistries.FLUID.get(location)).tags();
    return stream.map(tag -> tag.location().toString()).sorted().limit(MAX_TAGS).toList();
  }

  private static ResourceKey defaultKey(
      ResourceKind kind, ResourceLocation location, PlatformResourceCodec codec) {
    return kind == ResourceKind.ITEM
        ? codec.itemKey(new ItemStack(BuiltInRegistries.ITEM.get(location)))
        : codec.fluidKey(
            new net.neoforged.neoforge.fluids.FluidStack(BuiltInRegistries.FLUID.get(location), 1));
  }
}
