package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.storage.CablePortBlock;
import dev.izzy.factorycore.platform.storage.LabelOverlay;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Label Tool used on a machine through the server's real interaction path, where a furnace's
 * own use (opening its screen) runs before an item's {@code useOn} unless the item goes first.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class LabelToolUseGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(1, 1, 2),
      CABLE = new BlockPos(2, 1, 1),
      FURNACE = new BlockPos(2, 1, 2);

  private LabelToolUseGameTests() {}

  private static ServerPlayer setup(GameTestHelper h, Net[] net) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    net[0] = Net.in(h, player.getUUID());
    net[0].controller(ROOT);
    net[0].drive(DRIVE, CellTier.STARTER_ITEM);
    net[0].cable(CABLE);
    h.setBlock(FURNACE, Blocks.FURNACE);
    var tool = new ItemStack(LabelToolContent.ITEM.get());
    tool.set(LabelToolContent.DATA.get(), new LabelToolContent.Data("oven", new CompoundTag()));
    player.setItemInHand(InteractionHand.MAIN_HAND, tool);
    return player;
  }

  private static void use(GameTestHelper h, ServerPlayer player) {
    var at = h.absolutePos(FURNACE);
    player.gameMode.useItemOn(
        player,
        h.getLevel(),
        player.getMainHandItem(),
        InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
  }

  /** Regression (H5): the furnace's screen opened instead, and nothing was labelled. */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void usingTheToolOnAFurnaceLabelsItInsteadOfOpeningIt(GameTestHelper h) {
    var net = new Net[1];
    var player = setup(h, net);
    h.startSequence()
        .thenWaitUntil(() -> net[0].assertValid(CABLE))
        .thenExecute(
            () -> {
              use(h, player);
              h.assertTrue(
                  player.containerMenu == player.inventoryMenu, "The furnace screen did not open");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    h.getBlockState(CABLE).getBlock() instanceof CablePortBlock
                        && h.getBlockEntity(CABLE) instanceof MachineInterfaceEntity port
                        && port.label().equals("oven"),
                    "The furnace is labelled"))
        .thenExecute(
            () -> player.connection.disconnect(Component.literal("Label use fixture complete")))
        .thenSucceed();
  }

  /** Q12: in the network view the same use inspects the furnace and changes nothing. */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void inTheNetworkViewTheUseInspectsInstead(GameTestHelper h) {
    var net = new Net[1];
    var player = setup(h, net);
    h.startSequence()
        .thenWaitUntil(() -> net[0].assertValid(CABLE))
        .thenExecute(
            () -> {
              LabelOverlay.setMode(player, LabelOverlay.Mode.NETWORK);
              use(h, player);
              h.assertTrue(
                  player.containerMenu == player.inventoryMenu, "The furnace screen did not open");
            })
        .thenIdle(10)
        .thenExecute(
            () -> {
              h.assertTrue(
                  !(h.getBlockState(CABLE).getBlock() instanceof CablePortBlock),
                  "Inspecting labels nothing");
              LabelOverlay.setMode(player, LabelOverlay.Mode.LABELS);
              player.connection.disconnect(Component.literal("Label use fixture complete"));
            })
        .thenSucceed();
  }
}
