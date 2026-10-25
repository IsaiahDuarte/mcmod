package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import dev.izzy.factorycore.platform.storage.MachineInterfaceEntity;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * D8 (PD3 option a): the Label Tool labels, copies and pastes Interface settings, asking once per
 * batch, refusing a stale target and a target on a network the player may not edit.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class LabelToolGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      A = new BlockPos(2, 1, 1),
      B = new BlockPos(1, 1, 2),
      C = new BlockPos(1, 1, 3),
      FOREIGN_ROOT = new BlockPos(4, 1, 5),
      D = new BlockPos(5, 1, 5);

  private static final ConnectorSettings SOURCE =
      new ConnectorSettings(ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, 7, 0, 9, 8);

  private LabelToolGameTests() {}

  private record Fixture(
      ServerPlayer player,
      ItemStack tool,
      MachineInterfaceEntity a,
      MachineInterfaceEntity b,
      MachineInterfaceEntity c,
      MachineInterfaceEntity d) {}

  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    for (var chest : List.of(new BlockPos(3, 1, 1), new BlockPos(2, 1, 2), new BlockPos(2, 1, 3)))
      h.setBlock(chest, Blocks.CHEST);
    var a = net.machine(A, Direction.EAST);
    var b = net.machine(B, Direction.EAST);
    var c = net.machine(C, Direction.EAST);
    // Another player's network; this player holds no grant there.
    var foreign = Net.in(h, UUID.randomUUID());
    foreign.controller(FOREIGN_ROOT);
    h.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
    var d = foreign.machine(D, Direction.NORTH);
    var tool = new ItemStack(LabelToolContent.ITEM.get());
    player.setItemInHand(InteractionHand.MAIN_HAND, tool);
    return new Fixture(player, tool, a, b, c, d);
  }

  private static void ready(GameTestHelper h) {
    var networks = WorldNetworks.get(h.getLevel().getServer());
    for (var position : List.of(A, B, C, D))
      h.assertTrue(
          networks.membership(Net.in(h).at(position)).status() == NetworkTopology.Status.VALID,
          "Networks ready");
  }

  private static void use(GameTestHelper h, Fixture f, BlockPos at, boolean sneak) {
    f.player.setShiftKeyDown(sneak);
    var pos = h.absolutePos(at);
    f.tool
        .getItem()
        .useOn(
            new UseOnContext(
                f.player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
    f.player.setShiftKeyDown(false);
  }

  private static void label(Fixture f, String label) {
    f.tool.set(LabelToolContent.DATA.get(), new LabelToolContent.Data(label, new CompoundTag()));
  }

  private static void source(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.a.applySettings(new NetworkPermissions.Actor(f.player.getUUID(), false), SOURCE, "smelt")
            == NetworkAuthority.Decision.ALLOWED,
        "Source configured");
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void labelsThenCopiesAndPastesWithOneConfirmationPerBatch(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> source(h, f))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              label(f, "out");
              use(h, f, B, false);
              h.assertTrue(f.b.label().equals("out"), "Use applies the tool's label");
            })
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              use(h, f, A, true);
              var data = f.tool.get(LabelToolContent.DATA.get());
              h.assertTrue(data != null && data.copied(), "Sneak-use copies");
              use(h, f, B, false);
              h.assertTrue(
                  !f.b.settings().equals(SOURCE) && f.b.label().equals("out"),
                  "The first paste only asks for confirmation");
              use(h, f, B, false);
              h.assertTrue(
                  f.b.settings().equals(SOURCE) && f.b.label().equals("smelt"),
                  "The second use on the same Interface pastes settings and label");
            })
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              use(h, f, C, false);
              h.assertTrue(
                  f.c.settings().equals(SOURCE) && f.c.label().equals("smelt"),
                  "Within the batch another Interface pastes without asking again");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void aStaleTargetAndAForeignNetworkAreRefused(GameTestHelper h) {
    var f = setup(h);
    var other =
        new ConnectorSettings(ResourceKind.ITEM, ConnectorSettings.Access.WRITE_ONLY, 2, 0, 27, 16);
    h.startSequence()
        .thenWaitUntil(() -> ready(h))
        .thenExecute(() -> source(h, f))
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              use(h, f, A, true);
              use(h, f, B, false);
              // Someone changes B between the confirmation prompt and the confirming use.
              h.assertTrue(
                  f.b.applySettings(
                          new NetworkPermissions.Actor(f.player.getUUID(), false), other, "")
                      == NetworkAuthority.Decision.ALLOWED,
                  "B changed");
            })
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              use(h, f, B, false);
              h.assertTrue(f.b.settings().equals(other), "A stale confirmation does not paste");
              use(h, f, B, false);
              h.assertTrue(f.b.settings().equals(SOURCE), "Confirming the fresh view pastes");
            })
        .thenWaitUntil(() -> ready(h))
        .thenExecute(
            () -> {
              var before = f.d.settings();
              use(h, f, D, false);
              use(h, f, D, false);
              h.assertTrue(
                  f.d.settings().equals(before) && f.d.label().isEmpty(),
                  "Another player's network refuses the paste");
              label(f, "x");
              use(h, f, D, false);
              h.assertTrue(f.d.label().isEmpty(), "... and the label");
            })
        .thenSucceed();
  }
}
