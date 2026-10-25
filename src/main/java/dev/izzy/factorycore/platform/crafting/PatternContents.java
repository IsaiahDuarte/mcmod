package dev.izzy.factorycore.platform.crafting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.izzy.factorycore.core.crafting.Pattern;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * Encoded crafting-table pattern carried by a pattern item. Cells are the exact shaped placement
 * (count-one stacks, empty allowed); output and remainders were computed by the server recipe at
 * encode time. Values are immutable copies with value equality.
 */
public final class PatternContents {
  public static final int CELLS = 9;

  private final UUID id;
  private final long version;
  private final int priority;
  private final List<ItemStack> cells;
  private final ItemStack output;
  private final List<ItemStack> remainders;

  /**
   * E1 (PD4): per cell, the item IDs that may stand in for it when the player allowed substitutes
   * at encode time (from the recipe's ingredient); all empty for an exact pattern.
   */
  private final List<List<String>> alternatives;

  public PatternContents(
      UUID id,
      long version,
      int priority,
      List<ItemStack> cells,
      ItemStack output,
      List<ItemStack> remainders) {
    this(id, version, priority, cells, output, remainders, NONE);
  }

  private static final List<List<String>> NONE = java.util.Collections.nCopies(CELLS, List.of());

  public PatternContents(
      UUID id,
      long version,
      int priority,
      List<ItemStack> cells,
      ItemStack output,
      List<ItemStack> remainders,
      List<List<String>> alternatives) {
    this.id = Objects.requireNonNull(id);
    if (alternatives.size() != CELLS)
      throw new IllegalArgumentException("Pattern needs nine alternative lists");
    var lists = new ArrayList<List<String>>(CELLS);
    for (int index = 0; index < CELLS; index++) {
      var list = List.copyOf(alternatives.get(index));
      if (list.size() > Pattern.MAX_ALTERNATIVES || !list.isEmpty() && cells.get(index).isEmpty())
        throw new IllegalArgumentException("Invalid substitutes for a pattern cell");
      for (var item : list)
        new ResourceKey(dev.izzy.factorycore.core.resource.ResourceKind.ITEM, item, new byte[0]);
      lists.add(list);
    }
    this.alternatives = List.copyOf(lists);
    if (version < 1) throw new IllegalArgumentException("Pattern version must be positive");
    if (priority < Pattern.MIN_PRIORITY || priority > Pattern.MAX_PRIORITY)
      throw new IllegalArgumentException("Pattern priority out of range");
    if (cells.size() != CELLS) throw new IllegalArgumentException("Pattern needs nine cells");
    if (output.isEmpty()) throw new IllegalArgumentException("Pattern needs an output");
    if (remainders.size() > Pattern.MAX_BYPRODUCT_KEYS)
      throw new IllegalArgumentException("Too many remainders");
    var copied = new ArrayList<ItemStack>(CELLS);
    boolean any = false;
    for (var cell : cells) {
      if (!cell.isEmpty() && cell.getCount() != 1)
        throw new IllegalArgumentException("Pattern cells hold one unit");
      any |= !cell.isEmpty();
      copied.add(cell.copy());
    }
    if (!any) throw new IllegalArgumentException("Pattern needs an input");
    var rest = new ArrayList<ItemStack>();
    for (var remainder : remainders) {
      if (remainder.isEmpty()) throw new IllegalArgumentException("Empty remainder");
      rest.add(remainder.copy());
    }
    this.version = version;
    this.priority = priority;
    this.cells = List.copyOf(copied);
    this.output = output.copy();
    this.remainders = List.copyOf(rest);
  }

  public UUID id() {
    return id;
  }

  public long version() {
    return version;
  }

  public int priority() {
    return priority;
  }

  /** Defensive copies; the stored stacks are never exposed for mutation. */
  public List<ItemStack> cells() {
    return cells.stream().map(ItemStack::copy).toList();
  }

