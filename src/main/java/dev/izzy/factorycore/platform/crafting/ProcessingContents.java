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
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * Encoded processing pattern: exact item inputs inserted into a machine and deterministic outputs
 * collected from it. The first output is the primary output; the rest are byproducts. Counts are
 * per craft (1–64 each). The player declares outputs; no machine recipe is consulted.
 */
public final class ProcessingContents {
  public static final int MAX_INPUTS = Pattern.MAX_INPUT_KEYS;
  public static final int MAX_OUTPUTS = 3;

  private final UUID id;
  private final long version;
  private final int priority;
  private final List<ItemStack> inputs;
  private final List<ItemStack> outputs;

  public ProcessingContents(
      UUID id, long version, int priority, List<ItemStack> inputs, List<ItemStack> outputs) {
    this.id = Objects.requireNonNull(id);
    if (version < 1) throw new IllegalArgumentException("Pattern version must be positive");
    if (priority < Pattern.MIN_PRIORITY || priority > Pattern.MAX_PRIORITY)
      throw new IllegalArgumentException("Pattern priority out of range");
    this.version = version;
    this.priority = priority;
    this.inputs = copy(inputs, MAX_INPUTS, "input");
    this.outputs = copy(outputs, MAX_OUTPUTS, "output");
  }

  private static List<ItemStack> copy(List<ItemStack> stacks, int max, String what) {
    if (stacks.isEmpty() || stacks.size() > max)
      throw new IllegalArgumentException("Processing pattern needs 1–" + max + " " + what + "s");
    var copied = new ArrayList<ItemStack>(stacks.size());
    for (var stack : stacks) {
      if (stack.isEmpty() || stack.getCount() > 64)
        throw new IllegalArgumentException("Processing " + what + " counts are 1–64");
      copied.add(stack.copy());
    }
    return List.copyOf(copied);
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

  public List<ItemStack> inputs() {
    return inputs.stream().map(ItemStack::copy).toList();
  }

  public List<ItemStack> outputs() {
    return outputs.stream().map(ItemStack::copy).toList();
  }

  /** Exact portable contract; throws IllegalArgumentException for unrepresentable stacks. */
  public Pattern pattern(PlatformResourceCodec codec) {
    var in = new LinkedHashMap<ResourceKey, Long>();
    for (var stack : inputs)
      in.merge(codec.itemKey(stack), (long) stack.getCount(), Math::addExact);
    var primary = outputs.getFirst();
    var byproducts = new LinkedHashMap<ResourceKey, Long>();
    for (var stack : outputs.subList(1, outputs.size()))
      byproducts.merge(codec.itemKey(stack), (long) stack.getCount(), Math::addExact);
    var key = codec.itemKey(primary);
    if (byproducts.containsKey(key))
      throw new IllegalArgumentException("Primary output repeated as a byproduct");
    return new Pattern(
        id, version, Pattern.Type.PROCESSING, priority, in, key, primary.getCount(), byproducts);
  }

  private static boolean same(List<ItemStack> left, List<ItemStack> right) {
    if (left.size() != right.size()) return false;
    for (int index = 0; index < left.size(); index++)
      if (!ItemStack.matches(left.get(index), right.get(index))) return false;
    return true;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ProcessingContents that
        && id.equals(that.id)
        && version == that.version
        && priority == that.priority
        && same(inputs, that.inputs)
        && same(outputs, that.outputs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, version, priority);
  }

  private record Raw(
      UUID id, long version, int priority, List<ItemStack> inputs, List<ItemStack> outputs) {}

  public static final Codec<ProcessingContents> CODEC =
      RecordCodecBuilder.<Raw>create(
              instance ->
                  instance
                      .group(
                          UUIDUtil.CODEC.fieldOf("id").forGetter(Raw::id),
                          Codec.LONG.fieldOf("version").forGetter(Raw::version),
                          Codec.INT.fieldOf("priority").forGetter(Raw::priority),
                          ItemStack.CODEC.listOf().fieldOf("inputs").forGetter(Raw::inputs),
                          ItemStack.CODEC.listOf().fieldOf("outputs").forGetter(Raw::outputs))
                      .apply(instance, Raw::new))
          .comapFlatMap(
              raw -> {
                try {
                  return DataResult.success(
                      new ProcessingContents(
                          raw.id(), raw.version(), raw.priority(), raw.inputs(), raw.outputs()));
                } catch (IllegalArgumentException invalid) {
                  return DataResult.error(invalid::getMessage);
                }
              },
              value ->
                  new Raw(value.id, value.version, value.priority, value.inputs, value.outputs));

  private static void stacks(RegistryFriendlyByteBuf buffer, List<ItemStack> values) {
    ByteBufCodecs.VAR_INT.encode(buffer, values.size());
    for (var value : values) ItemStack.STREAM_CODEC.encode(buffer, value);
  }

  private static List<ItemStack> stacks(RegistryFriendlyByteBuf buffer, int max) {
    int count = ByteBufCodecs.VAR_INT.decode(buffer);
    if (count < 1 || count > max) throw new IllegalArgumentException("Processing stack count");
    var values = new ArrayList<ItemStack>(count);
    for (int index = 0; index < count; index++) values.add(ItemStack.STREAM_CODEC.decode(buffer));
    return values;
  }

  public static final StreamCodec<RegistryFriendlyByteBuf, ProcessingContents> STREAM_CODEC =
      StreamCodec.of(
          (buffer, value) -> {
            UUIDUtil.STREAM_CODEC.encode(buffer, value.id);
            buffer.writeVarLong(value.version);
            buffer.writeVarInt(value.priority);
            stacks(buffer, value.inputs);
            stacks(buffer, value.outputs);
          },
          buffer ->
              new ProcessingContents(
                  UUIDUtil.STREAM_CODEC.decode(buffer),
                  buffer.readVarLong(),
                  buffer.readVarInt(),
                  stacks(buffer, MAX_INPUTS),
                  stacks(buffer, MAX_OUTPUTS)));
}
