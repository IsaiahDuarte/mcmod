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
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

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

  @SuppressWarnings("removal")
  private static Fixture setup(GameTestHelper h) {
    var player = h.makeMockServerPlayerInLevel();
    NetworkRegistry.configureMockConnection(player.connection.getConnection());
    player.setGameMode(GameType.SURVIVAL);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    ((NetworkNodeEntity) h.getBlockEntity(ROOT)).commission(player.getUUID());
    h.setBlock(
        PORT,
        ConnectorContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    ConnectorEntity port = h.getBlockEntity(PORT);
    port.place(new ItemStack(ConnectorContent.ITEM.get()));
    h.setBlock(PROVIDER, Blocks.BARREL);
    BarrelBlockEntity barrel = h.getBlockEntity(PROVIDER);
    for (int slot = 0; slot < barrel.getContainerSize(); slot++)
      barrel.setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
    h.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = h.getBlockEntity(BUFFER);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), player.getUUID());
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
              h.setBlock(
                  PORT,
                  ConnectorContent.BLOCK
                      .get()
                      .defaultBlockState()
                      .setValue(BlockStateProperties.FACING, Direction.EAST));
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
              h.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
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
}
