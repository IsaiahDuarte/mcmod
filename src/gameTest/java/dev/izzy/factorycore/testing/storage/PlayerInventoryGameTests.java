package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class PlayerInventoryGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1);
  private static final BlockPos DRIVE = new BlockPos(2, 1, 1);

  private record Fixture(
      ServerPlayer player,
      NetworkPermissions.Actor actor,
      NetworkNodeEntity root,
      DeviceRegistry.Handle cell,
      DeviceSavedData saved,
      WorldNetworks runtime,
      NodeRegistry.Position origin,
      ResourceKey key) {}

  private PlayerInventoryGameTests() {}

  private static ItemStack named(int amount) {
    var stack = new ItemStack(Items.IRON_INGOT, amount);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Authenticated player transfer"));
    return stack;
  }

  @SuppressWarnings("removal") // Pinned helper performs real PlayerList/connection registration.
  private static ServerPlayer login(GameTestHelper helper) {
    var player = helper.makeMockServerPlayerInLevel();
    player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    player.getInventory().clearContent();
    return player;
  }

  private static Fixture setup(GameTestHelper helper) {
    var player = login(helper);
    var actor = new NetworkPermissions.Actor(player.getUUID(), false);
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(actor.id());
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    return new Fixture(
        player,
        actor,
        root,
        CellHandleData.read(drive.cell(0)),
        DeviceSavedData.get(helper.getLevel().getServer()),
        WorldNetworks.get(helper.getLevel().getServer()),
        new NodeRegistry.Position(
            helper.getLevel().dimension().location().toString(), helper.absolutePos(ROOT).asLong()),
        new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(named(1)));
  }

  private static void ready(GameTestHelper helper, Fixture fixture) {
    helper.assertTrue(
        fixture.runtime().membership(fixture.origin()).status() == NetworkTopology.Status.VALID,
        "Player operation wired origin ready");
  }

  private static void logout(Fixture fixture) {
    fixture.player().connection.disconnect(Component.literal("Completed player inventory fixture"));
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void exactPlayerDepositUsesSharedAccountingAndDurableAcceptedSlot(
      GameTestHelper helper) {
    var fixture = setup(helper);
    WorldStorageTransfers.Result[] result = {null};
    var id = UUID.randomUUID();
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.player().getInventory().setItem(0, named(64));
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      id,
                      fixture.actor(),
                      fixture.origin(),
                      fixture.runtime().transfers().inventory(fixture.player(), 0),
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.key(),
                      64,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Deposit callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].transfer().delivered() == 64
                      && fixture.player().getInventory().getItem(0).isEmpty()
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 64,
                  "Exact component items move from one native player slot through owned staging");
              var context = fixture.saved().transfers().context(id);
              helper.assertTrue(
                  context.source().type() == TransferContext.Type.PLAYER
                      && context.source().device().equals(fixture.player().getUUID())
                      && context.source().configuration() == 1,
                  "Canonical context retains actor, connection and selected source slot");
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void fullMainInventoryDoesNotExtractOrUseArmorOffhandOrCursor(
      GameTestHelper helper) {
    var fixture = setup(helper);
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              for (int slot = 0; slot < 36; slot++)
                fixture.player().getInventory().setItem(slot, new ItemStack(Items.GOLD_INGOT, 64));
              fixture.saved().registry().insert(fixture.cell(), fixture.key(), 64);
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      fixture.actor(),
                      fixture.origin(),
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.runtime().transfers().inventory(fixture.player(), -1),
                      fixture.key(),
                      64,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Full inventory callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].reason() == OperationResult.Reason.FULL
                      && result[0].transfer() == null
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 64
                      && fixture.player().getInventory().getItem(36).isEmpty()
                      && fixture.player().getInventory().getItem(40).isEmpty(),
                  "Destination preflight retains network stock and excludes equipment/offhand");
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void partialWithdrawalFillsMatchingStacksBeforeEmptyAndPreservesComponents(
      GameTestHelper helper) {
    var fixture = setup(helper);
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              for (int slot = 0; slot < 36; slot++)
                fixture.player().getInventory().setItem(slot, new ItemStack(Items.GOLD_INGOT, 64));
              fixture.player().getInventory().setItem(7, named(61));
              fixture.saved().registry().insert(fixture.cell(), fixture.key(), 64);
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      fixture.actor(),
                      fixture.origin(),
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.runtime().transfers().inventory(fixture.player(), -1),
                      fixture.key(),
                      64,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Partial callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].transfer().delivered() == 3
                      && result[0].transfer().staged() == 0
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 61
                      && fixture.player().getInventory().getItem(7).getCount() == 64
                      && fixture
                          .player()
                          .getInventory()
                          .getItem(7)
                          .get(DataComponents.CUSTOM_NAME)
                          .equals(named(1).get(DataComponents.CUSTOM_NAME)),
                  "Actual three-item admission preserves all remaining quantities and exact metadata");
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void slotReplacementAndAnotherPrincipalCannotSpendPlayerInventory(
      GameTestHelper helper) {
    var fixture = setup(helper);
    WorldStorageTransfers.Result[] result = {null, null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.player().getInventory().setItem(0, named(64));
              var endpoint = fixture.runtime().transfers().inventory(fixture.player(), 0);
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      fixture.actor(),
                      fixture.origin(),
                      endpoint,
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.key(),
                      64,
                      value -> result[0] = value);
              fixture.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND, 64));
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      new NetworkPermissions.Actor(UUID.randomUUID(), true),
                      fixture.origin(),
                      endpoint,
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.key(),
                      64,
                      value -> result[1] = value);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    result[0] != null && result[1] != null, "Both protected callbacks"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].reason() == OperationResult.Reason.SHORTAGE
                      && result[0].transfer() == null
                      && result[1].reason() == OperationResult.Reason.DENIED
                      && result[1].transfer() == null
                      && fixture.player().getInventory().getItem(0).is(Items.DIAMOND)
                      && fixture.player().getInventory().getItem(0).getCount() == 64
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 0,
                  "Queued exact key and original principal revalidate before mutation");
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void depositOnlyPrincipalCanDepositOwnItemsButCannotWithdraw(
      GameTestHelper helper) {
    var fixture = setup(helper);
    var guest = login(helper);
    var actor = new NetworkPermissions.Actor(guest.getUUID(), false);
    WorldStorageTransfers.Result[] result = {null, null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture
                  .saved()
                  .network(fixture.root().handle().id())
                  .setGrants(
                      fixture.actor(), actor.id(), Set.of(NetworkPermissions.Permission.DEPOSIT));
              guest.getInventory().setItem(0, named(17));
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      actor,
                      fixture.origin(),
                      fixture.runtime().transfers().inventory(guest, 0),
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.key(),
                      17,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Deposit-only callback"))
        .thenExecute(
            () ->
                fixture
                    .runtime()
                    .transfers()
                    .submit(
                        UUID.randomUUID(),
                        actor,
                        fixture.origin(),
                        new WorldStorageTransfers.Digital(fixture.cell()),
                        fixture.runtime().transfers().inventory(guest, -1),
                        fixture.key(),
                        17,
                        value -> result[1] = value))
        .thenWaitUntil(() -> helper.assertTrue(result[1] != null, "Forbidden withdrawal callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].transfer().delivered() == 17
                      && result[1].reason() == OperationResult.Reason.DENIED
                      && guest.getInventory().getItem(0).isEmpty()
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 17,
                  "Local ownership is not a network WITHDRAW/VIEW requirement for depositing own items");
              guest.connection.disconnect(Component.literal("Completed guest fixture"));
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void playerWrappersShareOutputRateAndIndependentInputAllowance(
      GameTestHelper helper) {
    var fixture = setup(helper);
    WorldStorageTransfers.Result[] result = {null, null, null, null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.player().getInventory().setItem(0, named(64));
              fixture.player().getInventory().setItem(1, named(64));
              fixture.player().getInventory().setItem(2, named(17));
              for (int slot = 0; slot < 3; slot++) {
                int index = slot;
                fixture
                    .runtime()
                    .transfers()
                    .submit(
                        UUID.randomUUID(),
                        fixture.actor(),
                        fixture.origin(),
                        fixture.runtime().transfers().inventory(fixture.player(), slot),
                        new WorldStorageTransfers.Digital(fixture.cell()),
                        fixture.key(),
                        slot == 2 ? 17 : 64,
                        value -> result[index] = value);
              }
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      fixture.actor(),
                      fixture.origin(),
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.runtime().transfers().inventory(fixture.player(), -1),
                      fixture.key(),
                      64,
                      value -> result[3] = value);
              for (int step = 0;
                  step < 16 && java.util.Arrays.stream(result).anyMatch(value -> value == null);
                  step++) fixture.runtime().scheduler().tick();
              helper.assertTrue(
                  java.util.Arrays.stream(result).allMatch(value -> value != null),
                  "Bounded same-server-tick fixture dispatch");
              helper.assertTrue(
                  result[0].transfer().delivered() == 64
                      && result[1].transfer().delivered() == 64
                      && result[2].reason() == OperationResult.Reason.RATE_LIMIT
                      && result[2].transfer() == null
                      && result[3].transfer().delivered() == 64
                      && fixture.player().getInventory().getItem(2).getCount() == 64
                      && fixture.player().getInventory().getItem(0).getCount() == 17
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 64,
                  "All source wrappers share 128 items; independent input can return known stock");
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void queuedPlayerWithdrawalRechecksRevocationAndDistance(GameTestHelper helper) {
    var fixture = setup(helper);
    var guest = login(helper);
    var actor = new NetworkPermissions.Actor(guest.getUUID(), false);
    WorldStorageTransfers.Result[] result = {null, null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.saved().registry().insert(fixture.cell(), fixture.key(), 64);
              var grants = fixture.saved().network(fixture.root().handle().id());
              grants.setGrants(
                  fixture.actor(), actor.id(), Set.of(NetworkPermissions.Permission.WITHDRAW));
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      actor,
                      fixture.origin(),
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.runtime().transfers().inventory(guest, -1),
                      fixture.key(),
                      64,
                      value -> result[0] = value);
              grants.setGrants(fixture.actor(), actor.id(), Set.of());
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      fixture.actor(),
                      fixture.origin(),
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.runtime().transfers().inventory(fixture.player(), -1),
                      fixture.key(),
                      64,
                      value -> result[1] = value);
              fixture.player().moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)).add(32, 0, 0));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    result[0] != null && result[1] != null, "Both revalidation callbacks"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].reason() == OperationResult.Reason.DENIED
                      && result[0].transfer() == null
                      && result[1].reason() == OperationResult.Reason.DENIED
                      && result[1].transfer() == null
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 64
                      && guest.getInventory().getItem(0).isEmpty()
                      && fixture.player().getInventory().getItem(0).isEmpty(),
                  "Neither revoked nor remote queued inventory request extracts stock");
              guest.connection.disconnect(Component.literal("Completed revoked fixture"));
              logout(fixture);
            })
        .thenSucceed();
  }

  private static UUID staged(
      Fixture fixture,
      NetworkPermissions.Actor actor,
      ServerPlayer owner,
      WorldStorageTransfers.PlayerInventory endpoint) {
    var id = UUID.randomUUID();
    var source =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return endpoint.backing();
          }

          @Override
          public long simulateInsert(ResourceKey key, long amount) {
            return amount;
          }

          @Override
          public long insert(ResourceKey key, long amount) {
            throw new AssertionError("Production recovery must use current inventory port");
          }

          @Override
          public long extract(ResourceKey key, long amount) {
            return owner.getInventory().removeItem(endpoint.slot(), (int) amount).getCount();
          }
        };
    var target =
        new ResourcePort() {
          @Override
          public UUID backingId() {
            return fixture.cell().backing();
          }

          @Override
          public long simulateInsert(ResourceKey key, long amount) {
            return amount;
          }

          @Override
          public long insert(ResourceKey key, long amount) {
            return 0;
          }

          @Override
          public long extract(ResourceKey key, long amount) {
            throw new AssertionError("Recovery never re-extracts target");
          }
        };
    var scope = fixture.runtime().membership(fixture.origin()).scope();
    var context =
        new TransferContext(
            fixture.saved().registry().world(),
            scope.network(),
            scope.segment(),
            fixture.origin(),
            new TransferContext.Endpoint(
                TransferContext.Type.PLAYER,
                owner.getUUID(),
                endpoint.backing(),
                endpoint.connection(),
                endpoint.slot() + 1),
            new TransferContext.Endpoint(
                TransferContext.Type.DIGITAL,
                fixture.cell().backing(),
                fixture.cell().backing(),
                fixture.cell().generation(),
                0));
    fixture
        .saved()
        .transfers()
        .start(id, actor.id(), fixture.key(), source, target, 64, 64, () -> true, context);
    fixture.saved().transfers().unbind(id);
    return id;
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void
      ownedPlayerCleanupAfterRevocationKeepsBlockedSlotStagingAndNeverReextractsRefill(
          GameTestHelper helper) {
    var fixture = setup(helper);
    var guest = login(helper);
    var actor = new NetworkPermissions.Actor(guest.getUUID(), false);
    UUID[] record = {null};
    WorldStorageTransfers.Result[] result = {null, null, null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              var grants = fixture.saved().network(fixture.root().handle().id());
              grants.setGrants(
                  fixture.actor(), actor.id(), Set.of(NetworkPermissions.Permission.DEPOSIT));
              guest.getInventory().setItem(0, named(64));
              var endpoint = fixture.runtime().transfers().inventory(guest, 0);
              record[0] = staged(fixture, actor, guest, endpoint);
              guest.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 64));
              grants.setGrants(fixture.actor(), actor.id(), Set.of());
              var admission =
                  fixture
                      .runtime()
                      .transfers()
                      .progress(record[0], actor, true, value -> result[0] = value);
              helper.assertTrue(
                  admission.rejection() == OperationResult.Reason.OK,
                  "Original owner can request owned cleanup after grant revocation");
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Blocked-slot return callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[0].transfer().staged() == 64
                      && guest.getInventory().getItem(0).is(Items.DIAMOND)
                      && guest.getInventory().getItem(0).getCount() == 64,
                  "Mismatched original slot preserves both refill and owned staging");
              var refill = guest.getInventory().getItem(0).copy();
              guest.getInventory().setItem(1, refill);
              guest.getInventory().setItem(0, ItemStack.EMPTY);
              fixture
                  .runtime()
                  .transfers()
                  .progress(record[0], actor, true, value -> result[1] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[1] != null, "Owned cleanup callback"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  result[1].transfer().staged() == 0
                      && result[1].transfer().returned() == 64
                      && guest.getInventory().getItem(0).getCount() == 64
                      && guest
                          .getInventory()
                          .getItem(0)
                          .get(DataComponents.CUSTOM_NAME)
                          .equals(named(1).get(DataComponents.CUSTOM_NAME))
                      && guest.getInventory().getItem(1).is(Items.DIAMOND)
                      && guest.getInventory().getItem(1).getCount() == 64
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 0,
                  "Known staging returns to the exact original slot; refill is never re-extracted or discarded");
              guest.connection.disconnect(Component.literal("Completed cleanup fixture"));
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void playerEndpointIsNotAnEnumerableGlobalCatalogCursor(GameTestHelper helper) {
    var fixture = setup(helper);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.player().getInventory().setItem(0, named(64));
              var endpoint = fixture.runtime().transfers().inventory(fixture.player(), 0);
              var scope = fixture.runtime().membership(fixture.origin()).scope();
              boolean denied = false;
              try {
                new WorldStorageCatalog.Cursor(
                    false,
                    null,
                    endpoint,
                    null,
                    0,
                    scope,
                    fixture.saved().network(scope.network()).generation(),
                    fixture.actor().id(),
                    ResourceKind.ITEM);
              } catch (IllegalArgumentException expected) {
                denied = true;
              }
              helper.assertTrue(
                  denied && fixture.player().getInventory().getItem(0).getCount() == 64,
                  "Private player inventory cannot become a globally enumerable stock source");
              logout(fixture);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void logoutInvalidatesAlreadyQueuedPlayerInventoryAccess(GameTestHelper helper) {
    var fixture = setup(helper);
    WorldStorageTransfers.Result[] result = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.player().getInventory().setItem(0, named(64));
              var endpoint = fixture.runtime().transfers().inventory(fixture.player(), 0);
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      fixture.actor(),
                      fixture.origin(),
                      endpoint,
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.key(),
                      64,
                      value -> result[0] = value);
              fixture
                  .player()
                  .connection
                  .disconnect(Component.literal("Disconnect queued inventory request"));
              fixture.player().server.getPlayerList().remove(fixture.player());
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Disconnected request callback"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    result[0].reason() == OperationResult.Reason.DENIED
                        && result[0].transfer() == null
                        && fixture.player().getInventory().getItem(0).getCount() == 64
                        && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                            == 0,
                    "A stale live-player binding cannot mutate a logged-out inventory"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void playerPendingIntentCannotReplayRefundOrAllowFreshExtraction(
      GameTestHelper helper) {
    var fixture = setup(helper);
    WorldStorageTransfers.Result[] result = {null};
    UUID[] record = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, fixture))
        .thenExecute(
            () -> {
              fixture.player().getInventory().setItem(0, named(64));
              var endpoint = fixture.runtime().transfers().inventory(fixture.player(), 0);
              var scope = fixture.runtime().membership(fixture.origin()).scope();
              var context =
                  new TransferContext(
                      fixture.saved().registry().world(),
                      scope.network(),
                      scope.segment(),
                      fixture.origin(),
                      new TransferContext.Endpoint(
                          TransferContext.Type.PLAYER,
                          fixture.player().getUUID(),
                          endpoint.backing(),
                          endpoint.connection(),
                          1),
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL,
                          fixture.cell().backing(),
                          fixture.cell().backing(),
                          fixture.cell().generation(),
                          0));
              var source =
                  new ResourcePort() {
                    @Override
                    public UUID backingId() {
                      return endpoint.backing();
                    }

                    @Override
                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    @Override
                    public long insert(ResourceKey key, long amount) {
                      throw new AssertionError("Uncertainty cannot refund");
                    }

                    @Override
                    public long extract(ResourceKey key, long amount) {
                      fixture.player().getInventory().removeItem(0, (int) amount);
                      throw new IllegalStateException("Unknown native extraction result");
                    }
                  };
              var target =
                  new ResourcePort() {
                    @Override
                    public UUID backingId() {
                      return fixture.cell().backing();
                    }

                    @Override
                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    @Override
                    public long insert(ResourceKey key, long amount) {
                      throw new AssertionError("Uncertain source cannot deliver");
                    }

                    @Override
                    public long extract(ResourceKey key, long amount) {
                      throw new AssertionError("Never re-extract");
                    }
                  };
              record[0] = UUID.randomUUID();
              fixture
                  .saved()
                  .transfers()
                  .start(
                      record[0],
                      fixture.actor().id(),
                      fixture.key(),
                      source,
                      target,
                      64,
                      64,
                      () -> true,
                      context);
              fixture.saved().transfers().unbind(record[0]);
              fixture.player().getInventory().setItem(0, named(17));
              var rejected =
                  fixture
                      .runtime()
                      .transfers()
                      .progress(
                          record[0],
                          fixture.actor(),
                          true,
                          value -> {
                            throw new AssertionError("Uncertainty cannot dispatch");
                          });
              helper.assertTrue(
                  rejected.rejection() == OperationResult.Reason.UNCERTAIN,
                  "Pending intent never queues a refund");
              fixture
                  .runtime()
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      fixture.actor(),
                      fixture.origin(),
                      endpoint,
                      new WorldStorageTransfers.Digital(fixture.cell()),
                      fixture.key(),
                      17,
                      value -> result[0] = value);
            })
        .thenWaitUntil(() -> helper.assertTrue(result[0] != null, "Blocked fresh request callback"))
        .thenExecute(
            () -> {
              var snapshot = fixture.saved().transfers().get(record[0]);
              helper.assertTrue(
                  result[0].reason() == OperationResult.Reason.RESERVED
                      && result[0].transfer() == null
                      && snapshot.state() == StagedTransfer.State.QUARANTINED
                      && snapshot.pending() == StagedTransfer.PendingCall.EXTRACT
                      && fixture.player().getInventory().getItem(0).getCount() == 17
                      && fixture.saved().registry().stock(fixture.cell(), fixture.key()).total()
                          == 0,
                  "Uncertain canonical evidence blocks fresh extraction and retains the refill");
              logout(fixture);
            })
        .thenSucceed();
  }
}
