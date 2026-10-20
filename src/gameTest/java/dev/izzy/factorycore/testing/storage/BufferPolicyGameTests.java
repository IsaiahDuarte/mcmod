package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.core.storage.*;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.PolicyFixture;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.*;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BufferPolicyGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      BUFFER = new BlockPos(3, 1, 1);

  private record Fixture(
      NetworkPermissions.Actor actor,
      BufferedInterfaceEntity buffer,
      DeviceRegistry.Handle cell,
      DeviceSavedData saved,
      WorldNetworks runtime,
      ResourceKey plain,
      ResourceKey named) {}

  private BufferPolicyGameTests() {}

  private static ItemStack named(int amount) {
    var stack = new ItemStack(Items.IRON_INGOT, amount);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Policy variant"));
    return stack;
  }

  private static Fixture setup(GameTestHelper helper, UUID owner) {
    var actor = new NetworkPermissions.Actor(owner, false);
    helper.setBlock(ROOT, NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(ROOT);
    root.commission(owner);
    helper.setBlock(DRIVE, StorageContent.DRIVE.get());
    StorageDriveEntity drive = helper.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(BUFFER);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), owner);
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    return new Fixture(
        actor,
        buffer,
        CellHandleData.read(drive.cell(0)),
        DeviceSavedData.get(helper.getLevel().getServer()),
        WorldNetworks.get(helper.getLevel().getServer()),
        codec.itemKey(new ItemStack(Items.IRON_INGOT)),
        codec.itemKey(named(1)));
  }

  private static void ready(GameTestHelper helper, Fixture f) {
    helper.assertTrue(
        f.runtime.membership(f.buffer.position()).status() == NetworkTopology.Status.VALID,
        "Policy holder scope ready");
  }

  private static BufferPolicy policy(ResourceFilter.Mode mode, ResourceFilter.Term term, int rate) {
    return new BufferPolicy(new ResourceFilter(ResourceKind.ITEM, mode, List.of(term)), rate);
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void registryImportMovesBothVariantsAndRetainsExcludedRealStock(
      GameTestHelper helper) {
    var f = setup(helper, UUID.randomUUID());
    var gold =
        new PlatformResourceCodec(helper.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.GOLD_INGOT));
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.IMPORT,
                  policy(
                      ResourceFilter.Mode.INCLUDE,
                      new ResourceFilter.Registry(f.plain.registryId()),
                      16));
              f.buffer.inventory().insert(f.plain, 10);
              f.buffer.inventory().insert(f.named, 12);
              f.buffer.inventory().insert(gold, 7);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.plain).total() == 10
                        && f.saved.registry().stock(f.cell, f.named).total() == 12,
                    "Registry filter imports both exact native identities"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, gold).total() == 0
                      && f.buffer.inventory().total(gold) == 7,
                  "Excluded resource remains physically owned");
              f.buffer.configure(f.actor, BufferRegistry.Mode.PAUSED);
              helper.assertTrue(
                  f.saved.buffers().deployment(f.buffer.handle().id()).policy().rate() == 16,
                  "Pause retains configured policy");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void exactImportAndQueuedReplacementPreserveRejectedVariants(
      GameTestHelper helper) {
    var f = setup(helper, UUID.randomUUID());
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.IMPORT,
                  policy(ResourceFilter.Mode.INCLUDE, new ResourceFilter.Exact(f.named), 64));
              f.buffer.inventory().insert(f.named, 5);
              f.buffer.inventory().insert(f.plain, 7);
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, f.named).total() == 5,
                    "Exact selected variant imports"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.buffer.inventory().total(f.plain) == 7, "Different components stay buffered");
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.IMPORT,
                  policy(
                      ResourceFilter.Mode.INCLUDE,
                      new ResourceFilter.Registry(f.plain.registryId()),
                      64));
              for (int i = 0; i < 32 && !f.runtime.imports().status(f.buffer).waiting(); i++)
                f.runtime.imports().tick();
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.IMPORT,
                  policy(
                      ResourceFilter.Mode.EXCLUDE,
                      new ResourceFilter.Registry(f.plain.registryId()),
                      64));
            })
        .thenIdle(40)
        .thenExecute(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.plain) == 7
                        && f.saved.registry().stock(f.cell, f.plain).total() == 0,
                    "Replaced queued definition cannot extract denied key"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void configuredNetworkBudgetIsSharedWithinOneActualTick(GameTestHelper helper) {
    var f = setup(helper, UUID.randomUUID());
    WorldStorageTransfers.Result[] results = new WorldStorageTransfers.Result[2];
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.PAUSED,
                  new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 16));
              f.buffer.inventory().insert(f.plain, 32);
              for (int i = 0; i < 2; i++) {
                int index = i;
                f.runtime
                    .transfers()
                    .submit(
                        UUID.randomUUID(),
                        f.actor,
                        f.buffer.position(),
                        new WorldStorageTransfers.Buffered(
                            f.buffer.handle(), f.buffer.reference().configuration()),
                        new WorldStorageTransfers.Digital(f.cell),
                        f.plain,
                        64,
                        r -> results[index] = r);
              }
              for (int i = 0; i < 16 && (results[0] == null || results[1] == null); i++)
                f.runtime.scheduler().tick();
              helper.assertTrue(
                  results[0] != null && results[1] != null,
                  "Both bounded same-tick submissions complete");
              helper.assertTrue(
                  f.saved.registry().stock(f.cell, f.plain).total() == 16
                      && f.buffer.inventory().total(f.plain) == 16,
                  "Two flows share one configured 16-item output budget");
              helper.assertTrue(
                  results[1].reason() == OperationResult.Reason.RATE_LIMIT,
                  "Exhaustion reports rate limit without extraction");
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.PAUSED,
                  new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 8));
              WorldStorageTransfers.Result[] lowered = {null};
              f.runtime
                  .transfers()
                  .submit(
                      UUID.randomUUID(),
                      f.actor,
                      f.buffer.position(),
                      new WorldStorageTransfers.Buffered(
                          f.buffer.handle(), f.buffer.reference().configuration()),
                      new WorldStorageTransfers.Digital(f.cell),
                      f.plain,
                      16,
                      value -> lowered[0] = value);
              for (int step = 0; step < 16 && lowered[0] == null; step++)
                f.runtime.scheduler().tick();
              helper.assertTrue(
                  lowered[0] != null
                      && lowered[0].reason() == OperationResult.Reason.RATE_LIMIT
                      && f.saved.registry().stock(f.cell, f.plain).total() == 16
                      && f.buffer.inventory().total(f.plain) == 16,
                  "Lowering policy below spent output neither resets its counter nor overflows remaining allowance");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void physicalSamplesRateClearAndTagExclusionDoNotConsumeHeldItems(
      GameTestHelper helper) {
    ServerPlayer player = TestPlayers.mock(helper);
    player.moveTo(Vec3.atCenterOf(helper.absolutePos(ROOT)));
    player.getInventory().clearContent();
    var f = setup(helper, player.getUUID());
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              player.setItemInHand(InteractionHand.OFF_HAND, named(3));
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  f.saved
                          .buffers()
                          .deployment(f.buffer.handle().id())
                          .policy()
                          .filter()
                          .terms()
                          .getFirst()
                      instanceof ResourceFilter.Registry,
                  "Normal sample selects registry identity");
              player.setShiftKeyDown(true);
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  f.saved
                      .buffers()
                      .deployment(f.buffer.handle().id())
                      .policy()
                      .filter()
                      .terms()
                      .getFirst()
                      .equals(new ResourceFilter.Exact(f.named)),
                  "Sneak sample selects actual exact components");
              helper.assertTrue(
                  player.getOffhandItem().getCount() == 3, "Filter sample is not consumed");
              player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.CLOCK));
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  f.saved.buffers().deployment(f.buffer.handle().id()).policy().rate() == 32,
                  "Native clock control lowers network rate");
              player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.PAPER));
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  f.saved.buffers().deployment(f.buffer.handle().id()).policy().filter().mode()
                      == ResourceFilter.Mode.ALL,
                  "Native clear preserves rate and removes selector");
              var tag = new ItemStack(Items.NAME_TAG);
              tag.set(DataComponents.CUSTOM_NAME, Component.literal("#minecraft:logs"));
              player.setItemInHand(InteractionHand.OFF_HAND, tag);
              helper.useBlock(BUFFER, player);
              var filter = f.saved.buffers().deployment(f.buffer.handle().id()).policy().filter();
              helper.assertTrue(
                  filter.mode() == ResourceFilter.Mode.EXCLUDE
                      && filter.terms().getFirst().equals(new ResourceFilter.Tag("minecraft:logs")),
                  "Actual named tag and sneak choose tag exclusion");
              helper.assertTrue(
                  f.saved.buffers().deployment(f.buffer.handle().id()).mode()
                      == BufferRegistry.Mode.PAUSED,
                  "Editing does not deploy a paused preset");
              player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.CHEST));
              helper.useBlock(BUFFER, player);
              helper.assertTrue(
                  f.saved.buffers().deployment(f.buffer.handle().id()).policy().target() == 128,
                  "Chest cycles physical goal without changing selection/rate");
              player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COMPASS));
              for (int step = 0; step < 3; step++) helper.useBlock(BUFFER, player);
              var configured = f.saved.buffers().deployment(f.buffer.handle().id());
              helper.assertTrue(
                  configured.mode() == BufferRegistry.Mode.STOCK
                      && configured.policy().target() == 128
                      && configured.policy().rate() == 32
                      && configured.policy().filter().mode() == ResourceFilter.Mode.EXCLUDE,
                  "Compass reaches Stock and preserves typed policy");
              player.connection.disconnect(Component.literal("Policy controls fixture complete"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void nativeTagRebindingWakesSleepingRuleAndRespectsChangedMembership(
      GameTestHelper helper) {
    var f = setup(helper, UUID.randomUUID());
    var stack = new ItemStack(PolicyFixture.PROBE.get());
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(stack);
    var registry = BuiltInRegistries.ITEM;
    var before = new HashMap<TagKey<Item>, List<Holder<Item>>>();
    registry
        .getTags()
        .forEach(pair -> before.put(pair.getFirst(), pair.getSecond().stream().toList()));
    var rebound = new HashMap<>(before);
    rebound.put(
        TagKey.create(
            Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("factorycore_tests", "policy_probe")),
        List.of());
    registry.bindTags(rebound);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.IMPORT,
                  policy(
                      ResourceFilter.Mode.INCLUDE,
                      new ResourceFilter.Tag("factorycore_tests:policy_probe"),
                      64));
              f.buffer.inventory().insert(key, 4);
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.buffer.inventory().total(key) == 4
                      && f.saved.registry().stock(f.cell, key).total() == 0,
                  "Missing current membership keeps stock and sleeps");
              registry.bindTags(before);
              WorldNetworks.tagsReloaded(
                  new OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.saved.registry().stock(f.cell, key).total() == 4
                        && f.buffer.inventory().total(key) == 0,
                    "Tag rebinding event wakes and imports without new inventory activity"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 400)
  public static void filteredDeploymentStillRequiresGrantsAtAdmissionAndExecution(
      GameTestHelper helper) {
    var f = setup(helper, UUID.randomUUID());
    var guest = new NetworkPermissions.Actor(UUID.randomUUID(), false);
    var policy = policy(ResourceFilter.Mode.INCLUDE, new ResourceFilter.Exact(f.plain), 8);
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              boolean denied = false;
              try {
                f.buffer.configure(guest, BufferRegistry.Mode.IMPORT, policy);
              } catch (IllegalStateException expected) {
                denied = true;
              }
              helper.assertTrue(
                  denied && f.saved.buffers().deployment(f.buffer.handle().id()) == null,
                  "Denied policy edit does not create deployment authority");
              var scope = f.runtime.membership(f.buffer.position()).scope();
              var grants = f.saved.network(scope.network());
              grants.setGrants(
                  f.actor,
                  guest.id(),
                  Set.of(
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY,
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.WITHDRAW,
                      NetworkPermissions.Permission.DEPOSIT));
              f.buffer.configure(guest, BufferRegistry.Mode.IMPORT, policy);
              f.buffer.inventory().insert(f.plain, 8);
              for (int j = 0; j < 32 && !f.runtime.imports().status(f.buffer).waiting(); j++)
                f.runtime.imports().tick();
              grants.setGrants(
                  f.actor,
                  guest.id(),
                  Set.of(
                      NetworkPermissions.Permission.EDIT,
                      NetworkPermissions.Permission.DEPLOY,
                      NetworkPermissions.Permission.VIEW,
                      NetworkPermissions.Permission.DEPOSIT));
            })
        .thenIdle(40)
        .thenExecute(
            () ->
                helper.assertTrue(
                    f.buffer.inventory().total(f.plain) == 8
                        && f.saved.registry().stock(f.cell, f.plain).total() == 0
                        && f.runtime.imports().status(f.buffer).reason()
                            == OperationResult.Reason.DENIED,
                    "Queued resource revocation preserves exact filtered source stock"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 500)
  public static void changedFilterRetainsKnownStagingAndOwnedReturnHonorsNewRate(
      GameTestHelper helper) {
    var f = setup(helper, UUID.randomUUID());
    UUID[] operation = {null};
    WorldStorageTransfers.Result[] returned = {null};
    helper
        .startSequence()
        .thenWaitUntil(() -> ready(helper, f))
        .thenExecute(
            () -> {
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.IMPORT,
                  policy(ResourceFilter.Mode.INCLUDE, new ResourceFilter.Exact(f.plain), 64));
              f.buffer.inventory().insert(f.plain, 15);
              var scope = f.runtime.membership(f.buffer.position()).scope();
              var context =
                  new TransferContext(
                      f.saved.registry().world(),
                      scope.network(),
                      scope.segment(),
                      f.buffer.position(),
                      new TransferContext.Endpoint(
                          TransferContext.Type.BUFFER,
                          f.buffer.handle().id(),
                          f.buffer.handle().id(),
                          f.buffer.handle().generation(),
                          f.buffer.reference().configuration()),
                      new TransferContext.Endpoint(
                          TransferContext.Type.DIGITAL,
                          f.cell.backing(),
                          f.cell.backing(),
                          f.cell.generation(),
                          0));
              ResourcePort rejecting =
                  new ResourcePort() {
                    public UUID backingId() {
                      return f.cell.backing();
                    }

                    public long simulateInsert(ResourceKey key, long amount) {
                      return amount;
                    }

                    public long insert(ResourceKey key, long amount) {
                      return 0;
                    }

                    public long extract(ResourceKey key, long amount) {
                      throw new AssertionError("Never extract destination");
                    }
                  };
              operation[0] = UUID.randomUUID();
              f.saved
                  .transfers()
                  .start(
                      operation[0],
                      f.actor.id(),
                      f.plain,
                      f.buffer.inventory(),
                      rejecting,
                      12,
                      12,
                      () -> true,
                      context);
              f.saved.transfers().unbind(operation[0]);
              f.buffer.inventory().insert(f.plain, 4);
              f.buffer.configure(
                  f.actor,
                  BufferRegistry.Mode.IMPORT,
                  policy(
                      ResourceFilter.Mode.EXCLUDE,
                      new ResourceFilter.Registry(f.plain.registryId()),
                      8));
            })
        .thenIdle(40)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  f.saved.transfers().get(operation[0]).staged() == 12
                      && f.buffer.inventory().total(f.plain) == 7
                      && f.saved.registry().stock(f.cell, f.plain).total() == 0,
                  "New selector never delivers old staging or extracts refilled items");
              var admission = f.buffer.returnStaging(f.actor, result -> returned[0] = result);
              helper.assertTrue(
                  admission.rejection() == OperationResult.Reason.OK,
                  "Original owner admits known return despite new exclusion");
            })
        .thenWaitUntil(() -> helper.assertTrue(returned[0] != null, "First owned return completes"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  returned[0].transfer().staged() == 4 && f.buffer.inventory().total(f.plain) == 15,
                  "Configured eight-item input budget retains exact return remainder");
              returned[0] = null;
            })
        .thenIdle(2)
        .thenExecute(
            () -> {
              var admission = f.buffer.returnStaging(f.actor, result -> returned[0] = result);
              helper.assertTrue(
                  admission.rejection() == OperationResult.Reason.OK,
                  "Remaining known return admits in a later tick");
            })
        .thenWaitUntil(() -> helper.assertTrue(returned[0] != null, "Final owned return completes"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    !f.saved.transfers().contains(operation[0])
                        && f.buffer.inventory().total(f.plain) == 19
                        && f.saved.registry().stock(f.cell, f.plain).total() == 0,
                    "All original plus refilled items conserved and completed journal retired"))
        .thenSucceed();
  }
}
