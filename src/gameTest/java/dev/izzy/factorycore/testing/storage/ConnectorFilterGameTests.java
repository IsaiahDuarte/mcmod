package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsMenu;
import dev.izzy.factorycore.platform.storage.FilterEdits;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * B2: a Storage-mode insert filter limits what priority routes put into its container. The
 * container's other contents stay listed and withdrawable; refused resources go to the next
 * priority or stay with the player.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ConnectorFilterGameTests {
  private static final BlockPos ROOT = new BlockPos(2, 1, 2),
      FILTERED = new BlockPos(3, 1, 2),
      // The filtered chest (named for its role in the first test).
      HOPPER = new BlockPos(4, 1, 2),
      PLAIN = new BlockPos(1, 1, 2),
      CHEST = new BlockPos(0, 1, 2),
      TERMINAL = new BlockPos(2, 1, 3);

  /** Ticks between commands so the session's command rate gate admits each one. */
  private static final int GAP = 5;

  private record Fixture(ServerPlayer player, ConnectorEntity filtered, ConnectorEntity plain) {}

  private ConnectorFilterGameTests() {}

  private static NetworkPermissions.Actor actor(ServerPlayer player) {
    return new NetworkPermissions.Actor(player.getUUID(), false);
  }

  private static ConnectorEntity storage(GameTestHelper h, BlockPos at, Direction facing) {
    h.setBlock(at, InterfaceContent.state(InterfaceKind.STORAGE, facing));
    ConnectorEntity connector = h.getBlockEntity(at);
    connector.place(new ItemStack(InterfaceContent.ITEM.get()));
    return connector;
  }

  private static Fixture setup(GameTestHelper h, boolean plainStorage) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    Net.in(h, player.getUUID()).controller(ROOT);
    var filtered = storage(h, FILTERED, Direction.EAST);
    h.setBlock(HOPPER, Blocks.CHEST);
    ConnectorEntity plain = null;
    if (plainStorage) {
      plain = storage(h, PLAIN, Direction.WEST);
      h.setBlock(CHEST, Blocks.CHEST);
    }
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    return new Fixture(player, filtered, plain);
  }

  /** Cobblestone only, priority 10, so it is tried before the priority-0 chest. */
  private static void filter(Fixture f) {
    var settings =
        new ConnectorSettings(ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 10, 0, 64, 64)
            .withInsert(
                new ResourceFilter(
                    ResourceKind.ITEM,
                    ResourceFilter.Mode.INCLUDE,
                    List.of(new ResourceFilter.Registry("minecraft:cobblestone"))));
    f.filtered.configure(actor(f.player), settings);
  }

  private static TerminalMenu terminal(Fixture f) {
    return (TerminalMenu) f.player.containerMenu;
  }

  private static void deposit(Fixture f, Item item, int count) {
    f.player.getInventory().setItem(0, new ItemStack(item, count));
    var m = terminal(f);
    var key = new PlatformResourceCodec(f.player.registryAccess()).itemKey(new ItemStack(item));
    m.command(
        f.player,
        new TerminalPackets.Command(
            m.containerId,
            m.session(),
            m.view().revision(),
            TerminalPackets.DEPOSIT,
            0,
            count,
            "",
            TerminalPackets.fingerprint(key)));
    m.broadcastChanges();
  }

  private static int count(GameTestHelper h, BlockPos at, Item item) {
    Container container = (Container) h.getBlockEntity(at);
    int total = 0;
    for (int slot = 0; slot < container.getContainerSize(); slot++)
      if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
    return total;
  }

  private static void end(Fixture f) {
    f.player.closeContainer();
    f.player.connection.disconnect(Component.literal("Connector filter fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void filteredStorageTakesOnlyItsItemsAndOverflowsByPriority(GameTestHelper h) {
    var f = setup(h, true);
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.filtered.canUse(f.player) && f.plain.canUse(f.player), "Ports ready"))
        .thenExecute(
            () -> {
              filter(f);
              // The chest already holds dirt, which the filter would not let in.
              Container filled = h.getBlockEntity(HOPPER);
              filled.setItem(0, new ItemStack(Items.DIRT, 3));
              for (int slot = 1; slot < filled.getContainerSize(); slot++)
                filled.setItem(
                    slot,
                    new ItemStack(
                        Items.COBBLESTONE, slot == filled.getContainerSize() - 1 ? 60 : 64));
              h.useBlock(TERMINAL, f.player);
            })
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              var connectors =
                  dev.izzy.factorycore.platform.network.WorldNetworks.get(f.player.server)
                      .connectors();
              h.assertTrue(
                  connectors.bind(f.filtered).reason()
                      == dev.izzy.factorycore.core.resource.OperationResult.Reason.OK,
                  "Filtered port bound: "
                      + connectors.bind(f.filtered).reason()
                      + " plain: "
                      + connectors.bind(f.plain).reason());
              deposit(f, Items.COBBLESTONE, 10);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    count(h, HOPPER, Items.COBBLESTONE) == 25 * 64 + 64
                        && f.player.getInventory().getItem(0).getCount() == 6,
                    "The filtered chest takes the four it has room for; one route makes one"
                        + " transfer, so the other six stay with the player"))
        .thenIdle(GAP)
        .thenExecute(() -> deposit(f, Items.COBBLESTONE, 6))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.player.getInventory().getItem(0).isEmpty()
                        && count(h, CHEST, Items.COBBLESTONE) == 6,
                    "With the filtered chest full, cobblestone goes to the next priority"))
        .thenIdle(GAP)
        .thenExecute(() -> deposit(f, Items.OAK_LOG, 5))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.player.getInventory().getItem(0).isEmpty()
                        && count(h, CHEST, Items.OAK_LOG) == 5
                        && count(h, HOPPER, Items.OAK_LOG) == 0,
                    "Logs skip the cobblestone-only chest even though it is tried first"))
        .thenWaitUntil(
            () -> {
              terminal(f).broadcastChanges();
              var rows = terminal(f).view().rows();
              h.assertTrue(
                  rows.stream().anyMatch(row -> row.icon().equals("minecraft:dirt")),
                  "Dirt already in the filtered chest is still listed: " + rows);
            })
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              var m = terminal(f);
              var rows = m.view().rows();
              int dirt = -1;
              for (int i = 0; i < rows.size(); i++)
                if (rows.get(i).icon().equals("minecraft:dirt")) dirt = i;
              f.player.getInventory().clearContent();
              m.command(
                  f.player,
                  new TerminalPackets.Command(
                      m.containerId,
                      m.session(),
                      m.view().revision(),
                      TerminalPackets.WITHDRAW,
                      dirt,
                      3,
                      "",
                      new byte[32]));
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.player.getInventory().countItem(Items.DIRT) == 3
                        && count(h, HOPPER, Items.DIRT) == 0,
                    "Dirt the filter would refuse can still be taken out"))
        .thenExecute(() -> end(f))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void refusedDepositStaysWithThePlayerAndFilterReloads(GameTestHelper h) {
    var f = setup(h, false);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(f.filtered.canUse(f.player), "Port ready"))
        .thenExecute(
            () -> {
              filter(f);
              h.useBlock(TERMINAL, f.player);
            })
        .thenIdle(GAP)
        .thenExecute(() -> deposit(f, Items.OAK_LOG, 5))
        .thenIdle(20)
        .thenExecute(
            () -> {
              h.assertTrue(
                  f.player.getInventory().getItem(0).is(Items.OAK_LOG)
                      && f.player.getInventory().getItem(0).getCount() == 5
                      && count(h, HOPPER, Items.OAK_LOG) == 0,
                  "With nowhere that accepts logs the deposit is refused and they stay");
              f.player.closeContainer();
              f.player.getInventory().clearContent();
              var saved = f.filtered.settings().insert();
              h.assertTrue(
                  saved != null && saved.terms().size() == 1, "The filter is part of settings");
            })
        .thenIdle(GAP)
        .thenExecute(
            () -> {
              // What reload restores is what the codec saved: reopen the settings screen.
              h.useBlock(FILTERED, f.player);
              h.assertTrue(
                  f.player.containerMenu instanceof ConnectorSettingsMenu, "Settings screen opens");
              var menu = (ConnectorSettingsMenu) f.player.containerMenu;
              menu.broadcastChanges();
              var rules = menu.view().insertRules();
              h.assertTrue(
                  menu.view().insertMode().equals("INCLUDE")
                      && rules.size() == 1
                      && rules.getFirst().type() == FilterEdits.ITEM
                      && rules.getFirst().icon().equals("minecraft:cobblestone"),
                  "The screen shows the saved filter: " + rules);
              end(f);
            })
        .thenSucceed();
  }
}
