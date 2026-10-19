package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Transfer Recovery screen (ADR 0055): the same original-owner, evidence-first and operator-only
 * rules as the recovery commands, with sessions that refuse forged record ids and other players.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class RecoveryScreenGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), DRIVE = new BlockPos(3, 1, 1);
  private static final ResourceKey KEY =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private RecoveryScreenGameTests() {}

  private static ResourcePort cell(DeviceSavedData saved, DeviceRegistry.Handle handle) {
    return new ResourcePort() {
      public UUID backingId() {
        return handle.backing();
      }

      public long simulateInsert(ResourceKey key, long amount) {
        return saved.registry().simulateInsert(handle, key, amount).amount();
      }

      public long extract(ResourceKey key, long amount) {
        return saved.registry().extract(handle, key, amount, null).amount();
      }

      public long insert(ResourceKey key, long amount) {
        return saved.registry().insert(handle, key, amount).amount();
      }
    };
  }

  /** Accepts nothing, or throws mid-insert to leave an uncertain outcome. */
  private static ResourcePort endpoint(UUID backing, boolean failInsert) {
    return new ResourcePort() {
      public UUID backingId() {
        return backing;
      }

      public long simulateInsert(ResourceKey key, long amount) {
        return amount;
      }

      public long extract(ResourceKey key, long amount) {
        return 0;
      }

      public long insert(ResourceKey key, long amount) {
        if (failInsert) throw new IllegalStateException("Injected uncertain insert");
        return 0;
      }
    };
  }

  private static ServerPlayer player(GameTestHelper h) {
    @SuppressWarnings("removal")
    var player = h.makeMockServerPlayerInLevel();
    net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(
        player.connection.getConnection());
    return player;
  }

  private static RecoveryMenu open(ServerPlayer player) {
    RecoveryMenu.open(player);
    var menu = (RecoveryMenu) player.containerMenu;
    menu.broadcastChanges();
    return menu;
  }

  private static void send(RecoveryMenu menu, ServerPlayer sender, int action, UUID record) {
    menu.command(
        sender, new RecoveryPackets.Command(menu.containerId, menu.session(), action, record));
  }

  private static RecoveryPackets.Row row(RecoveryMenu menu, UUID id) {
    return menu.view().rows().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
  }

  private static boolean evidence(ServerPlayer player, UUID id) {
    var folder =
        player
            .server
            .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("factorycore-recovery");
    if (!java.nio.file.Files.isDirectory(folder)) return false;
    try (var files = java.nio.file.Files.list(folder)) {
      return files.anyMatch(path -> path.getFileName().toString().startsWith(id.toString()));
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void recoveryScreenSettlesOwnRecordsAndOperatorsRetireOnlyCompleteOnes(
      GameTestHelper h) {
    var owner = player(h);
    var other = player(h);
    var operator = player(h);
    h.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = h.getBlockEntity(ROOT);
    root.commission(owner.getUUID());
    h.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    h.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = h.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    var source = CellHandleData.read(drive.cell(0));
    var target = CellHandleData.read(drive.cell(1));
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var drivePosition =
        new NodeRegistry.Position(
            h.getLevel().dimension().location().toString(), h.absolutePos(DRIVE).asLong());
    // Ids that sort before any random UUID, so the operator's first page shows them even while
    // other tests in the shared GameTest world have their own records.
    long low = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE - 1);
    var legacy = new UUID(Long.MIN_VALUE, low);
    var uncertain = new UUID(Long.MIN_VALUE, low + 1);
    RecoveryMenu[] menus = new RecoveryMenu[3];
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer()).membership(drivePosition).status()
                        == NetworkTopology.Status.VALID,
                    "Storage ready"))
        .thenExecute(
            () -> {
              saved.registry().insert(source, KEY, 45);
              saved
                  .transfers()
                  .start(
                      legacy,
                      owner.getUUID(),
                      KEY,
                      cell(saved, source),
                      endpoint(UUID.randomUUID(), false),
                      12,
                      12,
                      () -> true);
              saved.transfers().unbind(legacy);
              saved
                  .transfers()
                  .start(
                      uncertain,
                      owner.getUUID(),
                      KEY,
                      cell(saved, source),
                      endpoint(target.backing(), true),
                      5,
                      5,
                      () -> true);
              h.assertTrue(
                  saved.registry().stock(source, KEY).total() == 28,
                  "Two records own 17 staged items; the cell keeps 28");
              var mine = menus[0] = open(owner);
              h.assertTrue(
                  "legacy".equals(row(mine, legacy).classification())
                      && "uncertain".equals(row(mine, uncertain).classification())
                      && row(mine, legacy).mine()
                      && !mine.view().operator(),
                  "The original owner sees both records with their safe class: " + mine.view());
              var theirs = menus[1] = open(other);
              h.assertTrue(
                  theirs.view().rows().isEmpty(),
                  "Another player sees none of the owner's records");
              send(theirs, other, RecoveryPackets.SETTLE, legacy);
              h.assertTrue(
                  theirs.view().status().equals("missing")
                      && saved.transfers().get(legacy).staged() == 12
                      && !evidence(owner, legacy),
                  "A forged id that is not on the session's page is refused without evidence");
              // Forged: the other player sends into the owner's session.
              send(mine, other, RecoveryPackets.SETTLE, legacy);
              send(theirs, other, RecoveryPackets.RETIRE, legacy);
              h.assertTrue(
                  saved.transfers().get(legacy).staged() == 12
                      && saved.transfers().contains(legacy)
                      && theirs.view().status().equals("missing"),
                  "Wrong-session and rate-limited commands change nothing");
              send(mine, owner, RecoveryPackets.RETURN, legacy);
              h.assertTrue(
                  mine.view().status().equals("legacy") && !evidence(owner, legacy),
                  "Return refuses a record without endpoint context: " + mine.view().status());
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var mine = menus[0];
              var before = saved.transfers().get(uncertain);
              send(mine, owner, RecoveryPackets.SETTLE, uncertain);
              h.assertTrue(
                  mine.view().status().equals("uncertain")
                      && before.equals(saved.transfers().get(uncertain))
                      && !evidence(owner, uncertain),
                  "Uncertain outcomes are never replayed or refunded");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var mine = menus[0];
              send(mine, owner, RecoveryPackets.RETIRE, legacy);
              h.assertTrue(
                  mine.view().status().equals("operator") && saved.transfers().contains(legacy),
                  "A non-operator cannot retire");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var mine = menus[0];
              send(mine, owner, RecoveryPackets.SETTLE, legacy);
              h.assertTrue(
                  mine.view().status().equals("queued")
                      && !mine.view().evidence().isEmpty()
                      && evidence(owner, legacy),
                  "The owner's settlement preserves evidence before it is queued: " + mine.view());
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    saved.transfers().get(legacy).state() == StagedTransfer.State.COMPLETE,
                    "Settlement completes"))
        .thenExecute(
            () -> {
              h.assertTrue(
                  saved.transfers().get(legacy).returned() == 12
                      && saved.registry().stock(source, KEY).total() == 40,
                  "Exactly the staged items returned; nothing extracted again");
              owner.closeContainer();
              other.closeContainer();
              // GameTest servers grant level 0 through op(); add a level-2 entry explicitly.
              operator
                  .server
                  .getPlayerList()
                  .getOps()
                  .add(
                      new net.minecraft.server.players.ServerOpListEntry(
                          operator.getGameProfile(), 2, false));
              h.assertTrue(operator.hasPermissions(2), "Test operator has level 2");
              var ops = menus[2] = open(operator);
              h.assertTrue(
                  ops.view().operator()
                      && "complete".equals(row(ops, legacy).classification())
                      && !row(ops, legacy).mine()
                      && "uncertain".equals(row(ops, uncertain).classification()),
                  "Operators page every record: " + ops.view());
              send(ops, operator, RecoveryPackets.SETTLE, uncertain);
              h.assertTrue(
                  ops.view().status().equals("owner"),
                  "Operators cannot adopt another player's cleanup");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var ops = menus[2];
              send(ops, operator, RecoveryPackets.RETIRE, uncertain);
              h.assertTrue(
                  ops.view().status().equals("not_complete")
                      && saved.transfers().contains(uncertain),
                  "Uncertain staging cannot be retired");
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var ops = menus[2];
              send(ops, operator, RecoveryPackets.RETIRE, legacy);
              h.assertTrue(
                  ops.view().status().equals("retired")
                      && !saved.transfers().contains(legacy)
                      && row(ops, legacy) == null
                      && saved.registry().stock(source, KEY).total() == 40,
                  "The operator retires the complete empty record after preserving evidence");
              operator.closeContainer();
              operator.server.getPlayerList().getOps().remove(operator.getGameProfile());
              for (var player : new ServerPlayer[] {owner, other, operator})
                player.connection.disconnect(Component.literal("done"));
            })
        .thenSucceed();
  }
}
