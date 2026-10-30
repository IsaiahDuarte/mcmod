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
import dev.izzy.factorycore.testing.ConnectorFixture;
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

  /**
   * P5: the screens learn the bound container's size so they can draw a slot picker; a window
   * picked there is an ordinary Apply; a container over 64 slots reports its size for the fields.
   */
  @GameTest(template = "empty", timeoutTicks = 200)
  public static void viewsReportContainerSizeAndPickedWindowRoundTrips(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.connector.canUse(f.player), "Wired owner ready"))
        .thenExecute(
            () -> {
              var m = open(h, f.player);
              h.assertTrue(
                  m.view().itemSlots() == 27 && m.view().tanks() == 0,
                  "A chest reports 27 slots and no tanks: " + m.view());
              // What a click on slot 3 then Shift-click on slot 4 produces.
              var picked =
                  new MachineSettingsPackets.Form(
                      "", ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 3, 2, 64);
              apply(f.player, m, picked);
              m = open(h, f.player);
              h.assertTrue(
                  m.view().form().firstSlot() == 3 && m.view().form().slotCount() == 2,
                  "The picked window is saved and shown again: " + m.view().form());
              f.player.closeContainer();
              h.setBlock(CHEST, ConnectorFixture.WIDE.get());
            })
        .thenWaitUntil(() -> h.assertTrue(f.connector.canUse(f.player), "Rebound to wide"))
        .thenExecute(
            () -> {
              var m = open(h, f.player);
              h.assertTrue(
                  m.view().itemSlots() == 100,
                  "A 100-slot container reports its size (the screen keeps number fields)");
              f.player.closeContainer();
              h.setBlock(CHEST, Blocks.AIR);
            })
        .thenWaitUntil(() -> h.assertTrue(f.connector.canUse(f.player), "Usable unbound"))
        .thenExecute(
            () -> {
              var m = open(h, f.player);
              h.assertTrue(
                  m.view().itemSlots() == 0 && m.view().tanks() == 0,
                  "No container reports zero, so no picker is drawn");
              end(f.player);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void machineViewReportsBoundFaceSize(GameTestHelper h) {
    var player = player(h);
    Net.in(h, player.getUUID()).controller(ROOT);
    var machine = Net.in(h).machine(CONNECTOR, Direction.EAST);
    h.setBlock(CHEST, Blocks.HOPPER);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(machine.canUse(player), "Wired owner ready"))
        .thenExecute(
            () -> {
              h.useBlock(CONNECTOR, player);
              h.assertTrue(
                  player.containerMenu
                      instanceof dev.izzy.factorycore.platform.storage.MachineSettingsMenu,
                  "Machine screen opens");
              var m =
                  (dev.izzy.factorycore.platform.storage.MachineSettingsMenu) player.containerMenu;
              m.broadcastChanges();
              h.assertTrue(m.view().itemSlots() == 5, "A hopper reports its 5 slots: " + m.view());
              end(player);
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
                  f.connector.generation() == generation
                      && m.view().status() == MachineSettingsPackets.Status.UPGRADE,
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
                  m.view().status() == MachineSettingsPackets.Status.STALE
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
                      && m.view().status() == MachineSettingsPackets.Status.DENIED
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