  public ItemStack output() {
    return output.copy();
  }

  public List<ItemStack> remainders() {
    return remainders.stream().map(ItemStack::copy).toList();
  }

  /** Per-cell alternative item IDs (E1); every list empty for an exact pattern. */
  public List<List<String>> alternatives() {
    return alternatives;
  }

  public boolean substitutes() {
    return alternatives.stream().anyMatch(list -> !list.isEmpty());
  }

  /** Exact portable contract; throws IllegalArgumentException for unrepresentable stacks. */
  public Pattern pattern(PlatformResourceCodec codec) {
    var inputs = new LinkedHashMap<ResourceKey, Long>();
    for (var cell : cells) if (!cell.isEmpty()) inputs.merge(codec.itemKey(cell), 1L, Long::sum);
    var byproducts = new LinkedHashMap<ResourceKey, Long>();
    for (var remainder : remainders)
      byproducts.merge(codec.itemKey(remainder), (long) remainder.getCount(), Long::sum);
    var outputKey = codec.itemKey(output);
    return new Pattern(
        id,
        version,
        Pattern.Type.CRAFTING,
        priority,
        inputs,
        outputKey,
        output.getCount(),
        byproducts,
        substitutes(codec, inputs.keySet(), outputKey));
  }

  /**
   * E1: each input key's alternatives. Cells sharing an input keep only alternatives every one of
   * them allows; an alternative that is the output, an input or already another input's is dropped
   * (first input in cell order keeps it), so every concrete key belongs to one input.
   */
  private Map<ResourceKey, List<ResourceKey>> substitutes(
      PlatformResourceCodec codec, java.util.Set<ResourceKey> inputs, ResourceKey output) {
    var merged = new LinkedHashMap<ResourceKey, java.util.LinkedHashSet<ResourceKey>>();
    var exact = new java.util.HashSet<ResourceKey>();
    for (int index = 0; index < CELLS; index++) {
      var cell = cells.get(index);
      if (cell.isEmpty()) continue;
      var key = codec.itemKey(cell);
      var allowed = new java.util.LinkedHashSet<ResourceKey>();
      for (var id : alternatives.get(index)) {
        var item =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
                net.minecraft.resources.ResourceLocation.parse(id));
        if (item.isPresent()) allowed.add(codec.itemKey(new ItemStack(item.get())));
      }
      allowed.remove(key);
      if (allowed.isEmpty()) exact.add(key);
      else if (merged.containsKey(key)) merged.get(key).retainAll(allowed);
      else merged.put(key, allowed);
    }
    var claimed = new java.util.HashSet<ResourceKey>(inputs);
    claimed.add(output);
    var result = new LinkedHashMap<ResourceKey, List<ResourceKey>>();
    for (var entry : merged.entrySet()) {
      // A cell of this input that allows nothing makes the whole input exact.
      if (exact.contains(entry.getKey())) continue;
      var kept = new ArrayList<ResourceKey>();
      for (var alternative : entry.getValue()) if (claimed.add(alternative)) kept.add(alternative);
      if (!kept.isEmpty())
        result.put(
            entry.getKey(), kept.subList(0, Math.min(kept.size(), Pattern.MAX_ALTERNATIVES)));
    }
    return result;
  }

  private static boolean same(List<ItemStack> left, List<ItemStack> right) {
    if (left.size() != right.size()) return false;
    for (int index = 0; index < left.size(); index++)
      if (!ItemStack.matches(left.get(index), right.get(index))) return false;
    return true;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PatternContents that
        && id.equals(that.id)
        && version == that.version
        && priority == that.priority
        && ItemStack.matches(output, that.output)
        && same(cells, that.cells)
        && same(remainders, that.remainders)
        && alternatives.equals(that.alternatives);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, version, priority, ItemStack.hashItemAndComponents(output));
  }

  private static <T> DataResult<T> guarded(Supplier<T> build) {
    try {
      return DataResult.success(build.get());
    } catch (IllegalArgumentException invalid) {
      return DataResult.error(invalid::getMessage);
    }
  }

  private record Raw(
      UUID id,
      long version,
      int priority,
      List<ItemStack> cells,
      ItemStack output,
      List<ItemStack> remainders,
      List<List<String>> alternatives) {}

  public static final Codec<PatternContents> CODEC =
      RecordCodecBuilder.<Raw>create(
              instance ->
                  instance
                      .group(
                          UUIDUtil.CODEC.fieldOf("id").forGetter(Raw::id),
                          Codec.LONG.fieldOf("version").forGetter(Raw::version),
                          Codec.INT.fieldOf("priority").forGetter(Raw::priority),
                          ItemStack.OPTIONAL_CODEC.listOf().fieldOf("cells").forGetter(Raw::cells),
                          ItemStack.CODEC.fieldOf("output").forGetter(Raw::output),
                          ItemStack.CODEC.listOf().fieldOf("remainders").forGetter(Raw::remainders),
                          // E1: absent on exact (and every older) pattern.
                          Codec.STRING
                              .listOf()
                              .listOf()
                              .optionalFieldOf("substitutes", NONE)
                              .forGetter(Raw::alternatives))
                      .apply(instance, Raw::new))
          .comapFlatMap(
              raw ->
                  guarded(
                      () ->
                          new PatternContents(
                              raw.id(),
                              raw.version(),
                              raw.priority(),
                              raw.cells(),
                              raw.output(),
                              raw.remainders(),
                              raw.alternatives())),
              value ->
                  new Raw(
                      value.id,
                      value.version,
                      value.priority,
                      value.cells,
                      value.output,
                      value.remainders,
                      value.alternatives));

  public static final StreamCodec<RegistryFriendlyByteBuf, PatternContents> STREAM_CODEC =
      StreamCodec.of(
          (buffer, value) -> {
            UUIDUtil.STREAM_CODEC.encode(buffer, value.id);
            buffer.writeVarLong(value.version);
            buffer.writeVarInt(value.priority);
            for (var cell : value.cells) ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, cell);
            ItemStack.STREAM_CODEC.encode(buffer, value.output);
            ByteBufCodecs.VAR_INT.encode(buffer, value.remainders.size());
            for (var remainder : value.remainders) ItemStack.STREAM_CODEC.encode(buffer, remainder);
            for (var list : value.alternatives) {
              ByteBufCodecs.VAR_INT.encode(buffer, list.size());
              for (var item : list) buffer.writeUtf(item, 256);
            }
          },
          buffer -> {
            var id = UUIDUtil.STREAM_CODEC.decode(buffer);
            long version = buffer.readVarLong();
            int priority = buffer.readVarInt();
            var cells = new ArrayList<ItemStack>(CELLS);
            for (int index = 0; index < CELLS; index++)
              cells.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            var output = ItemStack.STREAM_CODEC.decode(buffer);
            int count = ByteBufCodecs.VAR_INT.decode(buffer);
            if (count < 0 || count > Pattern.MAX_BYPRODUCT_KEYS)
              throw new IllegalArgumentException("Remainder count");
            var remainders = new ArrayList<ItemStack>(count);
            for (int index = 0; index < count; index++)
              remainders.add(ItemStack.STREAM_CODEC.decode(buffer));
            var alternatives = new ArrayList<List<String>>(CELLS);
            for (int index = 0; index < CELLS; index++) {
              int size = ByteBufCodecs.VAR_INT.decode(buffer);
              if (size < 0 || size > Pattern.MAX_ALTERNATIVES)
                throw new IllegalArgumentException("Substitute count");
              var list = new ArrayList<String>(size);
              for (int item = 0; item < size; item++) list.add(buffer.readUtf(256));
              alternatives.add(list);
            }
            return new PatternContents(
                id, version, priority, cells, output, remainders, alternatives);
          });
}
