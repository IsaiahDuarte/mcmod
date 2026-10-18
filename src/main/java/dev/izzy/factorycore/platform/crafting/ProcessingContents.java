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
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Encoded processing pattern: exact item and fluid inputs inserted into a machine and deterministic
 * outputs collected from it. Item counts are 1–64 and fluid amounts 1–16,000 mB per craft; inputs
 * total 1–9 entries and outputs 1–3. The primary output is the first item output, or the first
 * fluid output when there are no item outputs; the rest are byproducts. The player declares
 * outputs; no machine recipe is consulted. Fluid lists are optional, so item-only patterns encoded
 * before fluids existed decode unchanged.
 */
public final class ProcessingContents {
  public static final int MAX_INPUTS = Pattern.MAX_INPUT_KEYS;
  public static final int MAX_OUTPUTS = 3;
  public static final int MAX_FLUID = 16_000;

  private final UUID id;
  private final long version;
  private final int priority;
  private final List<ItemStack> inputs;
  private final List<ItemStack> outputs;
  private final List<FluidStack> fluidInputs;
  private final List<FluidStack> fluidOutputs;

  public ProcessingContents(
      UUID id, long version, int priority, List<ItemStack> inputs, List<ItemStack> outputs) {
    this(id, version, priority, inputs, outputs, List.of(), List.of());
  }

  public ProcessingContents(
      UUID id,
      long version,
      int priority,
      List<ItemStack> inputs,
      List<ItemStack> outputs,
      List<FluidStack> fluidInputs,
      List<FluidStack> fluidOutputs) {
    this.id = Objects.requireNonNull(id);
    if (version < 1) throw new IllegalArgumentException("Pattern version must be positive");
    if (priority < Pattern.MIN_PRIORITY || priority > Pattern.MAX_PRIORITY)
      throw new IllegalArgumentException("Pattern priority out of range");
    this.version = version;
    this.priority = priority;
    this.inputs = copy(inputs, "input");
    this.outputs = copy(outputs, "output");
    this.fluidInputs = fluids(fluidInputs, "input");
    this.fluidOutputs = fluids(fluidOutputs, "output");
    int in = this.inputs.size() + this.fluidInputs.size();
    int out = this.outputs.size() + this.fluidOutputs.size();
    if (in < 1 || in > MAX_INPUTS)
      throw new IllegalArgumentException("Processing pattern needs 1–" + MAX_INPUTS + " inputs");
    if (out < 1 || out > MAX_OUTPUTS)
      throw new IllegalArgumentException("Processing pattern needs 1–" + MAX_OUTPUTS + " outputs");
  }

  private static List<FluidStack> fluids(List<FluidStack> stacks, String what) {
    var copied = new ArrayList<FluidStack>(stacks.size());
    for (var stack : stacks) {
      if (stack.isEmpty() || stack.getAmount() > MAX_FLUID)
        throw new IllegalArgumentException("Processing fluid " + what + "s are 1–16000 mB");
      copied.add(stack.copy());
    }
    return List.copyOf(copied);
  }

