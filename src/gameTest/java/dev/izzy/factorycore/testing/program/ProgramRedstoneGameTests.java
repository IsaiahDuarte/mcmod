package dev.izzy.factorycore.testing.program;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import dev.izzy.factorycore.testing.Net;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * D6 (ADR 0062): a program reads a Machine Interface's redstone input and drives a labelled
 * Signal-mode Interface; stopping it hands the Interface back to its own rule at once.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ProgramRedstoneGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CONTROLLER = new BlockPos(1, 1, 3),
      PORT = new BlockPos(2, 1, 1),
      CHEST = new BlockPos(3, 1, 1),
      POWER = new BlockPos(2, 2, 1),
      SIGNAL = new BlockPos(1, 1, 4),
      LAMP = new BlockPos(1, 2, 4);

  private static final String RELAY =
      """
      every 10 ticks as "relay" {
        if redstone(machines("button")) { emit redstone 15 on machines("lamp") }
        else { emit redstone 0 on machines("lamp") }
      }
      """;

  private ProgramRedstoneGameTests() {}

  private static boolean lit(GameTestHelper h) {
    return h.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT);
  }

  @GameTest(template = "empty", timeoutTicks = 800)
  public static void aProgramRelaysRedstoneAndLetsGoWhenStopped(GameTestHelper h) {
    var owner = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var net = Net.in(h, owner.id());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(CONTROLLER, ProgramContent.BLOCK.get());
    h.setBlock(CHEST, Blocks.CHEST);
    MachineInterfaceEntity port = net.machine(PORT, Direction.EAST);
    h.setBlock(SIGNAL, InterfaceContent.state(InterfaceKind.SIGNAL, Direction.NORTH));
    SignalInterfaceEntity signal = h.getBlockEntity(SIGNAL);
    signal.place(owner.id());
    signal.label("lamp");
    h.setBlock(LAMP, Blocks.REDSTONE_LAMP);
    ProgramControllerEntity controller = h.getBlockEntity(CONTROLLER);
    var programs = WorldPrograms.get(h.getLevel().getServer());
    var networks = WorldNetworks.get(h.getLevel().getServer());
    Runnable ready =
        () -> {
          for (var position : List.of(CONTROLLER, PORT, SIGNAL))
            h.assertTrue(
                networks.membership(Positions.of(h.getLevel(), h.absolutePos(position))).status()
                    == NetworkTopology.Status.VALID,
                "Network ready");
        };
    h.startSequence()
        .thenWaitUntil(ready)
        .thenExecute(
            () ->
                h.assertTrue(
                    port.applySettings(
                            owner,
                            new ConnectorSettings(
                                ResourceKind.ITEM,
                                ConnectorSettings.Access.READ_WRITE,
                                0,
                                0,
                                27,
                                64),
                            "button")
                        == NetworkAuthority.Decision.ALLOWED,
                    "Machine Interface labeled"))
        .thenWaitUntil(ready)
        .thenExecute(
            () -> {
              controller.energyStorage().receiveEnergy(1000, false);
              controller.draft(RELAY);
              var diagnostics = programs.deploy(owner, controller);
              h.assertTrue(diagnostics.isEmpty(), "Deployed: " + diagnostics);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    signal.reason().equals("program") && signal.power() == 0 && !lit(h),
                    "Unpowered input: the program holds the lamp off: "
                        + signal.reason()
                        + " "
                        + controller.logs()))
        .thenExecute(() -> h.setBlock(POWER, Blocks.REDSTONE_BLOCK))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    signal.power() == 15 && lit(h), "Powered input: the program lights the lamp"))
        .thenExecute(
            () ->
                h.assertTrue(
                    programs.control(owner, controller, ProgramControllerEntity.State.STOPPED),
                    "Stopped"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    signal.reason().equals("unset") && signal.power() == 0 && !lit(h),
                    "Stopping hands the Interface back to its own (unset) rule: "
                        + signal.reason()))
        .thenSucceed();
  }
}
