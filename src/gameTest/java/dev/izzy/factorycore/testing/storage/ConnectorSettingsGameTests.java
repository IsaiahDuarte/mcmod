package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsMenu;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsPackets;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** U5: the Storage Connector screen replaces every offhand control under the same authority. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ConnectorSettingsGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      CONNECTOR = new BlockPos(2, 1, 1),
      CHEST = new BlockPos(3, 1, 1);

  private record Fixture(ServerPlayer player, ConnectorEntity connector, NetworkNodeEntity root) {}

  private ConnectorSettingsGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    return player;
  }

  private static Fixture setup(GameTestHelper h) {
    var player = player(h);
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    h.setBlock(CONNECTOR, InterfaceContent.state(InterfaceKind.STORAGE, Direction.EAST));
    ConnectorEntity connector = h.getBlockEntity(CONNECTOR);
    connector.place(new ItemStack(InterfaceContent.ITEM.get()));
    h.setBlock(CHEST, Blocks.CHEST);
    return new Fixture(player, connector, root);
  }

  private static ConnectorSettingsMenu open(GameTestHelper h, ServerPlayer player) {
    h.useBlock(CONNECTOR, player);
    h.assertTrue(
        player.containerMenu instanceof ConnectorSettingsMenu,
        "Empty-hand use opens the connector screen");
    var menu = (ConnectorSettingsMenu) player.containerMenu;
    menu.broadcastChanges();
    return menu;
  }

  private static MachineSettingsPackets.Form form(ResourceKind kind, int rate) {
    return new MachineSettingsPackets.Form(
        "",
        kind,
        ConnectorSettings.Access.WRITE_ONLY,
        -7,
        kind == ResourceKind.ITEM ? 2 : 0,
        kind == ResourceKind.ITEM ? 1 : 64,
        rate);
  }

  private static void apply(
      ServerPlayer player, ConnectorSettingsMenu menu, MachineSettingsPackets.Form form) {
    menu.apply(
        player,
        new ConnectorSettingsPackets.Apply(
            menu.containerId, menu.session(), menu.view().generation(), form));
  }

  private static void end(ServerPlayer player) {
    player.closeContainer();
    player.connection.disconnect(Component.literal("Connector settings fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void screenEditsResourceAccessPriorityWindowAndRate(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.connector.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var m = open(h, f.player);
              h.assertTrue(
                  m.view().editable()
                      && m.view().binding().equals("ok")
                      && m.view().side().equals("west")
                      && m.view().form().settings().equals(f.connector.settings()),
                  "Screen shows current settings and a ready chest registration");
              long generation = f.connector.generation();
              apply(f.player, m, form(ResourceKind.ITEM, 8));
              h.assertTrue(
                  f.player.containerMenu != m
                      && f.connector.settings().equals(form(ResourceKind.ITEM, 8).settings())
                      && f.connector.generation() == generation + 1,
                  "One Apply sets access, priority, window and rate, then closes");
              m = open(h, f.player);
              apply(f.player, m, form(ResourceKind.FLUID, 500));
              h.assertTrue(
                  f.connector.settings().kind() == ResourceKind.FLUID,
                  "The resource selector replaces the crouch-use cycle");
              m = open(h, f.player);
              h.assertTrue(
                  m.view().binding().equals("unsupported"),
                  "Status line explains that a vanilla chest has no fluid storage");
              end(f.player);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void staleNonceAndOverLimitRateCannotOverwrite(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.connector.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var m = open(h, f.player);
              long generation = f.connector.generation();
              m.apply(
                  f.player,
                  new ConnectorSettingsPackets.Apply(
                      m.containerId, UUID.randomUUID(), generation, form(ResourceKind.ITEM, 8)));
              h.assertTrue(
                  f.connector.generation() == generation, "A wrong session nonce edits nothing");
              apply(f.player, m, form(ResourceKind.ITEM, 1024));
              h.assertTrue(
                  f.connector.generation() == generation && m.view().status().equals("upgrade"),
                  "A rate above the installed tier asks for an upgrade");
              f.connector.configure(
                  new NetworkPermissions.Actor(f.player.getUUID(), false),
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, 3, 0, 4, 16));
              m.apply(
                  f.player,
                  new ConnectorSettingsPackets.Apply(
                      m.containerId, m.session(), generation, form(ResourceKind.ITEM, 8)));
              h.assertTrue(
                  m.view().status().equals("stale")
                      && f.connector.settings().rate() == 16
                      && m.view().form().rate() == 16,
                  "An old form cannot replace newer settings; the current form reloads");
              end(f.player);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void viewOnlyPlayerSeesButCannotSaveAndStrangerCannotOpen(GameTestHelper h) {
    var f = setup(h);
    var guest = player(h);
    var stranger = player(h);
    var owner = new NetworkPermissions.Actor(f.player.getUUID(), false);
    DeviceSavedData.get(guest.server)
        .network(f.root.handle().id())
        .setGrants(owner, guest.getUUID(), Set.of(NetworkPermissions.Permission.VIEW));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.connector.canUse(guest), "Viewer ready"))
        .thenExecute(
            () -> {
              var m = open(h, guest);
              long generation = f.connector.generation();
              h.assertTrue(!m.view().editable(), "VIEW alone shows a read-only form");
              apply(guest, m, form(ResourceKind.ITEM, 8));
              h.assertTrue(
                  f.connector.generation() == generation
                      && m.view().status().equals("denied")
                      && guest.containerMenu == m,
                  "Apply without EDIT is denied and changes nothing");
              h.useBlock(CONNECTOR, stranger);
              h.assertFalse(
                  stranger.containerMenu instanceof ConnectorSettingsMenu,
                  "A player without VIEW cannot open the screen");
              end(guest);
              end(stranger);
              end(f.player);
            })
        .thenSucceed();
  }
}
