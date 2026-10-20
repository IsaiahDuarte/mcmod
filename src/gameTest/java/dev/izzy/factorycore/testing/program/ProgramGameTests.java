package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.CraftingUnitEntity;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.MachineInterfaceContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ProgramGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CONTROLLER = new BlockPos(1, 1, 3),
      MACHINE = new BlockPos(2, 1, 1),
      CHEST = new BlockPos(3, 1, 1),
      UNIT = new BlockPos(1, 1, 0);

  private record Fixture(
      NetworkPermissions.Actor owner,
      DeviceSavedData saved,
      DeviceRegistry.Handle cell,
      ProgramControllerEntity controller,
      MachineInterfaceEntity machine,
      ChestBlockEntity chest,
      PlatformResourceCodec codec) {
    long stock(Item item) {
      return saved.registry().stock(cell, codec.itemKey(new ItemStack(item))).total();
    }

    int chestCount(Item item) {
      int total = 0;
      for (int slot = 0; slot < chest.getContainerSize(); slot++)
        if (chest.getItem(slot).is(item)) total += chest.getItem(slot).getCount();
      return total;
    }
  }

  private ProgramGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.id());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    h.setBlock(
        MACHINE,
        MachineInterfaceContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    MachineInterfaceEntity machine = h.getBlockEntity(MACHINE);
    machine.place(new ItemStack(MachineInterfaceContent.ITEM.get()));
    h.setBlock(CHEST, Blocks.CHEST);
    return new Fixture(
        owner,
        DeviceSavedData.get(h.getLevel().getServer()),
        CellHandleData.read(drive.cell(0)),
        h.getBlockEntity(CONTROLLER),
        machine,
        h.getBlockEntity(CHEST),
        new PlatformResourceCodec(h.getLevel().registryAccess()));
  }

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos pos) {
    return Positions.of(h.getLevel(), h.absolutePos(pos));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    var networks = WorldNetworks.get(h.getLevel().getServer());
    h.assertTrue(
        networks.membership(position(h, CONTROLLER)).status() == NetworkTopology.Status.VALID
            && networks.membership(position(h, MACHINE)).status() == NetworkTopology.Status.VALID
            && f.saved
                .registry()
                .stock(f.cell, f.codec.itemKey(new ItemStack(Items.STONE)))
                .loaded(),
        "Network ready");
  }

  private static void label(GameTestHelper h, Fixture f, String label) {
    h.assertTrue(
        f.machine.applySettings(
                f.owner,
                new ConnectorSettings(
                    ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 27, 64),
                label)
            == NetworkAuthority.Decision.ALLOWED,
        "Machine labeled " + label);
  }

  private static WorldPrograms programs(GameTestHelper h) {
    return WorldPrograms.get(h.getLevel().getServer());
  }

  private static void deploy(GameTestHelper h, Fixture f, String source) {
    f.controller.draft(source);
    var diagnostics = programs(h).deploy(f.owner, f.controller);
    h.assertTrue(diagnostics.isEmpty(), "Deployed: " + diagnostics);
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void moveRuleEmptiesLabeledMachineIntoStorageAndSpendsPower(GameTestHelper h) {
    var f = setup(h);
    f.chest.setItem(0, new ItemStack(Items.IRON_INGOT, 20));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f, "out"))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.controller.energyStorage().receiveEnergy(1000, false);
              deploy(
                  h,
                  f,
                  "every 10 ticks as \"empty\" {\n  move item(\"iron_ingot\") from machines(\"out\") to storage limit 64\n}");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.stock(Items.IRON_INGOT) == 20 && f.chestCount(Items.IRON_INGOT) == 0,
                    "Ingots moved"))
        .thenExecute(
            () ->
                h.assertTrue(
                    f.controller.energy() <= 1000 - WorldPrograms.BASE_COST
                        && f.controller.state() == ProgramControllerEntity.State.RUNNING,
                    "Each invocation costs FE; program keeps running"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void keepRequestsOnlyTheDeficitAcrossInvocations(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              label(h, f, "fuel");
              f.chest.setItem(5, new ItemStack(Items.COAL, 4));
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.controller.energyStorage().receiveEnergy(1000, false);
              // Relabeling rebuilds the network; store the coal once the cell is available again.
              h.assertTrue(
                  f.saved
                          .registry()
                          .insert(f.cell, f.codec.itemKey(new ItemStack(Items.COAL)), 50)
                          .amount()
                      == 50,
                  "Coal stored");
              deploy(
                  h,
                  f,
                  "every 10 ticks { keep machines(\"fuel\") stocked with 16 of item(\"coal\") from storage }");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.chestCount(Items.COAL) == 16,
                    "Machine stocked to 16: "
                        + f.chestCount(Items.COAL)
                        + " "
                        + f.controller.logs()
                        + " "
                        + programs(h).status(f.controller)
                        + " "
                        + f.controller.state()
                        + " "
                        + f.controller.message()))
        .thenIdle(60)
        .thenExecute(
            () ->
                h.assertTrue(
                    f.chestCount(Items.COAL) == 16 && f.stock(Items.COAL) == 38,
                    "Later invocations request nothing more: 4 + 12 moved, 50 - 12 left"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void unpoweredControllerWaitsWithoutMovingAnything(GameTestHelper h) {
    var f = setup(h);
    f.chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> label(h, f, "out"))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () ->
                deploy(
                    h,
                    f,
                    "every 10 ticks { move item(\"iron_ingot\") from machines(\"out\") to storage }"))
        .thenIdle(40)
        .thenExecute(
            () -> {
              h.assertTrue(
                  programs(h).status(f.controller).equals("no_power")
                      && f.chestCount(Items.IRON_INGOT) == 5,
                  "No FE means no invocation");
              f.controller.energyStorage().receiveEnergy(100, false);
            })
        .thenWaitUntil(
            () -> h.assertTrue(f.stock(Items.IRON_INGOT) == 5, "Power arrives and the rule runs"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void deployRequiresPermissionAndValidSourceAndTrapsStopTheProgram(
      GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.controller.draft("every 10 ticks { log \"hi\" }");
              var stranger = new NetworkPermissions.Actor(UUID.randomUUID(), false);
              h.assertTrue(
                  !programs(h).deploy(stranger, f.controller).isEmpty()
                      && f.controller.deployedSource() == null,
                  "A player without Deploy cannot deploy");
              f.controller.draft("every 10 ticks { bogus }");
              var errors = programs(h).deploy(f.owner, f.controller);
              h.assertTrue(
                  !errors.isEmpty()
                      && errors.getFirst().line() == 1
                      && f.controller.deployedSource() == null,
                  "Compile errors are reported and nothing deploys");
              f.controller.energyStorage().receiveEnergy(1000, false);
              var flood = new StringBuilder("every 10 ticks as \"flood\" {\n");
              for (int i = 0; i < 300; i++) flood.append(" log \"x\"\n");
              deploy(h, f, flood.append("}").toString());
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.controller.state() == ProgramControllerEntity.State.TRAPPED
                        && f.controller.message().contains("flood")
                        && f.controller.logs().stream().noneMatch(line -> line.equals("flood: x")),
                    "Exceeding the command bound traps without publishing any command"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void revokedDeployGrantPausesAndCraftCommandsRequestJobs(GameTestHelper h) {
    var f = setup(h);
    var member = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    h.setBlock(UNIT, CraftingContent.UNIT.get());
    CraftingUnitEntity unit = h.getBlockEntity(UNIT);
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(Items.OAK_LOG));
    var pattern = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    pattern.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(Items.OAK_PLANKS, 4), List.of()));
    unit.patterns().insertItem(0, pattern, false);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var network =
                  WorldNetworks.get(h.getLevel().getServer())
                      .membership(position(h, CONTROLLER))
                      .scope()
                      .network();
              f.saved
                  .network(network)
                  .setGrants(
                      f.owner,
                      member.id(),
                      EnumSet.of(
                          NetworkPermissions.Permission.VIEW,
                          NetworkPermissions.Permission.DEPLOY,
                          NetworkPermissions.Permission.CRAFT));
              f.saved.registry().insert(f.cell, f.codec.itemKey(new ItemStack(Items.OAK_LOG)), 2);
              f.controller.energyStorage().receiveEnergy(1000, false);
              f.controller.draft(
                  "every 10 ticks as \"planks\" { if stock(item(\"oak_planks\")) < 4 { craft 4 of item(\"oak_planks\") } }");
              h.assertTrue(programs(h).deploy(member, f.controller).isEmpty(), "Member deploys");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.stock(Items.OAK_PLANKS) == 4,
                    "Program's craft request produced planks: "
                        + f.controller.logs()
                        + " "
                        + programs(h).status(f.controller)
                        + " "
                        + f.controller.state()))
        .thenIdle(30)
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.stock(Items.OAK_PLANKS) == 4 && f.stock(Items.OAK_LOG) == 1,
                  "Stock condition stops further requests");
              var network =
                  WorldNetworks.get(h.getLevel().getServer())
                      .membership(position(h, CONTROLLER))
                      .scope()
                      .network();
              f.saved
                  .network(network)
                  .setGrants(
                      f.owner,
                      member.id(),
                      EnumSet.of(
                          NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.CRAFT));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.controller.state() == ProgramControllerEntity.State.PAUSED,
                    "Losing Deploy pauses the deployment before its next invocation"))
        .thenExecute(
            () -> {
              var saved = f.controller.saveWithoutMetadata(h.getLevel().registryAccess());
              h.setBlock(CONTROLLER, Blocks.AIR);
              h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
              ProgramControllerEntity reloaded = h.getBlockEntity(CONTROLLER);
              reloaded.loadWithComponents(saved, h.getLevel().registryAccess());
              h.assertTrue(
                  reloaded.state() == ProgramControllerEntity.State.PAUSED
                      && member.id().equals(reloaded.deployer())
                      && reloaded.program() != null
                      && reloaded.program().rules().getFirst().key().equals("planks"),
                  "Deployment, deployer and state persist and recompile on load");
            })
        .thenSucceed();
  }
}
