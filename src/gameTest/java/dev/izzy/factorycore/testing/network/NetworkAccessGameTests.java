package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.network.AccessPackets;
import dev.izzy.factorycore.platform.network.GatewayPolicyMenu;
import dev.izzy.factorycore.platform.network.NetworkAccessMenu;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.Set;
import java.util.UUID;
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
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Network Access screen (ADR 0055): server-owned sessions administer grants and explicit, confirmed
 * ownership transfer through the shared authority; forged or stale packets change nothing; gateway
 * branch limits are shown and enforced.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class NetworkAccessGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      CABLE = new BlockPos(2, 1, 1),
      GATEWAY = new BlockPos(2, 1, 1),
      CHILD = new BlockPos(3, 1, 1);

  private NetworkAccessGameTests() {}

  private static NodeRegistry.Position position(GameTestHelper h, BlockPos relative) {
    return new NodeRegistry.Position(
        h.getLevel().dimension().location().toString(), h.absolutePos(relative).asLong());
  }

  private static boolean allowed(
      GameTestHelper h, ServerPlayer player, BlockPos at, Permission permission) {
    return WorldNetworks.get(h.getLevel().getServer())
            .authorize(
                new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
                position(h, at),
                permission)
        == NetworkAuthority.Decision.ALLOWED;
  }

  private static NetworkPermissions.State state(GameTestHelper h, NetworkNodeEntity root) {
    return DeviceSavedData.get(h.getLevel().getServer()).network(root.handle().id()).snapshot();
  }

  /** Sneak-use on the controller, as a real client would. */
  private static NetworkAccessMenu open(GameTestHelper h, ServerPlayer player) {
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT).above()));
    player.setShiftKeyDown(true);
    h.useBlock(ROOT, player);
    player.setShiftKeyDown(false);
    h.assertTrue(
        player.containerMenu instanceof NetworkAccessMenu,
        "Sneak-use on a controller opens Network Access");
    var menu = (NetworkAccessMenu) player.containerMenu;
    menu.broadcastChanges();
    return menu;
  }

  private static void send(
      NetworkAccessMenu menu, ServerPlayer sender, int action, UUID principal, int value) {
    menu.command(
        sender,
        new AccessPackets.Command(menu.containerId, menu.session(), action, principal, value));
  }

  private static ServerPlayer player(GameTestHelper h) {
    @SuppressWarnings("removal")
    var player = h.makeMockServerPlayerInLevel();
    NetworkRegistry.configureMockConnection(player.connection.getConnection());
    return player;
  }

  private static boolean listed(NetworkAccessMenu menu, UUID principal, int granted) {
    return menu.view().rows().stream()
        .anyMatch(row -> row.id().equals(principal) && row.granted() == granted);
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void ownerAdministersGrantsAndConfirmsOwnershipTransferInTheScreen(
      GameTestHelper h) {
    var owner = player(h);
    var guest = player(h);
    var third = player(h);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    h.setBlock(CABLE, NetworkContent.CABLE.get());
    int view = 1 << Permission.VIEW.ordinal(), withdraw = 1 << Permission.WITHDRAW.ordinal();
    NetworkAccessMenu[] menus = new NetworkAccessMenu[2];
    long[] generation = {0};
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(position(h, CABLE))
                            .status()
                        == NetworkTopology.Status.VALID,
                    "Network ready"))
        .thenExecute(
            () -> {
              var menu = menus[0] = open(h, owner);
              var v = menu.view();
              h.assertTrue(
                  v.manage() && v.ownerSelf() && v.transfer() && v.gateways() == 0,
                  "The owner manages, may transfer and is at the root: " + v);
              h.assertTrue(
                  listed(menu, guest.getUUID(), 0) && listed(menu, third.getUUID(), 0),
                  "Online players are offered as candidates without grants");
              generation[0] = state(h, root).generation();
              send(menu, owner, AccessPackets.GRANT, guest.getUUID(), Permission.VIEW.ordinal());
              h.assertTrue(
                  allowed(h, guest, CABLE, Permission.VIEW)
                      && !allowed(h, guest, CABLE, Permission.WITHDRAW),
                  "The View toggle grants exactly View");
              h.assertTrue(
                  state(h, root).generation() == generation[0] + 1
                      && menu.view().status().equals("updated")
                      && listed(menu, guest.getUUID(), view),
                  "One generation step; the view shows the grant");
              // A second click inside the 2-tick command window is dropped, not applied.
              send(menu, owner, AccessPackets.GRANT, guest.getUUID(), Permission.CRAFT.ordinal());
              h.assertTrue(
                  !allowed(h, guest, CABLE, Permission.CRAFT)
                      && menu.view().status().equals("rate"),
                  "Rate-limited commands change nothing");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = menus[0];
              send(
                  menu, owner, AccessPackets.GRANT, guest.getUUID(), Permission.WITHDRAW.ordinal());
              h.assertTrue(listed(menu, guest.getUUID(), view | withdraw), "Grants are additive");
              // Forged: the guest's own read-only session.
              var guestMenu = menus[1] = open(h, guest);
              var gv = guestMenu.view();
              h.assertTrue(
                  !gv.manage() && !gv.transfer() && gv.rows().isEmpty() && gv.total() == 0,
                  "Without Manage no other principal or owner is disclosed: " + gv);
              h.assertTrue(
                  gv.self() == (view | withdraw), "The guest sees its own effective access");
              long before = state(h, root).generation();
              send(
                  guestMenu,
                  guest,
                  AccessPackets.GRANT,
                  guest.getUUID(),
                  Permission.MANAGE.ordinal());
              h.assertTrue(
                  !allowed(h, guest, CABLE, Permission.MANAGE)
                      && guestMenu.view().status().equals("denied")
                      && state(h, root).generation() == before,
                  "A forged self-grant from a read-only session is refused");
              // Forged: the guest sends into the owner's session; wrong sender is ignored.
              send(menu, guest, AccessPackets.REVOKE_ALL, guest.getUUID(), 0);
              // Forged: wrong session nonce and wrong container id.
              menu.command(
                  owner,
                  new AccessPackets.Command(
                      menu.containerId,
                      UUID.randomUUID(),
                      AccessPackets.REVOKE_ALL,
                      guest.getUUID(),
                      0));
              menu.command(
                  owner,
                  new AccessPackets.Command(
                      menu.containerId + 1,
                      menu.session(),
                      AccessPackets.REVOKE_ALL,
                      guest.getUUID(),
                      0));
              h.assertTrue(
                  state(h, root).generation() == before
                      && allowed(h, guest, CABLE, Permission.WITHDRAW),
                  "Wrong sender, nonce or container changes nothing");
              boolean rejected;
              try {
                new AccessPackets.Command(menu.containerId, menu.session(), 99, guest.getUUID(), 0);
                rejected = false;
              } catch (IllegalArgumentException expected) {
                rejected = true;
              }
              h.assertTrue(rejected, "Out-of-range actions fail packet decoding");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = menus[0];
              send(
                  menu,
                  owner,
                  AccessPackets.REVOKE,
                  guest.getUUID(),
                  Permission.WITHDRAW.ordinal());
              h.assertTrue(
                  allowed(h, guest, CABLE, Permission.VIEW)
                      && !allowed(h, guest, CABLE, Permission.WITHDRAW),
                  "Revoke removes one permission");
              send(menus[1], guest, AccessPackets.TRANSFER, guest.getUUID(), 0);
              h.assertTrue(
                  menus[1].view().status().equals("denied")
                      && state(h, root).owner().equals(owner.getUUID()),
                  "A non-manager cannot request ownership transfer");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = menus[0];
              send(menu, owner, AccessPackets.TRANSFER, guest.getUUID(), 0);
              h.assertTrue(
                  menu.view().status().equals("confirm")
                      && menu.view().pending().equals(guest.getUUID())
                      && state(h, root).owner().equals(owner.getUUID()),
                  "Requesting a transfer only asks for confirmation");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = menus[0];
              send(menu, owner, AccessPackets.CONFIRM, third.getUUID(), 0);
              h.assertTrue(
                  menu.view().status().equals("unconfirmed")
                      && menu.view().pending().equals(AccessPackets.NONE)
                      && state(h, root).owner().equals(owner.getUUID()),
                  "Confirming a different principal than requested transfers nothing");
            })
        .thenIdle(3)
        .thenExecute(() -> send(menus[0], owner, AccessPackets.TRANSFER, guest.getUUID(), 0))
        .thenIdle(3)
        .thenExecute(
            () -> {
              // The grant table changes between request and confirmation.
              var menu = menus[0];
              send(menu, owner, AccessPackets.GRANT, third.getUUID(), 0);
              h.assertTrue(
                  menu.view().status().equals("changed")
                      && menu.view().pending().equals(AccessPackets.NONE),
                  "A grant change withdraws the pending confirmation: " + menu.view().status());
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = menus[0];
              send(menu, owner, AccessPackets.CONFIRM, guest.getUUID(), 0);
              h.assertTrue(
                  menu.view().status().equals("unconfirmed")
                      && state(h, root).owner().equals(owner.getUUID()),
                  "A stale confirmation after a grant change transfers nothing");
            })
        .thenIdle(3)
        .thenExecute(() -> send(menus[0], owner, AccessPackets.TRANSFER, guest.getUUID(), 0))
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = menus[0];
              send(menu, owner, AccessPackets.CONFIRM, guest.getUUID(), 0);
              var after = state(h, root);
              h.assertTrue(
                  after.owner().equals(guest.getUUID())
                      && !after.grants().containsKey(guest.getUUID())
                      && after.grants().containsKey(third.getUUID()),
                  "Confirmed transfer makes the guest owner and keeps others' grants: " + after);
              h.assertTrue(
                  !allowed(h, owner, CABLE, Permission.VIEW)
                      && allowed(h, guest, CABLE, Permission.MANAGE),
                  "The previous owner loses every implicit right");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = menus[0];
              menu.broadcastChanges();
              h.assertTrue(
                  !menu.view().manage()
                      && menu.view().rows().isEmpty()
                      && menu.view().status().equals("transferred"),
                  "The former owner's open session reports the transfer read-only: " + menu.view());
              send(menu, owner, AccessPackets.GRANT, owner.getUUID(), Permission.VIEW.ordinal());
              h.assertTrue(
                  !allowed(h, owner, CABLE, Permission.VIEW),
                  "The former owner cannot grant itself access back");
              owner.closeContainer();
              guest.closeContainer();
              // The new owner delegates Manage; a delegated manager cannot transfer ownership.
              var guestMenu = menus[1] = open(h, guest);
              h.assertTrue(guestMenu.view().ownerSelf(), "The guest now sees itself as owner");
              send(
                  guestMenu,
                  guest,
                  AccessPackets.GRANT,
                  third.getUUID(),
                  Permission.MANAGE.ordinal());
              var thirdMenu = open(h, third);
              h.assertTrue(
                  thirdMenu.view().manage() && !thirdMenu.view().transfer(),
                  "Delegated Manage administers grants but is not offered transfer");
              send(thirdMenu, third, AccessPackets.TRANSFER, third.getUUID(), 0);
              h.assertTrue(
                  thirdMenu.view().status().equals("denied")
                      && state(h, root).owner().equals(guest.getUUID()),
                  "A delegated manager's forged transfer request is refused");
              var networks = WorldNetworks.get(h.getLevel().getServer());
              h.assertTrue(
                  networks.transferOwnership(
                          new NetworkPermissions.Actor(third.getUUID(), false),
                          position(h, CABLE),
                          third.getUUID())
                      == NetworkPermissions.Change.DENIED,
                  "The shared transfer path refuses delegated managers too");
            })
        .thenIdle(3)
        .thenExecute(
            () ->
                send(
                    menus[1],
                    guest,
                    AccessPackets.REVOKE,
                    third.getUUID(),
                    Permission.MANAGE.ordinal()))
        .thenIdle(12)
        .thenExecute(
            () -> {
              var thirdMenu = (NetworkAccessMenu) third.containerMenu;
              thirdMenu.broadcastChanges();
              h.assertTrue(
                  !thirdMenu.view().manage()
                      && thirdMenu.view().rows().isEmpty()
                      && thirdMenu.view().status().equals("manage_revoked"),
                  "Revocation feedback reaches the open session: " + thirdMenu.view());
              for (var player : new ServerPlayer[] {owner, guest, third})
                player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void gatewayAccessScreenShowsAndEnforcesInheritedLimits(GameTestHelper h) {
    var owner = player(h);
    var guest = player(h);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
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
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(position(h, CHILD))
                            .status()
                        == NetworkTopology.Status.VALID,
                    "Branch ready"))
        .thenExecute(
            () -> {
              var policy = saved.nodes().definition(gateway.handle()).policy();
              h.assertTrue(
                  saved
                          .nodes()
                          .setPolicy(gateway.handle(), policy.toggled(Permission.CRAFT.ordinal()))
                      == NodeRegistry.Failure.OK,
                  "Branch blocks Craft");
              WorldNetworks.changed(h.getLevel(), h.absolutePos(GATEWAY));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(position(h, CHILD))
                            .status()
                        == NetworkTopology.Status.VALID,
                    "Branch rebuilt"))
        .thenExecute(
            () -> {
              owner.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT).above()));
              owner.setShiftKeyDown(true);
              h.useBlock(GATEWAY, owner);
              owner.setShiftKeyDown(false);
              h.assertTrue(
                  owner.containerMenu instanceof GatewayPolicyMenu, "Policy editor opened");
              var policyMenu = (GatewayPolicyMenu) owner.containerMenu;
              h.assertTrue(
                  policyMenu.clickMenuButton(owner, GatewayPolicyMenu.ACCESS_BUTTON),
                  "The policy editor's Access button is accepted");
              h.assertTrue(
                  owner.containerMenu instanceof NetworkAccessMenu,
                  "The Access button opens Network Access at the gateway");
              var menu = (NetworkAccessMenu) owner.containerMenu;
              menu.broadcastChanges();
              var v = menu.view();
              int craft = 1 << Permission.CRAFT.ordinal();
              h.assertTrue(
                  v.manage() && v.gateways() == 1 && (v.branch() & craft) == 0,
                  "One gateway above; its branch policy without Craft is shown: " + v);
              h.assertTrue(
                  (v.self() & craft) == 0, "Even the owner's effective access omits Craft here");
              send(menu, owner, AccessPackets.GRANT, guest.getUUID(), Permission.CRAFT.ordinal());
              h.assertTrue(
                  saved
                          .network(root.handle().id())
                          .snapshot()
                          .grants()
                          .getOrDefault(guest.getUUID(), Set.of())
                          .contains(Permission.CRAFT)
                      && !allowed(h, guest, CHILD, Permission.CRAFT),
                  "The network-wide grant is stored but blocked below the gateway");
              h.assertTrue(
                  menu.view().rows().stream()
                      .anyMatch(
                          row -> row.id().equals(guest.getUUID()) && (row.granted() & craft) != 0),
                  "The screen lists the blocked grant so it can mark it");
              owner.closeContainer();
              owner.connection.disconnect(Component.literal("done"));
              guest.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
