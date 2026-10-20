package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.CraftingInterfaceEntity;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.ConnectorFixture;
import dev.izzy.factorycore.testing.Net;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class IndexedMachinePortsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      FIRST = new BlockPos(3, 1, 1),
      SECOND = new BlockPos(4, 1, 2),
      PROVIDER = new BlockPos(4, 1, 1),
      DRIVE = new BlockPos(1, 1, 0);

  private record Fixture(
      NetworkPermissions.Actor actor,
      WorldNetworks runtime,
      MachineInterfaceEntity first,
      MachineInterfaceEntity second,
      ConnectorFixture.ProviderEntity provider,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell) {}

  private IndexedMachinePortsGameTests() {}

  private static MachineInterfaceEntity machine(GameTestHelper h, BlockPos p, Direction direction) {
    h.setBlock(
        p,
        MachineInterfaceContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, direction));
    MachineInterfaceEntity machine = h.getBlockEntity(p);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    return machine;
  }

  private static Fixture setup(GameTestHelper h) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    NetworkNodeEntity root = Net.in(h, actor.id()).controller(ROOT);
    h.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    h.setBlock(new BlockPos(3, 1, 2), NetworkContent.CABLE.get());
    var first = machine(h, FIRST, Direction.EAST);
    var second = machine(h, SECOND, Direction.NORTH);
    h.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = h.getBlockEntity(PROVIDER);
    provider.partitioned = true;
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_FLUID);
    return new Fixture(
        actor,
        WorldNetworks.get(h.getLevel().getServer()),
        first,
        second,
        provider,
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.runtime.membership(f.first.position()).status() == NetworkTopology.Status.VALID
            && f.runtime.membership(f.second.position()).status() == NetworkTopology.Status.VALID,
        "Both local ports ready");
  }

  private static ConnectorSettings settings(ResourceKind kind, int first) {
    return new ConnectorSettings(
        kind,
        ConnectorSettings.Access.READ_WRITE,
        0,
        first,
        1,
        kind == ResourceKind.ITEM ? 128 : 1000);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void scheduledSelectedTankLeavesSameFluidInExcludedTankUntouched(GameTestHelper h) {
    var f = setup(h);
    f.provider.indexedFluids.tanks[0].setFluid(new FluidStack(Fluids.WATER, 900));
    f.provider.indexedFluids.tanks[1].setFluid(new FluidStack(Fluids.WATER, 1200));
    var key =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .fluidKey(new FluidStack(Fluids.WATER, 1));
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.first.configure(f.actor, settings(ResourceKind.FLUID, 1));
              var handle = f.runtime.connectors().bind(f.first).handle();
              h.assertTrue(
                  handle != null && f.runtime.connectors().stock(handle, key).amount() == 1200,
                  "Only selected tank is stock");
              var page = f.runtime.connectors().observe(f.actor, f.first.position(), handle, 0);
              h.assertTrue(
                  page.page().entries().size() == 1
                      && page.page().entries().getFirst().stored() == 1200,
                  "Catalog excludes identical fluid in other tank");
              f.runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      f.actor,
                      f.first.position(),
                      new WorldStorageTransfers.External(handle),
                      new WorldStorageTransfers.Digital(f.cell),
                      key,
                      750,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Scheduled fluid move returned"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  result[0].reason() == OperationResult.Reason.OK
                      && f.provider.indexedFluids.tanks[0].getFluidAmount() == 900
                      && f.provider.indexedFluids.tanks[1].getFluidAmount() == 450
                      && f.saved.registry().stock(f.cell, key).total() == 750,
                  "Exactly 2100 mB conserved; excluded tank untouched");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fixedTankPortsShareRatesAndPreservePartitionHoldsOnRebind(GameTestHelper h) {
    var f = setup(h);
    f.provider.indexedFluids.tanks[0].setFluid(new FluidStack(Fluids.WATER, 1500));
    f.provider.indexedFluids.tanks[1].setFluid(new FluidStack(Fluids.WATER, 1500));
    var key =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .fluidKey(new FluidStack(Fluids.WATER, 1));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.first.configure(f.actor, settings(ResourceKind.FLUID, 0));
              f.second.configure(f.actor, settings(ResourceKind.FLUID, 1));
              var a = f.runtime.connectors().bind(f.first).handle();
              var b = f.runtime.connectors().bind(f.second).handle();
              h.assertTrue(
                  a != null && b != null && !a.backing().equals(b.backing()),
                  "Disjoint physical partitions bind independently");
              var claim = UUID.randomUUID();
              f.saved.externalClaims().reserve(b.backing(), claim, key, 1300, 1500);
              h.assertTrue(
                  f.runtime.connectors().port(a).extract(key, 800) == 800
                      && f.runtime.connectors().port(b).extract(key, 800) == 200,
                  "Shared physical provider cap is 1000 mB/tick; hold remains protected");
              var stalePort = f.runtime.connectors().port(a);
              f.first.configure(f.actor, settings(ResourceKind.FLUID, 1));
              h.assertTrue(
                  f.runtime.connectors().bind(f.first).reason() == OperationResult.Reason.OVERLAP,
                  "Same canonical tank rejects duplicate binding");
              h.assertTrue(
                  stalePort.extract(key, 1) == 0,
                  "Configuration invalidates cached selected port before any mutation");
              f.runtime.connectors().invalidate(f.second);
              var moved = f.runtime.connectors().bind(f.first).handle();
              h.assertTrue(
                  moved != null
                      && moved.backing().equals(b.backing())
                      && f.saved.externalClaims().reserved(moved.backing(), key) == 1300,
                  "Rebinding canonical tank retains exactly its saved holds");
              f.first.configure(
                  f.actor,
                  new ConnectorSettings(
                      ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 0, 0, 2, 1000));
              h.assertTrue(
                  f.runtime.connectors().bind(f.first).reason()
                      == OperationResult.Reason.UNSUPPORTED,
                  "Undeclared overlapping union cannot bypass held partitions");
              h.assertTrue(
                  f.provider.indexedFluids.tanks[0].getFluidAmount() == 700
                      && f.provider.indexedFluids.tanks[1].getFluidAmount() == 1300,
                  "All 3000 mB accounted for including 1000 received by test");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fixedItemPortsAndEnergyCoexistWithoutOverlappingItemBinding(GameTestHelper h) {
    var f = setup(h);
    f.provider.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
    f.provider.items.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 64));
    f.provider.energy.receiveEnergy(1000, false);
    var key =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.IRON_INGOT));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.first.configure(f.actor, settings(ResourceKind.ITEM, 0));
              f.second.configure(f.actor, settings(ResourceKind.ITEM, 1));
              var a = f.runtime.connectors().bind(f.first).handle();
              var b = f.runtime.connectors().bind(f.second).handle();
              h.assertTrue(
                  a != null
                      && b != null
                      && f.runtime.connectors().stock(a, key).amount() == 64
                      && f.runtime.connectors().stock(b, key).amount() == 64,
                  "Disjoint global item slots expose independent stock");
              h.assertTrue(
                  f.runtime.connectors().port(a).extract(key, 64) == 64
                      && f.runtime.connectors().port(b).extract(key, 64) == 64,
                  "Shared 128 item group budget permits both ports");
              f.first.configure(f.actor, ConnectorSettings.defaults(ResourceKind.ENERGY));
              var energy = f.runtime.connectors().bind(f.first).handle();
              h.assertTrue(
                  energy != null
                      && f.runtime.connectors().port(energy).extract(ResourceKey.ENERGY, 500) == 500
                      && f.runtime.connectors().bind(f.second).handle().equals(b),
                  "Energy and item ports coexist on one physical machine");
              f.first.configure(f.actor, settings(ResourceKind.ITEM, 1));
              h.assertTrue(
                  f.runtime.connectors().bind(f.first).reason() == OperationResult.Reason.OVERLAP,
                  "Duplicate item slot remains exclusive");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void savedPartitionHoldBlocksProcessingEvenAfterPortUnregisters(GameTestHelper h) {
    var f = setup(h);
    f.provider.items.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 8));
    h.setBlock(new BlockPos(3, 1, 0), NetworkContent.CABLE.get());
    var craftingPos = new BlockPos(4, 1, 0);
    h.setBlock(
        craftingPos,
        CraftingContent.INTERFACE
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
    CraftingInterfaceEntity crafting = h.getBlockEntity(craftingPos);
    var pattern = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    pattern.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.GOLD_INGOT)),
            List.of(new ItemStack(Items.DIAMOND))));
    crafting.patterns().insertItem(0, pattern, false);
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var cell = CellHandleData.read(drive.cell(1));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var iron = codec.itemKey(new ItemStack(Items.IRON_INGOT));
    var gold = codec.itemKey(new ItemStack(Items.GOLD_INGOT));
    var diamond = codec.itemKey(new ItemStack(Items.DIAMOND));
    var claim = UUID.randomUUID();
    UUID[] id = {null};
    WorldConnectors.Handle[] held = {null};
    var jobs = WorldCraftingJobs.get(h.getLevel().getServer());
    h.startSequence()
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.runtime.membership(crafting.origin()).status() == NetworkTopology.Status.VALID
                      && f.saved.registry().stock(cell, gold).loaded(),
                  "Processing holder ready");
            })
        .thenExecute(
            () -> {
              f.first.configure(f.actor, settings(ResourceKind.ITEM, 0));
              held[0] = f.runtime.connectors().bind(f.first).handle();
              f.saved.externalClaims().reserve(held[0].backing(), claim, iron, 8, 8);
              f.runtime.connectors().invalidate(f.first);
              f.saved.registry().insert(cell, gold, 3);
              id[0] = jobs.submit(f.actor, crafting.origin(), diamond, 1).job();
              h.assertTrue(
                  id[0] != null, "Processing request admitted with digital ingredient reservation");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.saved.craftingJobs().get(id[0]).reason().equals("reserved"),
                    "Saved foreign hold blocks physical processing acquisition without a live connector binding"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.craftingJobs().get(id[0]).dispatch() == null
                      && f.saved.registry().stock(cell, gold).total() == 3
                      && f.provider.items.getStackInSlot(0).getCount() == 8
                      && f.provider.items.getStackInSlot(1).isEmpty(),
                  "Blocked processing pulled or inserted no ingredients");
              f.saved.externalClaims().release(held[0].backing(), claim, iron);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.saved.craftingJobs().get(id[0]).dispatch() != null,
                    "Processing resumes after the actual owner releases its hold"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.saved.registry().stock(cell, gold).total() == 2
                      && f.provider.items.getStackInSlot(0).getCount() == 8
                      && f.provider.items.getStackInSlot(1).is(Items.GOLD_INGOT),
                  "Only new job ingredient entered the unheld machine");
              jobs.cancel(f.actor, id[0]);
            })
        .thenSucceed();
  }
}