  private static List<ItemStack> copy(List<ItemStack> stacks, String what) {
    if (stacks.size() > MAX_INPUTS) throw new IllegalArgumentException("Too many " + what + "s");
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

  public List<FluidStack> fluidInputs() {
    return fluidInputs.stream().map(FluidStack::copy).toList();
  }

  public List<FluidStack> fluidOutputs() {
    return fluidOutputs.stream().map(FluidStack::copy).toList();
  }

  /** Exact portable contract; throws IllegalArgumentException for unrepresentable stacks. */
  public Pattern pattern(PlatformResourceCodec codec) {
    var in = new LinkedHashMap<ResourceKey, Long>();
    for (var stack : inputs)
      in.merge(codec.itemKey(stack), (long) stack.getCount(), Math::addExact);
    for (var stack : fluidInputs)
      in.merge(codec.fluidKey(stack), (long) stack.getAmount(), Math::addExact);
    var out = new LinkedHashMap<ResourceKey, Long>();
    for (var stack : outputs)
      out.merge(codec.itemKey(stack), (long) stack.getCount(), Math::addExact);
    for (var stack : fluidOutputs)
      out.merge(codec.fluidKey(stack), (long) stack.getAmount(), Math::addExact);
    if (out.size() != outputs.size() + fluidOutputs.size())
      throw new IllegalArgumentException("Primary output repeated as a byproduct");
    var primary = out.entrySet().iterator().next();
    var byproducts = new LinkedHashMap<>(out);
    byproducts.remove(primary.getKey());
    return new Pattern(
        id,
        version,
        Pattern.Type.PROCESSING,
        priority,
        in,
        primary.getKey(),
        primary.getValue(),
        byproducts);
  }

  private static boolean sameFluids(List<FluidStack> left, List<FluidStack> right) {
    if (left.size() != right.size()) return false;
    for (int index = 0; index < left.size(); index++)
      if (!FluidStack.matches(left.get(index), right.get(index))) return false;
    return true;
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
        && same(outputs, that.outputs)
        && sameFluids(fluidInputs, that.fluidInputs)
        && sameFluids(fluidOutputs, that.fluidOutputs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, version, priority);
  }

  private record Raw(
      UUID id,
      long version,
      int priority,
      List<ItemStack> inputs,
      List<ItemStack> outputs,
      List<FluidStack> fluidInputs,
      List<FluidStack> fluidOutputs) {}

  public static final Codec<ProcessingContents> CODEC =
      RecordCodecBuilder.<Raw>create(
              instance ->
                  instance
                      .group(
                          UUIDUtil.CODEC.fieldOf("id").forGetter(Raw::id),
                          Codec.LONG.fieldOf("version").forGetter(Raw::version),
                          Codec.INT.fieldOf("priority").forGetter(Raw::priority),
                          ItemStack.CODEC
                              .listOf()
                              .optionalFieldOf("inputs", List.of())
                              .forGetter(Raw::inputs),
                          ItemStack.CODEC
                              .listOf()
                              .optionalFieldOf("outputs", List.of())
                              .forGetter(Raw::outputs),
                          FluidStack.CODEC
                              .listOf()
                              .optionalFieldOf("fluid_inputs", List.of())
                              .forGetter(Raw::fluidInputs),
                          FluidStack.CODEC
                              .listOf()
                              .optionalFieldOf("fluid_outputs", List.of())
                              .forGetter(Raw::fluidOutputs))
                      .apply(instance, Raw::new))
          .comapFlatMap(
              raw -> {
                try {
                  return DataResult.success(
                      new ProcessingContents(
                          raw.id(),
                          raw.version(),
                          raw.priority(),
                          raw.inputs(),
                          raw.outputs(),
                          raw.fluidInputs(),
                          raw.fluidOutputs()));
                } catch (IllegalArgumentException invalid) {
                  return DataResult.error(invalid::getMessage);
                }
              },
              value ->
                  new Raw(
                      value.id,
                      value.version,
                      value.priority,
                      value.inputs,
                      value.outputs,
                      value.fluidInputs,
                      value.fluidOutputs));

  private static void stacks(RegistryFriendlyByteBuf buffer, List<ItemStack> values) {
    ByteBufCodecs.VAR_INT.encode(buffer, values.size());
    for (var value : values) ItemStack.STREAM_CODEC.encode(buffer, value);
  }

  private static void fluids(RegistryFriendlyByteBuf buffer, List<FluidStack> values) {
    ByteBufCodecs.VAR_INT.encode(buffer, values.size());
    for (var value : values) FluidStack.STREAM_CODEC.encode(buffer, value);
  }

  private static List<FluidStack> fluids(RegistryFriendlyByteBuf buffer, int max) {
    int count = ByteBufCodecs.VAR_INT.decode(buffer);
    if (count < 0 || count > max) throw new IllegalArgumentException("Processing fluid count");
    var values = new ArrayList<FluidStack>(count);
    for (int index = 0; index < count; index++) values.add(FluidStack.STREAM_CODEC.decode(buffer));
    return values;
  }

  private static List<ItemStack> stacks(RegistryFriendlyByteBuf buffer, int max) {
    int count = ByteBufCodecs.VAR_INT.decode(buffer);
    if (count < 0 || count > max) throw new IllegalArgumentException("Processing stack count");
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
            fluids(buffer, value.fluidInputs);
            fluids(buffer, value.fluidOutputs);
          },
          buffer ->
              new ProcessingContents(
                  UUIDUtil.STREAM_CODEC.decode(buffer),
                  buffer.readVarLong(),
                  buffer.readVarInt(),
                  stacks(buffer, MAX_INPUTS),
                  stacks(buffer, MAX_OUTPUTS),
                  fluids(buffer, MAX_INPUTS),
                  fluids(buffer, MAX_OUTPUTS)));
}
