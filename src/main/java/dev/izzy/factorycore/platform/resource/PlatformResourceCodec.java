package dev.izzy.factorycore.platform.resource;

import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Registry-aware identity encoding, normalized to one unit; never holds mutable platform stacks.
 */
public final class PlatformResourceCodec {
  private final HolderLookup.Provider registries;

  public PlatformResourceCodec(HolderLookup.Provider registries) {
    this.registries = java.util.Objects.requireNonNull(registries);
  }

  public ResourceKey itemKey(ItemStack stack) {
    if (stack.isEmpty()) throw new IllegalArgumentException("Empty stack has no stored identity");
    return new ResourceKey(
        ResourceKind.ITEM,
        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
        canonical((CompoundTag) stack.copyWithCount(1).save(registries)));
  }

  public ResourceKey fluidKey(FluidStack stack) {
    if (stack.isEmpty()) throw new IllegalArgumentException("Empty fluid has no stored identity");
    return new ResourceKey(
        ResourceKind.FLUID,
        BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
        canonical((CompoundTag) stack.copyWithAmount(1).save(registries)));
  }

  public ItemStack itemStack(ResourceKey key, int count) {
    if (key.kind() != ResourceKind.ITEM || count <= 0)
      throw new IllegalArgumentException("Expected item identity and positive count");
    var stack =
        ItemStack.parse(registries, decode(key))
            .orElseThrow(() -> new IllegalArgumentException("Unsupported item identity"));
    if (!itemKey(stack).equals(key))
      throw new IllegalArgumentException("Item identity no longer round-trips exactly");
    return stack.copyWithCount(count);
  }

  public FluidStack fluidStack(ResourceKey key, int amount) {
    if (key.kind() != ResourceKind.FLUID || amount <= 0)
      throw new IllegalArgumentException("Expected fluid identity and positive mB amount");
    var stack =
        FluidStack.parse(registries, decode(key))
            .orElseThrow(() -> new IllegalArgumentException("Unsupported fluid identity"));
    if (!fluidKey(stack).equals(key))
      throw new IllegalArgumentException("Fluid identity no longer round-trips exactly");
    return stack.copyWithAmount(amount);
  }

  /**
   * Standard NBT with recursive compound-key ordering; list order and primitive types are exact.
   */
  public static byte[] canonical(CompoundTag tag) {
    try (var bytes = new BoundedOutput();
        var output = new DataOutputStream(bytes)) {
      output.writeByte(Tag.TAG_COMPOUND);
      output.writeUTF("");
      writePayload(tag, output, 0);
      return bytes.toByteArray();
    } catch (IOException error) {
      throw new UncheckedIOException("Cannot encode resource identity", error);
    }
  }

  private static void writePayload(Tag tag, DataOutputStream output, int depth) throws IOException {
    if (depth > 64)
      throw new IllegalArgumentException("Resource identity exceeds 64-level nesting limit");
    if (tag instanceof CompoundTag compound) {
      var keys = new ArrayList<>(compound.getAllKeys());
      Collections.sort(keys);
      for (var name : keys) {
        var child = java.util.Objects.requireNonNull(compound.get(name));
        output.writeByte(child.getId());
        output.writeUTF(name);
        writePayload(child, output, depth + 1);
      }
      output.writeByte(Tag.TAG_END);
    } else if (tag instanceof ListTag list) {
      output.writeByte(list.getElementType());
      output.writeInt(list.size());
      for (var child : list) writePayload(child, output, depth + 1);
    } else {
      tag.write(output);
    }
  }

  private static CompoundTag decode(ResourceKey key) {
    try (var input = new DataInputStream(new ByteArrayInputStream(key.components()))) {
      var tag = NbtIo.read(input, NbtAccounter.create(1_048_576));
      if (input.available() != 0)
        throw new IllegalArgumentException("Trailing resource identity bytes");
      return tag;
    } catch (IOException error) {
      throw new IllegalArgumentException("Invalid resource identity encoding", error);
    }
  }

  private static final class BoundedOutput extends ByteArrayOutputStream {
    @Override
    public synchronized void write(int value) {
      checkSpace(1);
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      checkSpace(length);
      super.write(bytes, offset, length);
    }

    private void checkSpace(int length) {
      if (length < 0 || length > ResourceKey.MAX_COMPONENT_BYTES - count) {
        throw new IllegalArgumentException("Resource identity exceeds 65536-byte limit");
      }
    }
  }
}
