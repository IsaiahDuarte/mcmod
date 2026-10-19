package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /factorycore access}: Manage-checked grant administration through the shared authority.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class AccessCommandGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), CABLE = new BlockPos(2, 1, 1);

  private AccessCommandGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos relative) {
    return new NodeRegistry.Position(
        h.getLevel().dimension().location().toString(), h.absolutePos(relative).asLong());
  }

  private static int run(CommandSourceStack source, String command) {
    try {
      return source.getServer().getCommands().getDispatcher().execute(command, source);
    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException rejected) {
      return -1;
    }
  }

  private static boolean allowed(GameTestHelper h, ServerPlayer player, Permission permission) {
    return WorldNetworks.get(h.getLevel().getServer())
            .authorize(
                new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
                position(h, CABLE),
                permission)
        == NetworkAuthority.Decision.ALLOWED;
  }

  @SuppressWarnings("removal")
  @GameTest(template = "empty", timeoutTicks = 200)
  public static void ownersAndOperatorsAdministerGrantsAndOthersAreRefused(GameTestHelper h) {
    var owner = h.makeMockServerPlayerInLevel();
    var stranger = h.makeMockServerPlayerInLevel();
    var third = h.makeMockServerPlayerInLevel();
    for (var player : new ServerPlayer[] {owner, stranger, third})
      net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
          player.connection.getConnection());
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    h.setBlock(CABLE, NetworkContent.CABLE.get());
    var at = h.absolutePos(CABLE);
    String pos = at.getX() + " " + at.getY() + " " + at.getZ();
    var air = h.absolutePos(new BlockPos(1, 3, 4));
    String nowhere = air.getX() + " " + air.getY() + " " + air.getZ();
    var console = h.getLevel().getServer().createCommandSourceStack();
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(position(h, CABLE))
                            .status()
                        == NetworkTopology.Status.VALID,
                    "Cable joins the owner's network"))
        .thenExecute(
            () -> {
              var source = stranger.createCommandSourceStack();
              h.assertFalse(stranger.hasPermissions(2), "The stranger is not an operator");
              h.assertTrue(!allowed(h, stranger, Permission.VIEW), "Private by default");
              h.assertTrue(
                  run(source, "factorycore access grant " + pos + " " + stranger.getUUID() + " all")
                      == 0,
                  "A stranger cannot grant itself access");
              h.assertTrue(
                  run(source, "factorycore access list " + pos) == 0,
                  "A stranger cannot read the grant list");
              long generation = saved.network(root.handle().id()).generation();
              var self = owner.createCommandSourceStack();
              h.assertTrue(
                  run(self, "factorycore access grant " + pos + " " + stranger.getUUID() + " view")
                      == 1,
                  "The owner grants View");
              h.assertTrue(
                  allowed(h, stranger, Permission.VIEW)
                      && !allowed(h, stranger, Permission.WITHDRAW),
                  "Exactly View is granted");
              h.assertTrue(
                  saved.network(root.handle().id()).generation() == generation + 1,
                  "A grant advances the permission generation once");
              h.assertTrue(
                  run(
                          self,
                          "factorycore access grant "
                              + pos
                              + " "
                              + stranger.getUUID()
                              + " deposit,withdraw")
                      == 1,
                  "Grants are additive");
              h.assertTrue(
                  allowed(h, stranger, Permission.VIEW)
                      && allowed(h, stranger, Permission.WITHDRAW)
                      && allowed(h, stranger, Permission.DEPOSIT)
                      && !allowed(h, stranger, Permission.MANAGE),
                  "View, deposit and withdraw only");
              h.assertTrue(
                  run(self, "factorycore access list " + pos) == 2,
                  "Owner lists the owner row and one grant");
              h.assertTrue(
                  run(
                          self,
                          "factorycore access revoke "
                              + pos
                              + " "
                              + stranger.getUUID()
                              + " withdraw")
                      == 1,
                  "Revoke removes one permission");
              h.assertTrue(
                  allowed(h, stranger, Permission.VIEW)
                      && !allowed(h, stranger, Permission.WITHDRAW),
                  "Withdraw revoked, View kept");
              h.assertTrue(
                  run(self, "factorycore access grant " + pos + " " + owner.getUUID() + " view")
                      == 0,
                  "Owner grants are implicit");
              h.assertTrue(
                  run(self, "factorycore access grant " + pos + " " + stranger.getUUID() + " fly")
                      == 0,
                  "Unknown permission names are refused");
              h.assertTrue(
                  run(self, "factorycore access grant " + pos + " nobody-online view") == 0,
                  "Offline names are not looked up");
              h.assertTrue(
                  run(
                          self,
                          "factorycore access grant "
                              + nowhere
                              + " "
                              + stranger.getUUID()
                              + " view")
                      == 0,
                  "A position outside any network is refused");
              h.assertTrue(
                  run(console, "factorycore access revoke " + pos + " " + stranger.getUUID()) == 1,
                  "An operator revokes everything");
              h.assertTrue(!allowed(h, stranger, Permission.VIEW), "Revocation is immediate");
              h.assertTrue(
                  run(
                          console,
                          "factorycore access grant " + pos + " " + stranger.getUUID() + " manage")
                      == 1,
                  "An operator delegates Manage");
              h.assertTrue(
                  run(source, "factorycore access grant " + pos + " " + third.getUUID() + " view")
                      == 1,
                  "A Manage grantee administers others");
              h.assertTrue(
                  allowed(h, third, Permission.VIEW)
                      && saved
                          .network(root.handle().id())
                          .snapshot()
                          .grants()
                          .equals(
                              java.util.Map.of(
                                  stranger.getUUID(),
                                  Set.of(Permission.MANAGE),
                                  third.getUUID(),
                                  Set.of(Permission.VIEW))),
                  "Canonical grants hold exactly the administered sets");
              for (var player : new ServerPlayer[] {owner, stranger, third})
                player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
