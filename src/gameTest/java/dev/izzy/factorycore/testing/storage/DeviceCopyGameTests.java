package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.BufferPolicy;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.DeviceCopy;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.LabelToolContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q13: Label Tool copy and paste for Drives, Signal Interfaces, Program Controllers, Buffers,
 * Gateways and Crafters, through the server's real interaction path: sneak-use copies, and a paste
 * needs a second use on the same device to confirm.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class DeviceCopyGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      FIRST = new BlockPos(1, 1, 2),
      SECOND = new BlockPos(2, 1, 1);

  private DeviceCopyGameTests() {}

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    // Mock players start in creative, where nothing is consumed; pastes here use real items.
    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LabelToolContent.ITEM.get()));
    return player;
  }

  private static void use(GameTestHelper h, ServerPlayer player, BlockPos at, boolean sneak) {
    var pos = h.absolutePos(at);
    player.setShiftKeyDown(sneak);
    player.gameMode.useItemOn(
        player,
        h.getLevel(),
        player.getMainHandItem(),
        InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    player.setShiftKeyDown(false);
  }

  /** Sneak-use copies from {@code from}; two uses paste onto {@code to}. */
  private static void copyPaste(GameTestHelper h, ServerPlayer player, BlockPos from, BlockPos to) {
    use(h, player, from, true);
    use(h, player, to, false);
    use(h, player, to, false);
  }

  private static void end(ServerPlayer... players) {
    for (var player : players)
      player.connection.disconnect(Component.literal("Device copy fixture complete"));
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aDrivesPriorityPastesAndNotOntoAnotherKind(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    StorageDriveEntity first = net.drive(FIRST, CellTier.STARTER_ITEM);
    StorageDriveEntity second = net.drive(SECOND, CellTier.STARTER_ITEM);
    h.setBlock(new BlockPos(1, 2, 1), ProgramContent.BLOCK.get());
    ProgramControllerEntity controller = h.getBlockEntity(new BlockPos(1, 2, 1));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              first.setPriority(7);
              copyPaste(h, player, FIRST, SECOND);
              h.assertTrue(second.priority() == 7, "Priority pasted: " + second.priority());
              use(h, player, new BlockPos(1, 2, 1), false);
              use(h, player, new BlockPos(1, 2, 1), false);
              h.assertTrue(
                  controller.draft().isEmpty(), "A Drive copy does not paste onto a controller");
              end(player);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aSignalsRuleResourceAndLabelPaste(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    h.setBlock(FIRST, InterfaceContent.state(InterfaceKind.SIGNAL, Direction.NORTH));
    h.setBlock(SECOND, InterfaceContent.state(InterfaceKind.SIGNAL, Direction.NORTH));
    SignalInterfaceEntity first = h.getBlockEntity(FIRST);
    SignalInterfaceEntity second = h.getBlockEntity(SECOND);
    first.place(player.getUUID());
    second.place(player.getUUID());
    var cobble =
        new PlatformResourceCodec(h.getLevel().registryAccess())
            .itemKey(new ItemStack(Items.COBBLESTONE));
    var rule =
        new SignalRule(
            SignalRule.Condition.STOCK,
            SignalRule.Compare.BELOW,
            32,
            SignalRule.Output.PROPORTIONAL);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              first.configure(player.getUUID(), rule, cobble);
              first.label("lamp");
              copyPaste(h, player, FIRST, SECOND);
              h.assertTrue(second.rule().equals(rule), "Rule pasted: " + second.rule());
              h.assertTrue(cobble.equals(second.resource()), "Watched resource pasted");
              h.assertTrue(second.label().equals("lamp"), "Label pasted");
              end(player);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aProgramDraftPastesWithoutDeploying(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    h.setBlock(FIRST, ProgramContent.BLOCK.get());
    h.setBlock(SECOND, ProgramContent.BLOCK.get());
    ProgramControllerEntity first = h.getBlockEntity(FIRST);
    ProgramControllerEntity second = h.getBlockEntity(SECOND);
    var source = "every 20 ticks {\n  move everything from machines(\"a\") to storage\n}\n";
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              first.draft(source);
              copyPaste(h, player, FIRST, SECOND);
              h.assertTrue(second.draft().equals(source), "Draft pasted");
              h.assertTrue(second.state() == ProgramControllerEntity.State.STOPPED, "Not deployed");
              end(player);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aBufferPasteInstallsTheCopiedUpgradeFromTheInventory(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    BufferedInterfaceEntity first = net.buffer(FIRST);
    BufferedInterfaceEntity second = net.buffer(SECOND);
    var actor = Actors.of(player);
    var policy = new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 8, 16);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> h.assertTrue(first.upgrade(actor, ThroughputTier.ADVANCED), "First upgraded"))
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              first.configure(actor, BufferRegistry.Mode.STOCK, policy);
              player
                  .getInventory()
                  .add(new ItemStack(ThroughputContent.item(ThroughputTier.ADVANCED)));
            })
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(() -> copyPaste(h, player, FIRST, SECOND))
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              h.assertTrue(
                  second.tier() == ThroughputTier.ADVANCED, "The copied upgrade was installed");
              h.assertTrue(
                  player.getInventory().countItem(ThroughputContent.item(ThroughputTier.ADVANCED))
                      == 0,
                  "From the inventory");
              var deployment =
                  DeviceSavedData.get(h.getLevel().getServer())
                      .buffers()
                      .deployment(second.handle().id());
              h.assertTrue(
                  deployment != null
                      && deployment.mode() == BufferRegistry.Mode.STOCK
                      && deployment.policy().equals(policy),
                  "Mode and policy pasted: " + deployment);
              // Stocking buffers would keep drawing on the shared budget after the test ends.
              first.configure(actor, BufferRegistry.Mode.PAUSED, policy);
              second.configure(actor, BufferRegistry.Mode.PAUSED, policy);
              end(player);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aGatewayPolicyPastesOnlyWithManage(GameTestHelper h) {
    var owner = player(h);
    var member = player(h);
    var net = Net.in(h, owner.getUUID());
    net.controller(ROOT);
    h.setBlock(
        SECOND,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    h.setBlock(
        FIRST,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
    NetworkNodeEntity first = h.getBlockEntity(FIRST);
    NetworkNodeEntity second = h.getBlockEntity(SECOND);
    first.commission(null);
    second.commission(null);
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    var closed = GatewayPolicy.fromMask(0);
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              var network = net.runtime().membership(net.at(ROOT)).scope().network();
              saved
                  .network(network)
                  .setGrants(
                      net.owner(),
                      member.getUUID(),
                      EnumSet.of(
                          NetworkPermissions.Permission.VIEW, NetworkPermissions.Permission.EDIT));
              saved.nodes().setPolicy(first.handle(), closed);
            })
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              var before = saved.nodes().definition(second.handle()).policy();
              h.assertTrue(!before.equals(closed), "The second gateway starts different");
              copyPaste(h, member, FIRST, SECOND);
              h.assertTrue(
                  saved.nodes().definition(second.handle()).policy().equals(before),
                  "Edit without Manage pastes nothing");
              copyPaste(h, owner, FIRST, SECOND);
              h.assertTrue(
                  saved.nodes().definition(second.handle()).policy().equals(closed),
                  "The owner's paste applies");
              end(owner, member);
            })
        .thenSucceed();
  }

  private static ItemStack pattern(net.minecraft.world.item.Item output) {
    var stacks = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    stacks.set(0, new ItemStack(Items.OAK_LOG));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(UUID.randomUUID(), 1, 0, stacks, new ItemStack(output, 4), List.of()));
    return stack;
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aCrafterPasteUsesBlankPatternsAndReportsTheMissingOne(GameTestHelper h) {
    var player = player(h);
    var net = Net.in(h, player.getUUID());
    net.controller(ROOT);
    h.setBlock(FIRST, CraftingContent.CRAFTER.get());
    h.setBlock(SECOND, CraftingContent.CRAFTER.get());
    CrafterEntity first = h.getBlockEntity(FIRST);
    CrafterEntity second = h.getBlockEntity(SECOND);
    first.patterns().insertItem(0, pattern(Items.OAK_PLANKS), false);
    first.patterns().insertItem(1, pattern(Items.STICK), false);
    player.getInventory().add(new ItemStack(CraftingContent.BLANK_PATTERN.get()));
    h.startSequence()
        .thenWaitUntil(() -> net.assertValid(FIRST, SECOND))
        .thenExecute(
            () -> {
              copyPaste(h, player, FIRST, SECOND);
              h.assertTrue(second.patternCount() == 1, "One blank, one pattern");
              h.assertTrue(
                  player.getInventory().countItem(CraftingContent.BLANK_PATTERN.get()) == 0,
                  "The blank was used");
              var copy =
                  player
                      .getMainHandItem()
                      .getOrDefault(LabelToolContent.DATA.get(), LabelToolContent.Data.EMPTY)
                      .copy();
              var again = DeviceCopy.paste(player, copy, second);
              h.assertTrue(
                  again.key().equals("pasted_patterns_missing")
                      && (int) again.arguments()[0] == 0
                      && (int) again.arguments()[1] == 1,
                  "The missing blank is reported: " + again.key());
              end(player);
            })
        .thenSucceed();
  }
}
