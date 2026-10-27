package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.storage.LabelOverlay;
import dev.izzy.factorycore.platform.storage.MachineInspection;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Q12: the Label Tool's network view and machine inspection. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class NetworkViewGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      CABLE = new BlockPos(2, 1, 1),
      GATEWAY = new BlockPos(3, 1, 1),
      CHILD = new BlockPos(4, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      PORT = new BlockPos(2, 1, 1),
      FURNACE = new BlockPos(2, 1, 2);

  private NetworkViewGameTests() {}

  private static LabelOverlay.Entry at(java.util.List<LabelOverlay.Entry> entries, long pos) {
    return entries.stream().filter(entry -> entry.pos() == pos).findFirst().orElse(null);
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void twoBranchesAreColouredApartAndAStrangerSeesNothing(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    var stranger = TestPlayers.mock(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.cable(CABLE);
    h.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = h.getBlockEntity(GATEWAY);
    gateway.commission(null);
    net.cable(CHILD);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(CABLE, CHILD))
        .thenExecute(
            () -> {
              var seen = LabelOverlay.network(owner, h.absolutePos(CABLE));
              var root = at(seen, h.absolutePos(CABLE).asLong());
              var child = at(seen, h.absolutePos(CHILD).asLong());
              var node = at(seen, h.absolutePos(GATEWAY).asLong());
              h.assertTrue(
                  root != null && child != null && node != null, "Both branches listed: " + seen);
              h.assertTrue(root.kind() == LabelOverlay.Kind.CABLE, "A cable is a cable");
              h.assertTrue(node.kind() == LabelOverlay.Kind.GATEWAY, "The gateway is marked");
              h.assertTrue(
                  root.branch() != child.branch(),
                  "The branches are coloured apart: " + root + " " + child);
              h.assertTrue(
                  at(seen, h.absolutePos(ROOT).asLong()).kind() == LabelOverlay.Kind.DEVICE,
                  "The controller is a device");
              h.assertTrue(
                  LabelOverlay.network(stranger, h.absolutePos(CABLE)).isEmpty(),
                  "Another player's network sends nothing");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void inspectingAFurnaceWritesItsMoveLines(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    var stranger = TestPlayers.mock(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(FURNACE, Blocks.FURNACE);
    var port = net.machine(PORT, Direction.SOUTH);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () ->
                h.assertTrue(
                    port.applySettings(
                            net.owner(), ConnectorSettings.defaults(ResourceKind.ITEM), "oven")
                        == NetworkAuthority.Decision.ALLOWED,
                    "Labelled"))
        .thenWaitUntil(() -> net.assertValid(PORT))
        .thenExecute(
            () -> {
              var view = MachineInspection.inspect(owner, h.absolutePos(FURNACE));
              h.assertTrue(view != null, "The owner may inspect");
              var lines = view.lines();
              h.assertTrue(view.title().startsWith("Furnace at "), "Titled: " + view.title());
              h.assertTrue(lines.getFirst().contains("oven"), "Labels first: " + lines);
              for (var expected :
                  new String[] {
                    "side up: 1 item slots",
                    "move everything from storage to machines(\"oven\") side up",
                    "move everything from machines(\"oven\") side up to storage",
                    "side down: 2 item slots",
                    "move everything from machines(\"oven\") side down to storage",
                    "side north: 1 item slots"
                  }) h.assertTrue(lines.contains(expected), "Has '" + expected + "': " + lines);
              h.assertTrue(
                  MachineInspection.inspect(stranger, h.absolutePos(FURNACE)) == null,
                  "No VIEW, no inspection");
              h.assertTrue(
                  MachineInspection.inspect(owner, h.absolutePos(new BlockPos(5, 1, 5))) == null,
                  "Air is not a machine");
            })
        .thenSucceed();
  }
}
