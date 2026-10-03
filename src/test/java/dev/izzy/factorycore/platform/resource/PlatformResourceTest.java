package dev.izzy.factorycore.platform.resource;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.resource.LedgerPort;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourceLedger;
import dev.izzy.factorycore.core.resource.StagedTransfer;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlatformResourceTest {
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
  void itemComponentsNormalizeCountsAndCanonicalizeCompoundOrder() {
    var first = new CompoundTag();
    first.putInt("a", 1);
    first.putInt("b", 2);
    var second = new CompoundTag();
    second.putInt("b", 2);
    second.putInt("a", 1);
    var iron = new ItemStack(Items.IRON_INGOT, 64);
    iron.set(DataComponents.CUSTOM_DATA, CustomData.of(first));
    var equivalent = iron.copyWithCount(1);
    equivalent.set(DataComponents.CUSTOM_DATA, CustomData.of(second));
    var key = codec.itemKey(iron);
    assertEquals(key, codec.itemKey(equivalent));
    var decoded = codec.itemStack(key, 32);
    assertEquals(32, decoded.getCount());
    assertTrue(ItemStack.isSameItemSameComponents(iron, decoded));
    equivalent.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct"));
    assertNotEquals(key, codec.itemKey(equivalent));
    assertThrows(IllegalArgumentException.class, () -> codec.itemKey(ItemStack.EMPTY));
    var giant = new CompoundTag();
    giant.putByteArray("too_large", new byte[65_536]);
    assertThrows(IllegalArgumentException.class, () -> PlatformResourceCodec.canonical(giant));
  }

  @Test
  void realItemHandlerRetainsFullAndPartialRemaindersInSelectedSlots() {
    var source = new ItemStackHandler(2);
    source.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
    source.setStackInSlot(1, new ItemStack(Items.GOLD_INGOT, 64));
    var target = new ItemStackHandler(2);
    target.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 60));
    var key = codec.itemKey(source.getStackInSlot(0));
    var sourcePort = new ItemHandlerPort(UUID.randomUUID(), source, codec, 0, 1);
    var targetPort = new ItemHandlerPort(UUID.randomUUID(), target, codec, 0, 1);
    var move =
        StagedTransfer.start(UUID.randomUUID(), key, sourcePort, targetPort, 64, 64, () -> true);
    assertEquals(4, move.delivered());
    assertEquals(60, source.getStackInSlot(0).getCount());
    assertEquals(64, target.getStackInSlot(0).getCount());
    assertTrue(target.getStackInSlot(1).isEmpty());
    assertEquals(0, sourcePort.extract(codec.itemKey(new ItemStack(Items.GOLD_INGOT)), 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ItemHandlerPort(UUID.randomUUID(), source, codec, 0, 65));
    assertEquals(
        0,
        StagedTransfer.start(UUID.randomUUID(), key, sourcePort, targetPort, 64, 64, () -> true)
            .delivered());
    assertEquals(60, source.getStackInSlot(0).getCount());
  }

  @Test
  void realFluidTankUsesExactMillibucketsAndMetadata() {
    var source = new FluidTank(2000);
    source.setFluid(new FluidStack(Fluids.WATER, 1000));
    var target = new FluidTank(333);
    var key = codec.fluidKey(source.getFluid());
    assertEquals(key, codec.fluidKey(source.getFluid().copyWithAmount(1)));
    assertEquals(1000, codec.fluidStack(key, 1000).getAmount());
    var decorated = source.getFluid().copy();
    decorated.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
    assertNotEquals(key, codec.fluidKey(decorated));
    var move =
        StagedTransfer.start(
            UUID.randomUUID(),
            key,
            new FluidHandlerPort(UUID.randomUUID(), source, codec),
            new FluidHandlerPort(UUID.randomUUID(), target, codec),
            1000,
            1000,
            () -> true);
    assertEquals(333, move.delivered());
    assertEquals(667, source.getFluidAmount());
    assertEquals(333, target.getFluidAmount());
    assertEquals(
        0,
        new FluidHandlerPort(UUID.randomUUID(), source, codec)
            .extract(codec.fluidKey(new FluidStack(Fluids.LAVA, 1)), 1));
  }

  @Test
  void realEnergyStorageHonorsRatesAndTypeWithoutImplicitEnergyLoss() {
    var source = new EnergyStorage(1000, 1000, 77, 1000);
    var target = new EnergyStorage(1000, 51, 1000);
    var move =
        StagedTransfer.start(
            UUID.randomUUID(),
            ResourceKey.ENERGY,
            new EnergyHandlerPort(UUID.randomUUID(), source),
            new EnergyHandlerPort(UUID.randomUUID(), target),
            1000,
            1000,
            () -> true);
    assertEquals(51, move.delivered());
    assertEquals(949, source.getEnergyStored());
    assertEquals(51, target.getEnergyStored());
    assertEquals(
        0,
        new EnergyHandlerPort(UUID.randomUUID(), target)
            .insert(codec.itemKey(new ItemStack(Items.COAL)), 10));
    var bank = new ResourceLedger(UUID.randomUUID(), ResourceKind.ENERGY, 1000, false, 1, 10);
    var digital =
        StagedTransfer.start(
            UUID.randomUUID(),
            ResourceKey.ENERGY,
            new EnergyHandlerPort(UUID.randomUUID(), source),
            new LedgerPort(bank, null),
            1000,
            1000,
            () -> true);
    assertEquals(77, digital.delivered());
    assertEquals(77, bank.total());
    assertEquals(1000, source.getEnergyStored() + target.getEnergyStored() + bank.total());
  }
}
