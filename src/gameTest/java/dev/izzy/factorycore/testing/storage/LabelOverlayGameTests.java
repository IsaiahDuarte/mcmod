package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.storage.LabelOverlay;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Q11: what the label overlay shows the owner, and what it shows a stranger. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class LabelOverlayGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CABLE = new BlockPos(2, 1, 1),
      PORT = new BlockPos(3, 1, 1),
      LABELLED = new BlockPos(3, 1, 2),
      END = new BlockPos(4, 1, 1),
      UNLABELLED = new BlockPos(4, 1, 2);

  private LabelOverlayGameTests() {}

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void theOwnerSeesLabelsAndMarkersAndAStrangerSeesNothing(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    var stranger = TestPlayers.mock(h);
    for (var player : Set.of(owner, stranger)) player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    net.cable(CABLE, END);
    h.setBlock(LABELLED, Blocks.FURNACE);
    h.setBlock(UNLABELLED, Blocks.FURNACE);
    var port = net.machine(PORT, Direction.SOUTH);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT, END))
        .thenExecute(
            () ->
                h.assertTrue(
                    port.applySettings(
                            net.owner(), ConnectorSettings.defaults(ResourceKind.ITEM), "oven")
                        == NetworkAuthority.Decision.ALLOWED,
                    "Labelled"))
        .thenWaitUntil(() -> net.assertValid(PORT, END))
        .thenExecute(
            () -> {
              var labelled = h.absolutePos(LABELLED).asLong();
              var unlabelled = h.absolutePos(UNLABELLED).asLong();
              var seen = LabelOverlay.snapshot(owner);
              h.assertTrue(
                  seen.contains(new LabelOverlay.Entry(labelled, "oven")),
                  "The label floats over the machine: " + seen);
              h.assertTrue(
                  seen.contains(new LabelOverlay.Entry(unlabelled, "")),
                  "The unlabelled machine on the cable is marked");
              h.assertTrue(
                  seen.stream().noneMatch(entry -> entry.pos() == h.absolutePos(PORT).asLong()),
                  "The port itself is not listed");
              var foreign = LabelOverlay.snapshot(stranger);
              h.assertTrue(
                  foreign.stream()
                      .noneMatch(entry -> entry.pos() == labelled || entry.pos() == unlabelled),
                  "Another player's network sends nothing: " + foreign);
              for (var player : Set.of(owner, stranger))
                player.connection.disconnect(Component.literal("Overlay fixture complete"));
            })
        .thenSucceed();
  }
}
