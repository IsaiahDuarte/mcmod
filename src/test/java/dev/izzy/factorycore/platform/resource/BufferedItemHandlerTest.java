package dev.izzy.factorycore.platform.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.storage.ItemBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BufferedItemHandlerTest {
  private static PlatformResourceCodec codec;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    codec =
        new PlatformResourceCodec(
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
  }

  @Test
  void realHopperHandlerKeepsComponentsAndSharesExactTransferQuantities() {
    var changes = new AtomicInteger();
    var buffer =
        new ItemBuffer(
            ItemBuffer.empty(UUID.randomUUID()),
            key -> codec.itemStack(key, 1).getMaxStackSize(),
            changes::incrementAndGet);
    var handler = new BufferedItemHandler(buffer, codec, () -> true);
    var named = new ItemStack(Items.IRON_INGOT, 70);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("Buffered exact identity"));
    var key = codec.itemKey(named);
    assertEquals(9, handler.getSlots());
    assertEquals(6, handler.insertItem(0, named, true).getCount());
    assertEquals(0, buffer.total(key));
    assertEquals(0, changes.get());
    assertEquals(6, handler.insertItem(0, named, false).getCount());
    assertEquals(70, named.getCount());
    assertEquals(64, buffer.total(key));
    var view = handler.getStackInSlot(0);
    view.setCount(1);
    assertEquals(64, handler.getStackInSlot(0).getCount());
    assertEquals(key, codec.itemKey(handler.extractItem(0, 20, true)));
    assertEquals(64, buffer.total(key));
    assertEquals(20, handler.extractItem(0, 20, false).getCount());
    assertEquals(44, buffer.total(key));
    assertEquals(30, buffer.extract(key, 30));
    assertEquals(14, handler.getStackInSlot(0).getCount());
    assertTrue(handler.insertItem(8, new ItemStack(Items.IRON_PICKAXE), false).isEmpty());
    assertEquals(1, handler.getStackInSlot(8).getCount());
    assertEquals(1, handler.insertItem(8, new ItemStack(Items.IRON_PICKAXE), false).getCount());
    assertEquals(4, changes.get());
    var oversized = named.copyWithCount(1);
    oversized.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(65536)));
    var snapshot = buffer.snapshot();
    assertEquals(1, handler.insertItem(7, oversized, false).getCount());
    assertEquals(snapshot, buffer.snapshot());
    assertEquals(4, changes.get());
  }

  @Test
  void staleCapabilityCannotObserveOrMutateBufferAndCanRevalidate() {
    var usable = new AtomicBoolean(true);
    var buffer = new ItemBuffer(ItemBuffer.empty(UUID.randomUUID()), key -> 64, () -> {});
    var handler = new BufferedItemHandler(buffer, codec, usable::get);
    var stack = new ItemStack(Items.IRON_INGOT, 23);
    handler.insertItem(0, stack, false);
    var key = codec.itemKey(stack);
    usable.set(false);
    assertTrue(handler.getStackInSlot(0).isEmpty());
    assertTrue(handler.extractItem(0, 23, false).isEmpty());
    assertEquals(23, handler.insertItem(1, stack, false).getCount());
    assertFalse(handler.isItemValid(1, stack));
    assertEquals(23, buffer.total(key));
    usable.set(true);
    assertEquals(23, handler.getStackInSlot(0).getCount());
    assertThrows(IllegalArgumentException.class, () -> handler.extractItem(0, -1, false));
    assertThrows(IllegalArgumentException.class, () -> handler.getStackInSlot(9));
  }
}
