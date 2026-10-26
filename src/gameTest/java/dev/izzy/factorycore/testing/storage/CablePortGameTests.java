package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.storage.CablePortBlock;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * H5 (ADR 0065): the Label Tool labels a machine that touches a cable by making that cable a port
 * aimed at it; the network still runs through the port; programs reach the machine on any side;
 * breaking an idle port leaves a plain cable and retires its lease.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CablePortGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CABLE = new BlockPos(2, 1, 1),
      BEYOND = new BlockPos(3, 1, 1),
      CONTROLLER = new BlockPos(4, 1, 1),
      MACHINE = new BlockPos(2, 1, 2);

  private record Fixture(ServerPlayer player, Net net, ItemStack tool) {}

  private CablePortGameTests() {}

  /** Root, drive, cable, cable, Program Controller in a line; a furnace touches the first cable. */
  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    net.cable(CABLE, BEYOND);
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    h.setBlock(MACHINE, Blocks.FURNACE);
    var tool = new ItemStack(LabelToolContent.ITEM.get());
    tool.set(LabelToolContent.DATA.get(), new LabelToolContent.Data("oven", new CompoundTag()));
    player.setItemInHand(InteractionHand.MAIN_HAND, tool);
    return new Fixture(player, net, tool);
  }

  private static void labelMachine(GameTestHelper h, Fixture f) {
    var pos = h.absolutePos(MACHINE);
    f.tool
        .getItem()
        .useOn(
            new UseOnContext(
                f.player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
  }

  private static MachineInterfaceEntity port(GameTestHelper h) {
    var state = h.getBlockState(CABLE);
    h.assertTrue(state.getBlock() instanceof CablePortBlock, "The cable became a cable port");
    h.assertTrue(
        state.getValue(BlockStateProperties.FACING) == Direction.SOUTH,
        "The port is aimed at the machine");
    return h.getBlockEntity(CABLE);
  }

  private static void end(Fixture f) {
    f.player.connection.disconnect(Component.literal("Cable port fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void labelToolMakesTheTouchingCableALabelledPortThatStillCarriesTheNetwork(
      GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> f.net.assertValid(CABLE, CONTROLLER))
        .thenExecute(() -> labelMachine(h, f))
        .thenWaitUntil(
            () -> {
              var port = port(h);
              h.assertTrue(port.label().equals("oven"), "The machine is labelled: " + port.label());
              f.net.assertValid(CABLE, CONTROLLER);
              h.assertTrue(
                  h.getBlockState(BEYOND).getBlock() instanceof NetworkCableBlock,
                  "Only the touching cable changed");
            })
        .thenExecute(
            () -> {
              // Labelling the machine again relabels the same port instead of making another.
              f.tool.set(
                  LabelToolContent.DATA.get(),
                  new LabelToolContent.Data("kiln", new CompoundTag()));
              labelMachine(h, f);
              h.assertTrue(port(h).label().equals("kiln"), "Relabelled in place");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aProgramPullsTheFurnaceOutputThroughThePortOnAnySide(GameTestHelper h) {
    var f = setup(h);
    var actor = new NetworkPermissions.Actor(f.player.getUUID(), false);
    var cell = f.net.cell(DRIVE, 0);
    h.startSequence()
        .thenWaitUntil(() -> f.net.assertValid(CABLE, CONTROLLER))
        .thenExecute(
            () -> {
              FurnaceBlockEntity furnace = h.getBlockEntity(MACHINE);
              furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 9));
              furnace.setItem(1, new ItemStack(Items.COAL, 4));
              labelMachine(h, f);
            })
        .thenWaitUntil(() -> h.assertTrue(port(h).label().equals("oven"), "Labelled"))
        .thenWaitUntil(() -> f.net.assertValid(CABLE, CONTROLLER))
        .thenExecute(
            () -> {
              ProgramControllerEntity controller = h.getBlockEntity(CONTROLLER);
              controller.energyStorage().receiveEnergy(1000, false);
              // The port touches the furnace's north face; "side down" reaches its output.
              controller.draft(
                  "every 10 ticks { move everything from machines(\"oven\") side down to storage }");
              var diagnostics =
                  WorldPrograms.get(h.getLevel().getServer()).deploy(actor, controller);
              h.assertTrue(diagnostics.isEmpty(), "Deployed: " + diagnostics);
            })
        .thenWaitUntil(
            () -> {
              FurnaceBlockEntity furnace = h.getBlockEntity(MACHINE);
              h.assertTrue(furnace.getItem(2).isEmpty(), "The output was emptied");
              h.assertTrue(
                  f.net.saved().registry().stock(cell, f.net.key(Items.IRON_INGOT)).total() == 9,
                  "Every ingot reached storage");
              h.assertTrue(
                  furnace.getItem(1).getCount() == 4,
                  "The fuel stays: the bottom face only offers the output");
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void breakingAnIdlePortLeavesACableAndRetiresTheLease(GameTestHelper h) {
    var f = setup(h);
    var lease = new java.util.UUID[1];
    h.startSequence()
        .thenWaitUntil(() -> f.net.assertValid(CABLE, CONTROLLER))
        .thenExecute(() -> labelMachine(h, f))
        .thenWaitUntil(() -> h.assertTrue(port(h).label().equals("oven"), "Labelled"))
        .thenWaitUntil(() -> f.net.assertValid(CABLE, CONTROLLER))
        .thenExecute(
            () -> {
              var port = port(h);
              lease[0] = port.handle().id();
              // A preset on the port is forgotten with it (the rule an Interface switch follows).
              port.deployPreset(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  dev.izzy.factorycore.core.storage.MachinePresets.Mode.PAUSED,
                  dev.izzy.factorycore.core.resource.ResourceFilter.all(
                      dev.izzy.factorycore.core.resource.ResourceKind.ITEM),
                  0);
              h.getLevel().destroyBlock(h.absolutePos(CABLE), true);
            })
        .thenWaitUntil(
            () -> {
              var drops =
                  h.getLevel()
                      .getEntitiesOfClass(
                          ItemEntity.class, new AABB(h.absolutePos(CABLE)).inflate(2));
              h.assertTrue(
                  drops.size() == 1
                      && drops.getFirst().getItem().is(NetworkContent.CABLE_ITEM.get()),
                  "A plain cable drops: " + drops);
              h.assertFalse(
                  f.net.saved().nodes().contains(lease[0]), "The idle port's lease is retired");
              h.assertTrue(
                  f.net.saved().machinePresets().get(lease[0]) == null
                      && !f.net.saved().machinePresets().configurations().containsKey(lease[0]),
                  "Its preset and configuration fence are forgotten");
              h.assertTrue(
                  WorldNetworks.get(h.getLevel().getServer())
                          .membership(Positions.of(h.getLevel(), h.absolutePos(CONTROLLER)))
                          .status()
                      != NetworkTopology.Status.VALID,
                  "With the cable gone the controller is cut off");
              end(f);
            })
        .thenSucceed();
  }
}
