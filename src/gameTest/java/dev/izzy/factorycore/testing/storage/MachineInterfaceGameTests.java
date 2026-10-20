package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.ConnectorFixture;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class MachineInterfaceGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      CABLE = new BlockPos(2, 1, 1),
      MACHINE = new BlockPos(3, 1, 1),
      PROVIDER = new BlockPos(4, 1, 1),
      DRIVE = new BlockPos(1, 1, 0);

  private record Fixture(
      NetworkPermissions.Actor actor,
      WorldNetworks runtime,
      MachineInterfaceEntity machine,
      ChestBlockEntity chest,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ResourceKey key) {}

  private MachineInterfaceGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos p) {
    return Positions.of(h.getLevel(), h.absolutePos(p));
  }

  private static MachineInterfaceEntity machine(GameTestHelper h, BlockPos p, Direction face) {
    h.setBlock(
        p,
        MachineInterfaceContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, face));
    MachineInterfaceEntity machine = h.getBlockEntity(p);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    return machine;
  }

  private static Fixture setup(GameTestHelper h) {
    var actor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    NetworkNodeEntity root = Net.in(h, actor.id()).controller(ROOT);
    h.setBlock(CABLE, NetworkContent.CABLE.get());
    var machine = machine(h, MACHINE, Direction.EAST);
    h.setBlock(PROVIDER, Blocks.CHEST);
    ChestBlockEntity chest = h.getBlockEntity(PROVIDER);
    chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 11));
    chest.setItem(1, new ItemStack(Items.IRON_INGOT, 32));
    StorageDriveEntity drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    return new Fixture(
        actor,
        WorldNetworks.get(h.getLevel().getServer()),
        machine,
        chest,
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        codec.itemKey(new ItemStack(Items.IRON_INGOT)));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.runtime.membership(f.machine.position()).status() == NetworkTopology.Status.VALID,
        "Wired machine ready");
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void localLabelSelectedSlotAndScheduledTransferExcludeGlobalCatalog(
      GameTestHelper h) {
    var f = setup(h);
    WorldStorageTransfers.CatalogPage[] page = {null};
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.configure(
                  f.actor,
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, 0, 1, 1, 8));
              f.machine.relabel(f.actor, "smelter");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.runtime
                            .machines(f.actor, position(h, ROOT), "smelter", null, 2)
                            .machines()
                            .size()
                        == 1,
                    "Published local label ready"))
        .thenExecute(
            () -> {
              var registration = f.runtime.connectors().bind(f.machine);
              h.assertTrue(
                  registration.reason() == OperationResult.Reason.OK,
                  "Local selected provider binds");
              h.assertTrue(
                  f.runtime.connectors().stock(registration.handle(), f.key).amount() == 32,
                  "Only selected input window observed");
              f.runtime
                  .transfers()
                  .catalog(
                      UUID.randomUUID(), f.actor, position(h, ROOT), f.key, null, v -> page[0] = v);
            })
        .thenWaitUntil(() -> h.assertTrue(page[0] != null, "Scheduled catalog completed"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  page[0].rows().stream()
                      .noneMatch(
                          row ->
                              row.endpoint() instanceof WorldStorageTransfers.External external
                                  && external.handle().connector().kind()
                                      == NodeRegistry.Kind.MACHINE_INTERFACE),
                  "Bound machine stock remains excluded from global terminal discovery");
              var handle = f.runtime.connectors().bind(f.machine).handle();
              f.runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      f.actor,
                      position(h, ROOT),
                      new WorldStorageTransfers.External(handle),
                      new WorldStorageTransfers.Digital(f.cell),
                      f.key,
                      8,
                      v -> result[0] = v);
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Scheduled local transfer completed"))
        .thenExecute(
            () ->
                h.assertTrue(
                    result[0].reason() == OperationResult.Reason.OK
                        && f.chest.getItem(0).getCount() == 11
                        && f.chest.getItem(1).getCount() == 24
                        && f.saved.registry().stock(f.cell, f.key).total() == 8,
                    "Selected slot mutation conserves32 iron and leaves excluded gold untouched"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void identicalLabelsStayLocalEvenWithInheritedStoragePermission(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(
        CABLE,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = h.getBlockEntity(CABLE);
    gateway.commission(null);
    var parentPos = new BlockPos(1, 1, 2);
    var parent = machine(h, parentPos, Direction.SOUTH);
    h.setBlock(parentPos.south(), Blocks.CHEST);
    h.startSequence()
        .thenWaitUntil(
            () -> {
              ready(h, f);
              h.assertTrue(
                  f.runtime.membership(parent.position()).status() == NetworkTopology.Status.VALID,
                  "Parent ready");
            })
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.machine.relabel(f.actor, "shared") == NetworkAuthority.Decision.ALLOWED,
                  "Child label edit accepted");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.runtime.membership(parent.position()).status()
                        == NetworkTopology.Status.VALID,
                    "Relabel rebuild completed"))
        .thenExecute(
            () ->
                h.assertTrue(
                    parent.relabel(f.actor, "shared") == NetworkAuthority.Decision.ALLOWED,
                    "Parent label edit accepted after rebuild"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.runtime
                                .machines(f.actor, parent.position(), "shared", null, 2)
                                .machines()
                                .size()
                            == 1
                        && f.runtime
                                .machines(f.actor, f.machine.position(), "shared", null, 2)
                                .machines()
                                .size()
                            == 1,
                    "Both reused labels published"))
        .thenExecute(
            () -> {
              var parentPage = f.runtime.machines(f.actor, parent.position(), "shared", null, 2);
              var childPage = f.runtime.machines(f.actor, f.machine.position(), "shared", null, 2);
              h.assertTrue(
                  parentPage.machines().getFirst().node().equals(parent.handle().id())
                      && childPage.machines().getFirst().node().equals(f.machine.handle().id()),
                  "Identical labels resolve only within each local segment");
              h.assertTrue(
                  f.runtime.machines(f.actor, parent.position(), "Shared", null, 2).decision()
                      == NetworkAuthority.Decision.EMPTY,
                  "Labels remain case-sensitive");
              var handle = f.runtime.connectors().bind(f.machine).handle();
              h.assertTrue(
                  !f.runtime
                      .connectors()
                      .allowed(
                          f.actor,
                          parent.position(),
                          handle,
                          f.key,
                          NetworkPermissions.Permission.WITHDRAW),
                  "Inherited storage visibility never widens machine endpoint access");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void queuedRelabelAndUnauthorizedEditPreventMutation(GameTestHelper h) {
    var f = setup(h);
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var handle = f.runtime.connectors().bind(f.machine).handle();
              f.runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      f.actor,
                      position(h, ROOT),
                      new WorldStorageTransfers.External(handle),
                      new WorldStorageTransfers.Digital(f.cell),
                      f.key,
                      8,
                      v -> result[0] = v);
              f.machine.relabel(f.actor, "changed");
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Stale scheduled operation returned"))
        .thenExecute(
            () ->
                h.assertTrue(
                    result[0].transfer() == null
                        && f.chest.getItem(1).getCount() == 32
                        && f.saved.registry().stock(f.cell, f.key).total() == 0,
                    "Relabel invalidates old configured handle before extraction"))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var visitor = new NetworkPermissions.Actor(UUID.randomUUID(), false);
              var before = f.machine.generation();
              h.assertTrue(
                  f.machine.relabel(visitor, "unauthorized") == NetworkAuthority.Decision.DENIED
                      && f.machine.generation() == before
                      && f.machine.label().equals("changed"),
                  "Unauthorized label cannot alter generation/policy");
            })
        .thenSucceed();
  }

  private static void utilityTransfer(GameTestHelper h, boolean fluid) {
    var f = setup(h);
    h.setBlock(PROVIDER, ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = h.getBlockEntity(PROVIDER);
    var kind = fluid ? ResourceKind.FLUID : ResourceKind.ENERGY;
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var key = fluid ? codec.fluidKey(new FluidStack(Fluids.WATER, 1000)) : ResourceKey.ENERGY;
    DeviceRegistry.Handle target;
    if (fluid) {
      StorageDriveEntity drive = h.getBlockEntity(DRIVE);
      drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_FLUID)));
      target = CellHandleData.read(drive.cell(1));
      provider.fluids.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
    } else {
      EnergyBankEntity bank = Net.in(h).bank(DRIVE, EnergyBankTier.STARTER);
      target = bank.handle();
      provider.energy.receiveEnergy(1000, false);
    }
    WorldStorageTransfers.Result[] result = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.machine.configure(f.actor, ConnectorSettings.defaults(kind));
              var handle = f.runtime.connectors().bind(f.machine).handle();
              h.assertTrue(
                  handle != null && handle.kind() == kind,
                  "Machine binds actual selected sided utility capability");
              f.runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      f.actor,
                      position(h, ROOT),
                      new WorldStorageTransfers.External(handle),
                      new WorldStorageTransfers.Digital(target),
                      key,
                      750,
                      v -> result[0] = v);
            })
        .thenWaitUntil(() -> h.assertTrue(result[0] != null, "Utility transfer completed"))
        .thenExecute(
            () ->
                h.assertTrue(
                    result[0].reason() == OperationResult.Reason.OK
                        && f.saved.registry().stock(target, key).total() == 750
                        && (fluid
                                ? provider.fluids.getFluidAmount()
                                : provider.energy.getEnergyStored())
                            == 250,
                    "Machine utility route preserves exact 1000 mB/FE and does not cross resource kind"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void machineFluidSidePreservesMillibuckets(GameTestHelper h) {
    utilityTransfer(h, true);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void machineEnergySidePreservesForgeEnergy(GameTestHelper h) {
    utilityTransfer(h, false);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void authenticatedPaperLabelAndRecipeHaveNoImplicitStock(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    h.setBlock(CABLE, NetworkContent.CABLE.get());
    var machine = machine(h, MACHINE, Direction.EAST);
    h.setBlock(PROVIDER, Blocks.CHEST);
    var paper = new ItemStack(Items.PAPER);
    paper.set(DataComponents.CUSTOM_NAME, Component.literal("Mixer"));
    h.startSequence()
        .thenWaitUntil(
            () -> h.assertTrue(machine.canUse(player), "Authenticated nearby machine owner ready"))
        .thenExecute(
            () -> {
              player.setItemInHand(InteractionHand.OFF_HAND, paper);
              h.useBlock(MACHINE, player);
              h.assertTrue(
                  machine.label().equals("Mixer") && player.getOffhandItem().getCount() == 1,
                  "Native renamed paper copies case-sensitive label without consumption");
            })
        .thenWaitUntil(() -> h.assertTrue(machine.canUse(player), "Label rebuild ready"))
        .thenExecute(
            () -> {
              player.setShiftKeyDown(true);
              h.useBlock(MACHINE, player);
              h.assertTrue(
                  machine.label().isEmpty() && player.getOffhandItem().getCount() == 1,
                  "Sneak paper clears local label without consuming tool");
              var recipe =
                  h.getLevel()
                      .getRecipeManager()
                      .byKey(
                          ResourceLocation.fromNamespaceAndPath("factorycore", "machine_interface"))
                      .orElseThrow()
                      .value();
              var costs = new HashMap<String, Integer>();
              for (var ingredient : recipe.getIngredients())
                for (var stack : ingredient.getItems())
                  costs.merge(
                      BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), 1, Integer::sum);
              h.assertTrue(
                  costs.equals(
                      Map.of(
                          "minecraft:iron_ingot",
                          4,
                          "minecraft:copper_ingot",
                          4,
                          "minecraft:redstone",
                          1)),
                  "Machine recipe uses exact selected vanilla starter materials");
              var result = recipe.getResultItem(h.getLevel().registryAccess());
              h.assertTrue(
                  result.is(MachineInterfaceContent.ITEM.get())
                      && result.get(DataComponents.CUSTOM_DATA) == null,
                  "Crafted machine casing has no copied lease or inventory");
              player.connection.disconnect(Component.literal("Machine fixture complete"));
            })
        .thenSucceed();
  }
}
