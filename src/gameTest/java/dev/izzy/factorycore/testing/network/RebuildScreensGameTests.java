package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Q119: device screens stay open while a cable placed elsewhere rebuilds their network. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class RebuildScreensGameTests {
  private static final BlockPos ROOT = new BlockPos(2, 1, 2),
      DRIVE = new BlockPos(3, 1, 2),
      BUFFER = new BlockPos(2, 1, 3),
      MACHINE = new BlockPos(1, 1, 2),
      CHEST = new BlockPos(0, 1, 2),
      CRAFTER = new BlockPos(2, 1, 1),
      PROGRAM = new BlockPos(3, 2, 2),
      STORAGE = new BlockPos(1, 2, 2),
      STORAGE_CHEST = new BlockPos(0, 2, 2);

  private RebuildScreensGameTests() {}

  @GameTest(template = "empty", timeoutTicks = 900)
  public static void deviceScreensStayOpenThroughARebuild(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)).add(0, 1, 0));
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    net.drive(DRIVE, CellTier.STARTER_ITEM);
    net.buffer(BUFFER);
    h.setBlock(CHEST, Blocks.CHEST);
    net.machine(MACHINE, Direction.WEST);
    h.setBlock(
        CRAFTER,
        CraftingContent.CRAFTER
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.NORTH));
    net.cable(new BlockPos(2, 2, 2));
    h.setBlock(PROGRAM, ProgramContent.BLOCK.get());
    h.setBlock(STORAGE_CHEST, Blocks.CHEST);
    h.setBlock(STORAGE, InterfaceContent.state(InterfaceKind.STORAGE, Direction.WEST));
    ConnectorEntity storage = h.getBlockEntity(STORAGE);
    storage.place(new ItemStack(InterfaceContent.ITEM.get()));
    var screens = List.of(ROOT, DRIVE, BUFFER, MACHINE, CRAFTER, PROGRAM, STORAGE);
    var closed = new ArrayList<String>();
    String[] open = {""};
    GameTestSequence sequence =
        h.startSequence()
            .thenWaitUntil(() -> net.assertValid(ROOT, DRIVE, BUFFER, PROGRAM, STORAGE));
    for (int i = 0; i < screens.size(); i++) {
      var at = screens.get(i);
      var cable = new BlockPos(2, 3 + i, 2);
      sequence =
          sequence
              .thenIdle(20)
              .thenExecute(
                  () -> {
                    player.closeContainer();
                    h.useBlock(at, player);
                    AbstractContainerMenu menu = player.containerMenu;
                    h.assertFalse(menu instanceof InventoryMenu, "A screen opens at " + at);
                    open[0] = menu.getClass().getSimpleName();
                    h.setBlock(cable, NetworkContent.CABLE.get());
                  })
              .thenExecuteFor(
                  40,
                  () -> {
                    player.containerMenu.broadcastChanges();
                    if (player.containerMenu instanceof InventoryMenu && !open[0].isEmpty()) {
                      closed.add(open[0]);
                      open[0] = "";
                    }
                  });
    }
    sequence
        .thenExecute(
            () -> {
              h.assertTrue(closed.isEmpty(), "Screens closed by a rebuild: " + closed);
              player.connection.disconnect(Component.literal("Rebuild screens complete"));
            })
        .thenSucceed();
  }
}
