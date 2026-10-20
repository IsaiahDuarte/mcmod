package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.GatewayPolicyMenu;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class GatewayPolicyGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      GATEWAY = new BlockPos(2, 1, 1),
      CHILD = new BlockPos(3, 1, 1);

  private GatewayPolicyGameTests() {}

  private static NetworkTopology.Status status(GameTestHelper h, BlockPos relative) {
    return WorldNetworks.get(h.getLevel().getServer())
        .membership(Positions.of(h.getLevel(), h.absolutePos(relative)))
        .status();
  }

  private static GatewayPolicyMenu open(GameTestHelper h, ServerPlayer player) {
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.setShiftKeyDown(true);
    h.useBlock(GATEWAY, player);
    player.setShiftKeyDown(false);
    h.assertTrue(
        player.containerMenu instanceof GatewayPolicyMenu, "Sneak-use opens the policy editor");
    return (GatewayPolicyMenu) player.containerMenu;
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void ownerEditsGatewayPolicyAndOthersAreReadOnly(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    var stranger = TestPlayers.mock(h);
    for (var player : new ServerPlayer[] {owner, stranger}) TestPlayers.connect(player);
    NetworkNodeEntity root = Net.in(h, owner.getUUID()).controller(ROOT);
    h.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gateway = h.getBlockEntity(GATEWAY);
    gateway.commission(null);
    h.setBlock(CHILD, NetworkContent.CABLE.get());
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    status(h, CHILD) == NetworkTopology.Status.VALID,
                    "Branch below the gateway is valid"))
        .thenExecute(
            () -> {
              var menu = open(h, owner);
              menu.broadcastChanges();
              h.assertTrue(menu.editable(), "The network owner may edit");
              h.assertTrue(
                  menu.clickMenuButton(owner, Permission.CRAFT.ordinal()), "Toggle branch Craft");
              h.assertFalse(
                  menu.clickMenuButton(owner, 14 + ResourceKind.FLUID.ordinal()),
                  "Edits fail closed while the edited branch is rebuilding");
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    status(h, CHILD) == NetworkTopology.Status.VALID,
                    "Branch rebuilds after the edit"))
        .thenExecute(
            () -> {
              var menu = (GatewayPolicyMenu) owner.containerMenu;
              h.assertTrue(
                  menu.clickMenuButton(owner, 14 + ResourceKind.FLUID.ordinal()),
                  "Toggle inherited fluids");
              var policy = saved.nodes().definition(gateway.handle()).policy();
              h.assertTrue(
                  !policy.branchPermissions().contains(Permission.CRAFT)
                      && policy.branchPermissions().contains(Permission.VIEW)
                      && !policy.inheritedKinds().contains(ResourceKind.FLUID)
                      && policy.inheritedKinds().contains(ResourceKind.ITEM),
                  "Exactly the toggled bits change in canonical policy: " + policy);
              h.assertFalse(menu.clickMenuButton(owner, 17), "Out-of-range buttons are refused");
              owner.closeContainer();
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    status(h, CHILD) == NetworkTopology.Status.VALID,
                    "Branch rebuilds after the edit"))
        .thenExecute(
            () -> {
              var before = saved.nodes().definition(gateway.handle()).policy();
              var menu = open(h, stranger);
              menu.broadcastChanges();
              h.assertFalse(menu.editable(), "A stranger sees a read-only editor");
              h.assertFalse(
                  menu.clickMenuButton(stranger, Permission.VIEW.ordinal()),
                  "Stranger clicks are refused");
              h.assertTrue(
                  saved.nodes().definition(gateway.handle()).policy().equals(before),
                  "Refused clicks change nothing");
              stranger.closeContainer();
              owner.connection.disconnect(Component.literal("done"));
              stranger.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
