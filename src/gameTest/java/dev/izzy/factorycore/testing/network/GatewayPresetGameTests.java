package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.GatewayPolicyMenu;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.Set;
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

/** U8: a gateway preset choice writes its exact policy, and only with Manage. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class GatewayPresetGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      GATEWAY = new BlockPos(2, 1, 1),
      CHILD = new BlockPos(3, 1, 1);

  private GatewayPresetGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT).above()));
    player.getInventory().clearContent();
    return player;
  }

  private static GatewayPolicyMenu open(GameTestHelper h, ServerPlayer player) {
    h.useBlock(GATEWAY, player);
    h.assertTrue(
        player.containerMenu instanceof GatewayPolicyMenu, "Plain use opens the gateway screen");
    return (GatewayPolicyMenu) player.containerMenu;
  }

  private static void branchReady(GameTestHelper h) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer())
                .membership(Positions.of(h.getLevel(), h.absolutePos(CHILD)))
                .status()
            == NetworkTopology.Status.VALID,
        "Branch ready");
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void presetChoicesWriteExactPoliciesOnlyWithManage(GameTestHelper h) {
    var owner = player(h);
    var guest = player(h);
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
    saved
        .network(root.handle().id())
        .setGrants(
            new NetworkPermissions.Actor(owner.getUUID(), false),
            guest.getUUID(),
            Set.of(NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.EDIT));
    var sequence = h.startSequence().thenWaitUntil(() -> branchReady(h));
    for (var preset :
        new GatewayPolicy.Preset[] {
          GatewayPolicy.Preset.ISOLATED, GatewayPolicy.Preset.READ_ONLY, GatewayPolicy.Preset.SHARED
        })
      sequence =
          sequence
              .thenExecute(
                  () -> {
                    var menu = open(h, owner);
                    h.assertTrue(
                        menu.clickMenuButton(
                            owner, GatewayPolicyMenu.PRESET_BUTTON + preset.ordinal()),
                        "Owner may choose " + preset);
                    h.assertTrue(
                        saved.nodes().definition(gateway.handle()).policy().equals(preset.policy()),
                        preset + " writes exactly its policy");
                    owner.closeContainer();
                  })
              // Each choice rebuilds the branch; Manage is checked again once it settles.
              .thenWaitUntil(() -> branchReady(h));
    sequence
        .thenExecute(
            () -> {
              var menu = open(h, owner);
              h.assertFalse(
                  menu.clickMenuButton(
                      owner,
                      GatewayPolicyMenu.PRESET_BUTTON + GatewayPolicy.Preset.values().length),
                  "An out-of-range preset id is refused");
              owner.closeContainer();
              var guestMenu = open(h, guest);
              h.assertFalse(
                  guestMenu.clickMenuButton(
                      guest,
                      GatewayPolicyMenu.PRESET_BUTTON + GatewayPolicy.Preset.ISOLATED.ordinal()),
                  "Without Manage a preset click is refused");
              h.assertTrue(
                  saved
                      .nodes()
                      .definition(gateway.handle())
                      .policy()
                      .equals(GatewayPolicy.Preset.SHARED.policy()),
                  "The refused click changes nothing");
              guest.closeContainer();
              owner.connection.disconnect(Component.literal("done"));
              guest.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
