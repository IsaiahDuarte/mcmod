package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CrafterMenu;
import dev.izzy.factorycore.platform.crafting.CrafterPackets;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Ergonomics Q2: the Crafter screen moves patterns like ordinary slots under EDIT, and shows each
 * machine beside it with the job holding it.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CrafterScreenGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      DRIVE = new BlockPos(2, 1, 1),
      CRAFTER = new BlockPos(1, 1, 2),
      FURNACE = new BlockPos(2, 1, 2);

  private CrafterScreenGameTests() {}

  private static ItemStack smelting() {
    var stack = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    stack.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON)),
            List.of(new ItemStack(Items.IRON_INGOT))));
    return stack;
  }

  private static UUID id(ItemStack pattern) {
    return pattern.get(CraftingContent.PROCESSING.get()).id();
  }

  /** A Crafter on a network owned by {@code owner}, holding {@code patterns} from slot 0. */
  private static CrafterEntity crafter(GameTestHelper h, UUID owner, ItemStack... patterns) {
    Net.in(h, owner).controller(ROOT);
    h.setBlock(
        CRAFTER,
        CraftingContent.CRAFTER
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.UP));
    CrafterEntity crafter = h.getBlockEntity(CRAFTER);
    for (int slot = 0; slot < patterns.length; slot++)
      crafter.patterns().insertItem(slot, patterns[slot], false);
    return crafter;
  }

  private static ServerPlayer player(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.setGameMode(GameType.SURVIVAL);
    var center = h.absolutePos(CRAFTER);
    player.setPos(center.getX() + 0.5, center.getY() + 1, center.getZ() - 1.5);
    return player;
  }

  private static void online(GameTestHelper h) {
    h.assertTrue(
        WorldNetworks.get(h.getLevel().getServer())
                .membership(Positions.of(h.getLevel(), h.absolutePos(CRAFTER)))
                .status()
            == NetworkTopology.Status.VALID,
        "Network ready");
  }

  private static CrafterMenu open(GameTestHelper h, ServerPlayer player) {
    h.useBlock(CRAFTER, player);
    h.assertTrue(player.containerMenu instanceof CrafterMenu, "Empty-hand use opens the Crafter");
    var menu = (CrafterMenu) player.containerMenu;
    menu.broadcastChanges();
    return menu;
  }

  @GameTest(template = "empty")
  public static void theThirdOfThreePatternsIsTakenAndOneIsInsertedFromTheCursor(GameTestHelper h) {
    var player = player(h);
    ItemStack first = smelting(), second = smelting(), third = smelting(), extra = smelting();
    var crafter = crafter(h, player.getUUID(), first.copy(), second.copy(), third.copy());
    h.startSequence()
        .thenWaitUntil(() -> online(h))
        .thenExecute(
            () -> {
              var menu = open(h, player);
              h.assertTrue(menu.view().editable(), "The owner may move patterns");
              menu.clicked(2, 0, ClickType.PICKUP, player);
              h.assertTrue(
                  id(menu.getCarried()).equals(id(third))
                      && crafter.patterns().getStackInSlot(2).isEmpty()
                      && id(crafter.patterns().getStackInSlot(0)).equals(id(first))
                      && id(crafter.patterns().getStackInSlot(1)).equals(id(second)),
                  "Clicking the third slot takes exactly the third pattern");
              menu.setCarried(extra.copy());
              menu.clicked(5, 0, ClickType.PICKUP, player);
              h.assertTrue(
                  menu.getCarried().isEmpty()
                      && id(crafter.patterns().getStackInSlot(5)).equals(id(extra))
                      && crafter.patternCount() == 3,
                  "A pattern on the cursor goes into the clicked slot");
              menu.setCarried(new ItemStack(Items.DIAMOND));
              menu.clicked(6, 0, ClickType.PICKUP, player);
              h.assertTrue(
                  menu.getCarried().is(Items.DIAMOND)
                      && crafter.patterns().getStackInSlot(6).isEmpty(),
                  "Anything but a pattern is refused");
              menu.setCarried(ItemStack.EMPTY);
              menu.quickMoveStack(player, 0);
              h.assertTrue(
                  crafter.patterns().getStackInSlot(0).isEmpty()
                      && player.getInventory().items.stream()
                          .anyMatch(stack -> stack.has(CraftingContent.PROCESSING.get())),
                  "Shift-click moves a pattern to the inventory");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void withoutEditPatternsCannotBeMoved(GameTestHelper h) {
    var stranger = player(h);
    ItemStack held = smelting();
    var crafter = crafter(h, UUID.randomUUID(), held.copy());
    h.startSequence()
        .thenWaitUntil(() -> online(h))
        .thenExecute(
            () -> {
              h.assertFalse(
                  WorldNetworks.get(h.getLevel().getServer())
                      .allows(
                          dev.izzy.factorycore.platform.Actors.of(stranger),
                          crafter.origin(),
                          NetworkPermissions.Permission.EDIT),
                  "The stranger has no EDIT here");
              var menu = open(h, stranger);
              h.assertFalse(menu.view().editable(), "The view says patterns are locked");
              menu.clicked(0, 0, ClickType.PICKUP, stranger);
              h.assertTrue(
                  menu.getCarried().isEmpty()
                      && id(crafter.patterns().getStackInSlot(0)).equals(id(held)),
                  "A click without EDIT takes nothing");
              menu.quickMoveStack(stranger, 0);
              h.assertTrue(
                  id(crafter.patterns().getStackInSlot(0)).equals(id(held)),
                  "Shift-click without EDIT moves nothing");
              var mine = smelting();
              menu.setCarried(mine.copy());
              menu.clicked(1, 0, ClickType.PICKUP, stranger);
              h.assertTrue(
                  crafter.patterns().getStackInSlot(1).isEmpty()
                      && id(menu.getCarried()).equals(id(mine)),
                  "A pattern cannot be inserted without EDIT");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 600)
  public static void aMachineHeldByAJobIsShownBusy(GameTestHelper h) {
    var player = player(h);
    var crafter = crafter(h, player.getUUID(), smelting());
    var drive = Net.in(h).drive(DRIVE, CellTier.STARTER_ITEM);
    h.setBlock(FURNACE, Blocks.BLAST_FURNACE);
    BlastFurnaceBlockEntity furnace = h.getBlockEntity(FURNACE);
    furnace.setItem(1, new ItemStack(Items.COAL, 8));
    var saved = DeviceSavedData.get(h.getLevel().getServer());
    DeviceRegistry.Handle cell = CellHandleData.read(drive.cell(0));
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var ore = codec.itemKey(new ItemStack(Items.RAW_IRON));
    var ingot = codec.itemKey(new ItemStack(Items.IRON_INGOT));
    CrafterMenu[] menu = {null};
    h.startSequence()
        .thenWaitUntil(
            () -> {
              online(h);
              h.assertTrue(saved.registry().stock(cell, ore).loaded(), "Storage ready");
            })
        .thenExecute(
            () -> {
              menu[0] = open(h, player);
              var idle = menu[0].view();
              h.assertTrue(
                  idle.activity() == CrafterPackets.Activity.IDLE
                      && idle.machines().size() == 1
                      && idle.machines().getFirst().side() == Direction.EAST
                      && idle.machines().getFirst().block().equals("minecraft:blast_furnace")
                      && idle.machines().getFirst().state() == CrafterPackets.MachineState.FREE,
                  "Before a job the furnace is free and the Crafter idle: " + idle);
              saved.registry().insert(cell, ore, 1);
              var submission =
                  WorldCraftingJobs.get(h.getLevel().getServer())
                      .submit(
                          new NetworkPermissions.Actor(player.getUUID(), false),
                          crafter.origin(),
                          ingot,
                          1);
              h.assertTrue(submission.job() != null, "Submitted: " + submission.reason());
            })
        .thenWaitUntil(
            () -> {
              menu[0].broadcastChanges();
              var view = menu[0].view();
              h.assertTrue(
                  view.activity() == CrafterPackets.Activity.WORKING
                      && view.machines().getFirst().state() == CrafterPackets.MachineState.BUSY
                      && view.machines().getFirst().output().equals("minecraft:iron_ingot"),
                  "While smelting the furnace is busy making iron: " + view);
            })
        .thenSucceed();
  }
}
