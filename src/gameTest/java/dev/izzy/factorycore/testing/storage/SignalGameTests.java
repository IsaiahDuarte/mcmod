package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceBlock;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.InterfaceSwitch;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * C1: a Signal-mode Interface emits redstone from network stock or a running job, re-evaluated at
 * most once a second, under its owner's current VIEW; it turns off when that or the network goes.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class SignalGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      SIGNAL = new BlockPos(1, 1, 2),
      LAMP = new BlockPos(1, 1, 3),
      UNIT = new BlockPos(0, 1, 1);

  /** Long enough for at least one scheduled evaluation. */
  private static final int SETTLE = 2 * 20 + 5;

  private record Fixture(
      ServerPlayer owner,
      SignalInterfaceEntity signal,
      StorageDriveEntity drive,
      ResourceKey cobble) {}

  private SignalGameTests() {}

  private static Fixture setup(GameTestHelper h) {
    var owner = TestPlayers.mock(h);
    TestPlayers.connect(owner);
    owner.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    Net.in(h, owner.getUUID()).controller(ROOT);
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(SIGNAL, InterfaceContent.state(InterfaceKind.SIGNAL, Direction.NORTH));
    SignalInterfaceEntity signal = h.getBlockEntity(SIGNAL);
    signal.place(owner.getUUID());
    h.setBlock(LAMP, Blocks.REDSTONE_LAMP);
    var cobble =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));
    return new Fixture(owner, signal, drive, cobble);
  }

  private static void stock(GameTestHelper h, Fixture f, long amount) {
    var registry = DeviceSavedData.get(h.getLevel().getServer()).registry();
    var cell = CellHandleData.read(f.drive.cell(0));
    long now = registry.stock(cell, f.cobble).total();
    if (amount > now) registry.insert(cell, f.cobble, amount - now);
    else if (amount < now) registry.extract(cell, f.cobble, now - amount, null);
  }

  private static boolean lit(GameTestHelper h) {
    return h.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT);
  }

  private static void ready(GameTestHelper h, Fixture f) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer())
                    .membership(Positions.of(h.getLevel(), h.absolutePos(SIGNAL)))
                    .status()
                == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID
            && DeviceSavedData.get(h.getLevel().getServer())
                .registry()
                .stock(CellHandleData.read(f.drive.cell(0)), f.cobble)
                .loaded(),
        "Signal on a valid network with its cell online");
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void stockThresholdLightsALampAndProportionalScales(GameTestHelper h) {
    var f = setup(h);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              f.signal.configure(
                  f.owner.getUUID(),
                  new SignalRule(
                      SignalRule.Condition.STOCK,
                      SignalRule.Compare.AT_LEAST,
                      10,
                      SignalRule.Output.FULL),
                  f.cobble);
              stock(h, f, 9);
            })
        .thenIdle(SETTLE)
        .thenExecute(
            () ->
                h.assertTrue(
                    f.signal.power() == 0 && !lit(h) && f.signal.reason().isEmpty(),
                    "Nine of ten: off and checked"))
        .thenExecute(() -> stock(h, f, 10))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.signal.power() == 15 && lit(h), "Ten of ten: 15 and the lamp is lit"))
        .thenIdle(1)
        .thenExecute(
            () ->
                // Regression: a strength change once told network neighbors and rebuilt the
                // network, briefly refusing EDIT and VIEW everywhere on it.
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .membership(Positions.of(h.getLevel(), h.absolutePos(SIGNAL)))
                            .status()
                        == dev.izzy.factorycore.core.network.NetworkTopology.Status.VALID,
                    "Changing strength does not rebuild the network"))
        .thenExecute(
            () -> {
              stock(h, f, 4);
              f.signal.configure(
                  f.owner.getUUID(),
                  new SignalRule(
                      SignalRule.Condition.STOCK,
                      SignalRule.Compare.AT_LEAST,
                      8,
                      SignalRule.Output.PROPORTIONAL),
                  f.cobble);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.signal.power() == 7,
                    "Four of eight, proportional: 7, got " + f.signal.power()))
        .thenExecute(() -> stock(h, f, 0))
        .thenWaitUntil(() -> h.assertTrue(f.signal.power() == 0 && !lit(h), "Nothing stored: off"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void revokedViewAndOfflineNetworkTurnTheSignalOff(GameTestHelper h) {
    var f = setup(h);
    var member = TestPlayers.mock(h);
    TestPlayers.connect(member);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () -> {
              var networks = WorldNetworks.get(h.getLevel().getServer());
              var origin = Positions.of(h.getLevel(), h.absolutePos(SIGNAL));
              h.assertTrue(
                  networks.setGrants(
                          new NetworkPermissions.Actor(f.owner.getUUID(), false),
                          origin,
                          member.getUUID(),
                          NetworkPermissions.AccessRole.MEMBER.grants())
                      == NetworkPermissions.Change.OK,
                  "Member granted");
              // The member applies the settings, so the signal reads under the member's grants.
              f.signal.configure(member.getUUID(), SignalRule.DEFAULT, f.cobble);
              stock(h, f, 64);
            })
        .thenWaitUntil(() -> h.assertTrue(f.signal.power() == 15, "On under the member's VIEW"))
        .thenExecute(
            () ->
                h.assertTrue(
                    WorldNetworks.get(h.getLevel().getServer())
                            .setGrants(
                                new NetworkPermissions.Actor(f.owner.getUUID(), false),
                                Positions.of(h.getLevel(), h.absolutePos(SIGNAL)),
                                member.getUUID(),
                                java.util.Set.of())
                        == NetworkPermissions.Change.OK,
                    "Member revoked"))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.signal.power() == 0 && f.signal.reason().equals("denied") && !lit(h),
                    "Revoked VIEW turns it off with a reason: " + f.signal.reason()))
        .thenExecute(
            () -> {
              f.signal.configure(f.owner.getUUID(), SignalRule.DEFAULT, f.cobble);
            })
        .thenWaitUntil(() -> h.assertTrue(f.signal.power() == 15, "On again under the owner"))
        .thenExecute(() -> h.setBlock(ROOT, Blocks.AIR))
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    f.signal.power() == 0 && f.signal.reason().equals("offline"),
                    "No controller: off, offline; got " + f.signal.reason()))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void settingsSurviveReloadAndModesSwitch(GameTestHelper h) {
    var f = setup(h);
    var rule =
        new SignalRule(
            SignalRule.Condition.STOCK, SignalRule.Compare.BELOW, 5, SignalRule.Output.FULL);
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(() -> f.signal.configure(f.owner.getUUID(), rule, f.cobble))
        .thenWaitUntil(
            () -> h.assertTrue(f.signal.power() == 15, "Below five with nothing stored: on"))
        .thenExecute(
            () -> {
              var saved = f.signal.saveWithoutMetadata(h.getLevel().registryAccess());
              f.signal.loadWithComponents(saved, h.getLevel().registryAccess());
              h.assertTrue(
                  f.signal.rule().equals(rule)
                      && f.cobble.equals(f.signal.resource())
                      && f.owner.getUUID().equals(f.signal.owner())
                      && f.signal.power() == 15,
                  "Reload keeps the rule, resource, owner and last strength");
              f.owner.setPos(Vec3.atCenterOf(h.absolutePos(SIGNAL)));
              h.assertTrue(
                  f.signal.canUse(f.owner),
                  "Owner can use the Signal Interface: current="
                      + f.signal.current()
                      + " alive="
                      + f.owner.isAlive()
                      + " level="
                      + (f.owner.serverLevel() == h.getLevel()));
              h.assertTrue(
                  WorldNetworks.get(h.getLevel().getServer())
                      .allows(
                          dev.izzy.factorycore.platform.Actors.of(f.owner),
                          f.signal.origin(),
                          NetworkPermissions.Permission.EDIT),
                  "Owner holds EDIT here");
              var outcome =
                  InterfaceSwitch.request(f.owner, h.absolutePos(SIGNAL), InterfaceKind.BUFFER);
              h.assertTrue(
                  outcome == InterfaceSwitch.Outcome.SWITCHED
                      && h.getBlockState(SIGNAL).getValue(InterfaceBlock.KIND)
                          == InterfaceKind.BUFFER,
                  "Signal switches to Buffer: " + outcome);
            })
        .thenIdle(5)
        .thenExecute(
            () -> {
              h.assertTrue(!lit(h), "Out of Signal mode the lamp is off");
              var back =
                  InterfaceSwitch.request(f.owner, h.absolutePos(SIGNAL), InterfaceKind.SIGNAL);
              h.assertTrue(
                  back == InterfaceSwitch.Outcome.SWITCHED
                      && h.getBlockEntity(SIGNAL) instanceof SignalInterfaceEntity fresh
                      && fresh.resource() == null
                      && f.owner.getUUID().equals(fresh.owner()),
                  "Buffer switches back to a fresh, unset Signal: " + back);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void jobConditionIsOnWhileAJobForTheItemRuns(GameTestHelper h) {
    var f = setup(h);
    h.setBlock(UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(UNIT);
    var processing = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    processing.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON)),
            List.of(new ItemStack(Items.IRON_INGOT))));
    unit.patterns().insertItem(0, processing, false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var ingot = codec.itemKey(new ItemStack(Items.IRON_INGOT));
    var raw = codec.itemKey(new ItemStack(Items.RAW_IRON));
    UUID[] job = {null};
    h.startSequence()
        .thenWaitUntil(() -> ready(h, f))
        .thenExecute(
            () ->
                f.signal.configure(
                    f.owner.getUUID(),
                    new SignalRule(
                        SignalRule.Condition.JOB,
                        SignalRule.Compare.AT_LEAST,
                        1,
                        SignalRule.Output.FULL),
                    ingot))
        .thenIdle(SETTLE)
        .thenExecute(
            () -> {
              h.assertTrue(f.signal.power() == 0, "No job: off");
              DeviceSavedData.get(h.getLevel().getServer())
                  .registry()
                  .insert(CellHandleData.read(f.drive.cell(0)), raw, 1);
              job[0] =
                  WorldCraftingJobs.get(h.getLevel().getServer())
                      .submit(
                          new NetworkPermissions.Actor(f.owner.getUUID(), false),
                          Positions.of(h.getLevel(), h.absolutePos(SIGNAL)),
                          ingot,
                          1)
                      .job();
              h.assertTrue(job[0] != null, "A smelting job started (it waits for a machine)");
            })
        .thenWaitUntil(() -> h.assertTrue(f.signal.power() == 15 && lit(h), "Job running: on"))
        .thenExecute(
            () ->
                WorldCraftingJobs.get(h.getLevel().getServer())
                    .cancel(new NetworkPermissions.Actor(f.owner.getUUID(), false), job[0]))
        .thenWaitUntil(() -> h.assertTrue(f.signal.power() == 0, "Job cancelled: off"))
        .thenSucceed();
  }
}
