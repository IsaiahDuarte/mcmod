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
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
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

  @Test
  void largeSparseSlotIndexStaysBoundedAndNegativeSlotCountsRejectWithoutOverflow() {
    var actual = new ItemStackHandler(1);
    actual.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
    int[] reported = {Integer.MAX_VALUE};
    int selected = Integer.MAX_VALUE - 1;
    var sparse =
        new IItemHandler() {
          private void requireSlot(int slot) {
            if (slot != selected) throw new IllegalArgumentException("Unexpected sparse slot");
          }

          @Override
          public int getSlots() {
            return reported[0];
          }

          @Override
          public ItemStack getStackInSlot(int slot) {
            requireSlot(slot);
            return actual.getStackInSlot(0);
          }

          @Override
          public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            requireSlot(slot);
            return actual.insertItem(0, stack, simulate);
          }

          @Override
          public ItemStack extractItem(int slot, int amount, boolean simulate) {
            requireSlot(slot);
            return actual.extractItem(0, amount, simulate);
          }

          @Override
          public int getSlotLimit(int slot) {
            requireSlot(slot);
            return 64;
          }

          @Override
          public boolean isItemValid(int slot, ItemStack stack) {
            requireSlot(slot);
            return true;
          }
        };
    var key = codec.itemKey(actual.getStackInSlot(0));
    var port = new ItemHandlerPort(UUID.randomUUID(), sparse, codec, selected, 1);
    var destination = new ResourceLedger(UUID.randomUUID(), ResourceKind.ITEM, 3, false, 8, 8);
    var transfer =
        StagedTransfer.start(
            UUID.randomUUID(), key, port, new LedgerPort(destination, null), 64, 64, () -> true);
    assertEquals(3, transfer.delivered());
    assertEquals(61, actual.getStackInSlot(0).getCount());
    assertEquals(3, destination.stock(key).total());
    assertEquals(3, port.simulateInsert(key, 20));
    assertEquals(61, actual.getStackInSlot(0).getCount());
    reported[0] = Integer.MIN_VALUE;
    assertThrows(
        IllegalArgumentException.class,
        () -> new ItemHandlerPort(UUID.randomUUID(), sparse, codec, 0, 1));
  }

  @Test
  void foreignStockPagesBoundReadsAndKeepComponentIdentities() {
    var handler = new ItemStackHandler(40);
    var named = new ItemStack(Items.IRON_INGOT, 31);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("Exact observed identity"));
    handler.setStackInSlot(2, named);
    handler.setStackInSlot(18, new ItemStack(Items.GOLD_INGOT, 17));
    handler.setStackInSlot(35, new ItemStack(Items.DIAMOND, 1));
    var first = ExternalStockPage.read(ResourceKind.ITEM, handler, codec, 2, 32, 0);
    assertEquals(16, first.next());
    assertEquals(1, first.entries().size());
    assertEquals(codec.itemKey(named), first.entries().getFirst().key());
    assertEquals(31, first.entries().getFirst().stored());
    assertNull(first.entries().getFirst().capacity());
    var second = ExternalStockPage.read(ResourceKind.ITEM, handler, codec, 2, 32, first.next());
    assertEquals(-1, second.next());
    assertEquals(17, second.entries().getFirst().stored());
    assertEquals(16, second.entries().getFirst().index());
    assertEquals(31, handler.getStackInSlot(2).getCount());
    assertThrows(UnsupportedOperationException.class, () -> first.entries().clear());
    assertTrue(
        ExternalStockPage.read(ResourceKind.ITEM, handler, codec, 2, 32, 100).entries().isEmpty());
  }

  @Test
  void foreignFluidAndEnergyObservationsReportKnownCapacityWithoutMutation() {
    var tank = new FluidTank(5000);
    tank.fill(new FluidStack(Fluids.WATER, 2500), IFluidHandler.FluidAction.EXECUTE);
    var fluid = ExternalStockPage.read(ResourceKind.FLUID, tank, codec, 0, 1, 0);
    assertEquals(codec.fluidKey(tank.getFluid()), fluid.entries().getFirst().key());
    assertEquals(2500, fluid.entries().getFirst().stored());
    assertEquals(5000L, fluid.entries().getFirst().capacity());
    var energy = new EnergyStorage(10000);
    energy.receiveEnergy(3000, false);
    var observed = ExternalStockPage.read(ResourceKind.ENERGY, energy, codec, 0, 1, 0);
    assertEquals(ResourceKey.ENERGY, observed.entries().getFirst().key());
    assertEquals(3000, observed.entries().getFirst().stored());
    assertEquals(10000L, observed.entries().getFirst().capacity());
    assertEquals(2500, tank.getFluidAmount());
    assertEquals(3000, energy.getEnergyStored());
  }

  @Test
  void foreignCatalogReadCallsStayBoundedForHugeProviders() {
    int[] reads = {0};
    var handler =
        new ItemStackHandler() {
          @Override
          public int getSlots() {
            return Integer.MAX_VALUE;
          }

          @Override
          public ItemStack getStackInSlot(int slot) {
            reads[0]++;
            return new ItemStack(Items.IRON_INGOT, 1);
          }
        };
    var page =
        ExternalStockPage.read(ResourceKind.ITEM, handler, codec, Integer.MAX_VALUE - 64, 64, 0);
    assertEquals(16, reads[0]);
    assertEquals(16, page.entries().size());
    var tail =
        ExternalStockPage.read(ResourceKind.ITEM, handler, codec, Integer.MAX_VALUE - 64, 64, 63);
    assertEquals(17, reads[0]);
    assertEquals(-1, tail.next());
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalStockPage.read(ResourceKind.ITEM, handler, codec, 0, 1, -1));
  }
}
