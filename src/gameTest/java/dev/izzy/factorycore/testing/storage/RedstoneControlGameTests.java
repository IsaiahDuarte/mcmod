package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.core.storage.RedstoneGate;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.RedstoneControlled;
import dev.izzy.factorycore.platform.storage.RedstoneMenu;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * C2: a Machine or Buffer preset obeys its Interface's redstone setting. A redstone block placed
 * beside the Interface is the input; it is removed again to turn the input off.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class RedstoneControlGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      PORT = new BlockPos(1, 1, 2),
      CHEST = new BlockPos(1, 1, 3),
      POWER = new BlockPos(0, 1, 2);

  /** Longer than a denied retry after a network rebuild, so "nothing moved" is meaningful. */
  private static final int HOLD = 60;

  private RedstoneControlGameTests() {}

  private static int chest(GameTestHelper h) {
    return ((ChestBlockEntity) h.getBlockEntity(CHEST)).countItem(Items.COBBLESTONE);
  }

  private static void power(GameTestHelper h, boolean on) {
    h.setBlock(POWER, on ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
  }

  private static <E extends RedstoneControlled> void control(E owner, RedstoneGate.Control c) {
    owner.redstone().control(c);
    owner.redstoneWake();
  }

  private static void ready(GameTestHelper h) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer()).membership(Net.in(h).at(PORT)).status()
            == NetworkTopology.Status.VALID,
        "Interface on a valid network");
  }

  /** A Machine Interface importing cobblestone one item per move from a chest it faces. */
  private static MachineInterfaceEntity machine(GameTestHelper h, Net net, int items) {
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(CHEST, Blocks.CHEST);
    ((ChestBlockEntity) h.getBlockEntity(CHEST))
        .setItem(0, new ItemStack(Items.COBBLESTONE, items));
    return net.machine(PORT, Direction.SOUTH);
  }

  private static void deploy(Net net, MachineInterfaceEntity machine) {
    machine.configure(
        net.owner(),
        new ConnectorSettings(ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 27, 1));
    machine.deployPreset(
        net.owner(), MachinePresets.Mode.IMPORT, ResourceFilter.all(ResourceKind.ITEM), 0);
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void poweredPresetRunsOnlyWhileTheInputIsOn(GameTestHelper h) {
    var net = Net.in(h);
    var machine = machine(h, net, 64);
    int[] seen = {0};
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              control(machine, RedstoneGate.Control.POWERED);
              deploy(net, machine);
            })
        .thenIdle(HOLD)
        .thenExecute(
            () ->
                h.assertTrue(
                    chest(h) == 64
                        && WorldNetworks.get(h.getLevel().getServer())
                                .machinePresets()
                                .status(machine)
                                .reason()
                            == OperationResult.Reason.REDSTONE,
                    "Unpowered: nothing moves and the reason is redstone; chest " + chest(h)))
        .thenExecute(() -> power(h, true))
        .thenWaitUntil(() -> h.assertTrue(chest(h) <= 60, "Powered: it imports"))
        .thenExecute(() -> power(h, false))
        .thenIdle(20)
        .thenExecute(() -> seen[0] = chest(h))
        .thenIdle(HOLD)
        .thenExecute(
            () ->
                h.assertTrue(
                    chest(h) == seen[0] && seen[0] > 0,
                    "Unpowered again: it stops (" + seen[0] + " then " + chest(h) + ")"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void pulseMakesOneMovePerRisingEdgeAndHoldingDoesNotRepeat(GameTestHelper h) {
    var net = Net.in(h);
    var machine = machine(h, net, 16);
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              control(machine, RedstoneGate.Control.PULSE);
              deploy(net, machine);
            })
        .thenIdle(HOLD)
        .thenExecute(() -> h.assertTrue(chest(h) == 16, "No pulse: nothing moves"))
        .thenExecute(() -> power(h, true))
        .thenWaitUntil(() -> h.assertTrue(chest(h) == 15, "First pulse: one item"))
        .thenIdle(HOLD)
        .thenExecute(
            () ->
                h.assertTrue(
                    chest(h) == 15, "Held high: the pulse does not repeat; chest " + chest(h)))
        .thenExecute(() -> power(h, false))
        .thenIdle(5)
        .thenExecute(() -> power(h, true))
        .thenWaitUntil(() -> h.assertTrue(chest(h) == 14, "Second pulse: one more item"))
        .thenIdle(HOLD)
        .thenExecute(
            () -> {
              h.assertTrue(chest(h) == 14, "Still held: no repeat; chest " + chest(h));
              var saved = machine.saveWithoutMetadata(h.getLevel().registryAccess());
              h.assertTrue(
                  saved.contains(RedstoneControlled.FIELD)
                      && saved
                          .getCompound(RedstoneControlled.FIELD)
                          .getString("Control")
                          .equals("PULSE")
                      && saved.getCompound(RedstoneControlled.FIELD).getBoolean("Powered"),
                  "The setting and input are saved with the Interface");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void unpoweredBufferImportsOnlyWhileOffAndEditIsRequired(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    owner.moveTo(Vec3.atCenterOf(h.absolutePos(PORT)));
    var guest = TestPlayers.mock(h);
    TestPlayers.connect(guest);
    guest.moveTo(Vec3.atCenterOf(h.absolutePos(PORT)));
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    power(h, true);
    BufferedInterfaceEntity buffer = net.buffer(PORT);
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              h.assertTrue(
                  !RedstoneMenu.click(guest, buffer, RedstoneMenu.BUTTON + 2)
                      && buffer.redstone().control() == RedstoneGate.Control.IGNORE,
                  "Without EDIT the setting is refused");
              h.assertTrue(
                  RedstoneMenu.click(owner, buffer, RedstoneMenu.BUTTON + 2)
                      && buffer.redstone().control() == RedstoneGate.Control.UNPOWERED
                      && buffer.redstone().powered(),
                  "The owner sets Unpowered; the input reads on");
              buffer.configure(
                  new NetworkPermissions.Actor(owner.getUUID(), false),
                  dev.izzy.factorycore.core.storage.BufferRegistry.Mode.IMPORT);
              buffer.capability().insertItem(0, new ItemStack(Items.COBBLESTONE, 8), false);
            })
        .thenIdle(HOLD)
        .thenExecute(
            () ->
                h.assertTrue(
                    buffer.inventory().total(net.key(Items.COBBLESTONE)) == 8,
                    "Powered: the buffer keeps its items"))
        .thenExecute(() -> power(h, false))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    buffer.inventory().total(net.key(Items.COBBLESTONE)) == 0,
                    "Unpowered: the buffer imports"))
        .thenSucceed();
  }
}
