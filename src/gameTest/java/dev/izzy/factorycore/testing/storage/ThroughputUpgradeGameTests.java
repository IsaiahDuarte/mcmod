package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.platform.upgrade.ThroughputUpgradeable;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual item installation, permission failure, rate enforcement and portable ownership. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class ThroughputUpgradeGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos PORT = new BlockPos(2, 1, 1);
  private static final BlockPos PROVIDER = new BlockPos(3, 1, 1);
  private static final BlockPos BUFFER = new BlockPos(1, 1, 2);

  private record Fixture(
      ServerPlayer player,
      ConnectorEntity port,
      BufferedInterfaceEntity buffer,
      WorldNetworks networks) {
    NetworkPermissions.Actor actor() {
      return new NetworkPermissions.Actor(player.getUUID(), false);
    }
  }

  private ThroughputUpgradeGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(GameType.SURVIVAL);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    ((NetworkNodeEntity) h.getBlockEntity(ROOT)).commission(player.getUUID());
    h.setBlock(PORT, InterfaceContent.state(InterfaceKind.STORAGE, Direction.EAST));
    ConnectorEntity port = h.getBlockEntity(PORT);
    port.place(new ItemStack(InterfaceContent.ITEM.get()));
    h.setBlock(PROVIDER, Blocks.BARREL);
    BarrelBlockEntity barrel = h.getBlockEntity(PROVIDER);
    for (int slot = 0; slot < barrel.getContainerSize(); slot++)
      barrel.setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
    BufferedInterfaceEntity buffer = Net.in(h, player.getUUID()).buffer(BUFFER);
    return new Fixture(player, port, buffer, WorldNetworks.get(player.server));
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        f.networks.membership(f.port.position()).status() == NetworkTopology.Status.VALID
            && f.networks.membership(f.buffer.position()).status() == NetworkTopology.Status.VALID,
        "Upgrade fixture has current wired authority");
  }

  private static InteractionResult use(
      GameTestHelper h, ServerPlayer player, BlockPos relative, ItemStack upgrade) {
    player.setItemInHand(InteractionHand.MAIN_HAND, upgrade);
    var absolute = h.absolutePos(relative);
    return upgrade.useOn(
        new UseOnContext(
            player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false)));
  }

  private static ResourcePort bind(GameTestHelper h, Fixture f) {
    var registration = f.networks.connectors().bind(f.port);
    h.assertTrue(registration.handle() != null, "Actual provider binds: " + registration.reason());
    return f.networks.connectors().port(registration.handle());
  }

  private static void end(Fixture f) {
    f.player.connection.disconnect(Component.literal("Throughput fixture complete"));
  }

  private static long extract(ResourcePort port, ResourceKey key, long requested) {
    long moved = 0;
    // A platform handler may return only one stack per call; the contract caps their aggregate.
    for (int call = 0; call < 64 && moved < requested; call++) {
      long actual = port.extract(key, requested - moved);
      if (actual == 0) break;
      moved += actual;
    }
    return moved;
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void paidPortStepsEnforceRealRatesAndInvalidateOldHandles(GameTestHelper h) {
    var f = setup(h);
    var key =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.IRON_INGOT));
    var old = new ResourcePort[1];
    var installedAt = new int[1];
    UUID id = f.port.handle().id();
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var overdrive = new ItemStack(ThroughputContent.OVERDRIVE.get(), 2);
              h.assertTrue(
                  use(h, f.player, PORT, overdrive) == InteractionResult.FAIL
                      && overdrive.getCount() == 2
                      && f.port.tier() == ThroughputTier.BASE,
                  "Skipping the paid first step consumes nothing");
              try {
                f.port.configure(
                    f.actor(),
                    new ConnectorSettings(
                        ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 129));
                h.fail("Base hardware accepted a forged high rate");
              } catch (IllegalArgumentException rejected) {
                h.assertTrue(
                    f.port.settings().rate() == 64, "Rejected rate changed no configuration");
              }
              old[0] = bind(h, f);
              h.assertTrue(
                  old[0].extract(key, 256) == 64,
                  "Unupgraded port retains its real 64-item setting");
              var advanced = new ItemStack(ThroughputContent.THROUGHPUT.get(), 2);
              h.assertTrue(
                  use(h, f.player, PORT, advanced).consumesAction()
                      && advanced.getCount() == 1
                      && f.port.settings().rate() == 256,
                  "Successful paid use consumes exactly one and raises the physical rate");
              installedAt[0] = f.player.server.getTickCount();
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.player.server.getTickCount() != installedAt[0],
                    "Measure the Advanced ceiling in a fresh server tick"))
        .thenExecute(
            () -> {
              h.assertTrue(old[0].extract(key, 1) == 0, "Pre-upgrade provider handle stays inert");
              var port = bind(h, f);
              h.assertTrue(
                  extract(port, key, 512) == 256 && port.extract(key, 1) == 0,
                  "Advanced extraction is capped at 256 across actual calls");
              var overdrive = new ItemStack(ThroughputContent.OVERDRIVE.get(), 2);
              h.assertTrue(
                  use(h, f.player, PORT, overdrive).consumesAction()
                      && overdrive.getCount() == 1
                      && f.port.handle().id().equals(id),
                  "Second paid step preserves device identity");
              installedAt[0] = f.player.server.getTickCount();
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.player.server.getTickCount() != installedAt[0],
                    "Measure the Overdrive ceiling in a fresh server tick"))
        .thenExecute(
            () -> {
              var port = bind(h, f);
              long actualAmount = extract(port, key, 2048);
              h.assertTrue(
                  actualAmount == 1024 && port.extract(key, 1) == 0,
                  "Overdrive remains bounded at 1024 items per tick; moved="
                      + actualAmount
                      + " rate="
                      + f.port.settings().rate());
              var portable = f.port.portableForBreak();
              h.setBlock(PORT, Blocks.AIR);
              h.setBlock(PORT, InterfaceContent.state(InterfaceKind.STORAGE, Direction.EAST));
              ConnectorEntity moved = h.getBlockEntity(PORT);
              moved.place(portable);
              h.assertTrue(
                  moved.handle().id().equals(id)
                      && moved.tier() == ThroughputTier.ENDGAME
                      && moved.settings().rate() == 1024,
                  "Portable ownership retains the paid tier and rate");
              end(f);
            })
        .thenSucceed();
  }

  /**
   * A foreign player is denied the same way on every upgradeable device: no item spent, no tier
   * change, and a clean refusal rather than a device fault (the buffer used to surface its
   * permission exception as a fault message).
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void foreignUpgradeIsDeniedAlikeOnEveryDevice(GameTestHelper h) {
    var f = setup(h);
    var stranger = TestPlayers.mock(h);
    TestPlayers.connect(stranger);
    stranger.setGameMode(GameType.SURVIVAL);
    stranger.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    var actor = new NetworkPermissions.Actor(stranger.getUUID(), false);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              for (var device : List.<ThroughputUpgradeable>of(f.port, f.buffer))
                h.assertTrue(
                    !device.upgrade(actor, ThroughputTier.ADVANCED)
                        && device.tier() == ThroughputTier.BASE,
                    "Foreign actor is refused without a fault: " + device);
              for (var target : List.of(PORT, BUFFER)) {
                var item = new ItemStack(ThroughputContent.THROUGHPUT.get(), 1);
                h.assertTrue(
                    use(h, stranger, target, item) == InteractionResult.FAIL
                        && item.getCount() == 1,
                    "Denied use at " + target + " keeps the item");
              }
              stranger.connection.disconnect(Component.literal("Stranger done"));
              end(f);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void deniedInstallationKeepsItemsAndBufferUpgradePreservesContents(
      GameTestHelper h) {
    var f = setup(h);
    var key =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.GOLD_INGOT));
    UUID id = f.buffer.handle().id();
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var stranger = new NetworkPermissions.Actor(UUID.randomUUID(), false);
              h.assertTrue(
                  f.port.installThroughput(stranger, ThroughputTier.ADVANCED)
                          == NetworkAuthority.Decision.DENIED
                      && f.port.tier() == ThroughputTier.BASE,
                  "Foreign actor cannot upgrade private hardware");
              f.buffer.inventory().insert(key, 23);
              var advanced = new ItemStack(ThroughputContent.THROUGHPUT.get(), 2);
              h.assertTrue(
                  use(h, f.player, BUFFER, advanced).consumesAction()
                      && advanced.getCount() == 1
                      && f.buffer.tier() == ThroughputTier.ADVANCED,
                  "Buffer takes the paid first step");
              h.assertTrue(
                  use(h, f.player, BUFFER, advanced) == InteractionResult.FAIL
                      && advanced.getCount() == 1,
                  "Repeating a step consumes nothing");
              var overdrive = new ItemStack(ThroughputContent.OVERDRIVE.get());
              h.assertTrue(
                  use(h, f.player, BUFFER, overdrive).consumesAction() && overdrive.isEmpty(),
                  "Buffer takes exactly one paid second step");
              var portable = f.buffer.portableForBreak();
              h.setBlock(BUFFER, Blocks.AIR);
              h.setBlock(BUFFER, InterfaceContent.state(InterfaceKind.BUFFER, Direction.NORTH));
              BufferedInterfaceEntity moved = h.getBlockEntity(BUFFER);
              moved.place(portable, f.player.getUUID());
              h.assertTrue(
                  moved.handle().id().equals(id)
                      && moved.tier() == ThroughputTier.ENDGAME
                      && moved.inventory().total(key) == 23,
                  "Portable buffer has one owner, all 23 items and its installed tier");
              end(f);
            })
        .thenSucceed();
  }

  /**
   * The removed upgrade is on the cursor, or in the inventory when the change closed the screen (a
   * port re-validates, which makes its open screen stale); the stack is cleared for the next step.
   */
  private static boolean took(ServerPlayer player, ConnectorSettingsMenu menu, Item item) {
    if (player.containerMenu == menu && menu.getCarried().is(item)) {
      menu.setCarried(ItemStack.EMPTY);
      return true;
    }
    var inventory = player.getInventory();
    for (int slot = 0; slot < inventory.getContainerSize(); slot++)
      if (inventory.getItem(slot).is(item)) {
        inventory.setItem(slot, ItemStack.EMPTY);
        return true;
      }
    return false;
  }

  /**
   * Q19: the upgrade slot on the device screen installs the held upgrade, gives the top one back
   * (Overdrive before Throughput) lowering the rate to fit, and refuses a viewer without Edit.
   */
  @GameTest(template = "empty", timeoutTicks = 300)
  public static void upgradeSlotInstallsRemovesInOrderAndClampsTheRate(GameTestHelper h) {
    var f = setup(h);
    var stranger = TestPlayers.mock(h);
    TestPlayers.connect(stranger);
    stranger.setGameMode(GameType.SURVIVAL);
    stranger.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    int button = dev.izzy.factorycore.platform.upgrade.UpgradeSlots.BUTTON;
    // Each change re-validates the port, so every step waits for current wired authority. A
    // change also makes the open screen stale; a mock player is not ticked, so close it here as the
    // server's player tick would.
    java.util.function.Supplier<ConnectorSettingsMenu> menu =
        () -> {
          if (!(f.player.containerMenu instanceof ConnectorSettingsMenu open)
              || !open.stillValid(f.player)) {
            f.player.closeContainer();
            f.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            h.useBlock(PORT, f.player);
            return (ConnectorSettingsMenu) f.player.containerMenu;
          }
          return open;
        };
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var m = menu.get();
              m.setCarried(new ItemStack(ThroughputContent.OVERDRIVE.get()));
              h.assertTrue(
                  !m.clickMenuButton(f.player, button)
                      && m.getCarried().is(ThroughputContent.OVERDRIVE.get())
                      && f.port.tier() == ThroughputTier.BASE,
                  "Overdrive before Throughput is refused and stays on the cursor");
              m.setCarried(new ItemStack(ThroughputContent.THROUGHPUT.get()));
              h.assertTrue(
                  m.clickMenuButton(f.player, button)
                      && m.getCarried().isEmpty()
                      && f.port.tier() == ThroughputTier.ADVANCED,
                  "A held Throughput Upgrade installs and is used up");
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var m = menu.get();
              m.setCarried(new ItemStack(ThroughputContent.OVERDRIVE.get()));
              h.assertTrue(
                  m.clickMenuButton(f.player, button)
                      && f.port.tier() == ThroughputTier.ENDGAME
                      && f.port.settings().rate() == 1024
                      && m.upgradeState() == ThroughputTier.ENDGAME.ordinal(),
                  "Overdrive installs; the slot shows it: " + f.port.settings().rate());
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              // Without Edit: a stranger cannot open the screen, and the slot's server path refuses
              // them too (the check is the device's, not the screen's).
              stranger.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
              h.useBlock(PORT, stranger);
              h.assertFalse(
                  stranger.containerMenu instanceof ConnectorSettingsMenu,
                  "A stranger cannot open the port's screen");
              var foreign = stranger.inventoryMenu;
              h.assertTrue(
                  !dev.izzy.factorycore.platform.upgrade.UpgradeSlots.click(
                          stranger, foreign, f.port)
                      && foreign.getCarried().isEmpty()
                      && f.port.tier() == ThroughputTier.ENDGAME,
                  "A player without Edit cannot take an upgrade out");
              foreign.setCarried(new ItemStack(ThroughputContent.THROUGHPUT.get()));
              h.assertTrue(
                  !dev.izzy.factorycore.platform.upgrade.UpgradeSlots.click(
                          stranger, foreign, f.port)
                      && foreign.getCarried().is(ThroughputContent.THROUGHPUT.get()),
                  "A refused upgrade stays on the cursor");
              stranger.closeContainer();
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var m = menu.get();
              boolean clicked = m.clickMenuButton(f.player, button);
              h.assertTrue(
                  clicked
                      && took(f.player, m, ThroughputContent.OVERDRIVE.get())
                      && f.port.tier() == ThroughputTier.ADVANCED
                      && f.port.settings().rate() == 256,
                  "Overdrive comes out first and the rate drops to the Throughput ceiling: "
                      + f.port.settings().rate());
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.port.configure(
                  f.actor(),
                  new ConnectorSettings(
                      ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 64, 100));
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var m = menu.get();
              boolean clicked = m.clickMenuButton(f.player, button);
              h.assertTrue(
                  clicked
                      && took(f.player, m, ThroughputContent.THROUGHPUT.get())
                      && f.port.tier() == ThroughputTier.BASE
                      && f.port.settings().rate() == 100,
                  "Throughput comes out next; a rate that fits is kept: "
                      + f.port.settings().rate());
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var m = menu.get();
              h.assertTrue(
                  !m.clickMenuButton(f.player, button) && m.getCarried().isEmpty(),
                  "An empty slot gives nothing");
              f.player.closeContainer();
              f.buffer.upgrade(f.actor(), ThroughputTier.ADVANCED);
            })
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> f.buffer.upgrade(f.actor(), ThroughputTier.ENDGAME))
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              // A buffer clamps its rate the same way.
              var buffers = DeviceSavedData.get(f.player.server).buffers();
              var id = f.buffer.handle().id();
              h.assertTrue(
                  buffers.deployment(id).policy().rate() == 576, "Overdrive buffer runs at 576");
              h.assertTrue(
                  f.buffer.removeUpgrade(f.actor()) == ThroughputTier.ENDGAME
                      && f.buffer.tier() == ThroughputTier.ADVANCED
                      && buffers.deployment(id).policy().rate() == 256,
                  "Removing Overdrive lowers the buffer rate to 256");
              stranger.connection.disconnect(Component.literal("Stranger done"));
              end(f);
            })
        .thenSucceed();
  }
}
