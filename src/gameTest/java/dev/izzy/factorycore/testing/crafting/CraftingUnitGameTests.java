package dev.izzy.factorycore.testing.crafting;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.crafting.CrafterEntity;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.crafting.PatternContents;
import dev.izzy.factorycore.platform.crafting.ProcessingContents;
import dev.izzy.factorycore.platform.crafting.WorldCrafting;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalEntity;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CraftingUnitGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1),
      GATEWAY = new BlockPos(2, 1, 1),
      CHILD_UNIT = new BlockPos(3, 1, 1),
      ROOT_UNIT = new BlockPos(1, 1, 2),
      COPY_UNIT = new BlockPos(1, 1, 0),
      TERMINAL = new BlockPos(0, 1, 1);

  private CraftingUnitGameTests() {}

  private static ItemStack pattern(Item input, Item output, int count) {
    var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    cells.set(0, new ItemStack(input));
    var stack = new ItemStack(CraftingContent.CRAFTING_PATTERN.get());
    stack.set(
        CraftingContent.PATTERN.get(),
        new PatternContents(
            UUID.randomUUID(), 1, 0, cells, new ItemStack(output, count), List.of()));
    return stack;
  }

  private static NetworkTopology.Membership membership(GameTestHelper h, BlockPos pos) {
    return WorldNetworks.get(h.getLevel().getServer())
        .membership(Positions.of(h.getLevel(), h.absolutePos(pos)));
  }

  private static void valid(GameTestHelper h, BlockPos... positions) {
    for (var pos : positions)
      h.assertTrue(membership(h, pos).status() == NetworkTopology.Status.VALID, "Valid at " + pos);
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void patternsAreOfferedOnlyToTheirOwnSegmentAndDeduplicated(GameTestHelper h) {
    NetworkNodeEntity root = Net.in(h, UUID.randomUUID()).controller(ROOT);
    h.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    NetworkNodeEntity gate = h.getBlockEntity(GATEWAY);
    gate.commission(null);
    h.setBlock(CHILD_UNIT, CraftingContent.CRAFTER.get());
    h.setBlock(ROOT_UNIT, CraftingContent.CRAFTER.get());
    h.setBlock(COPY_UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity rootUnit = h.getBlockEntity(ROOT_UNIT);
    CrafterEntity copyUnit = h.getBlockEntity(COPY_UNIT);
    CrafterEntity childUnit = h.getBlockEntity(CHILD_UNIT);
    var planks = pattern(Items.OAK_LOG, Items.OAK_PLANKS, 4);
    var sticks = pattern(Items.OAK_PLANKS, Items.STICK, 4);
    h.setBlock(
        ROOT_UNIT.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = h.getBlockEntity(ROOT_UNIT.above());
    hopper.setItem(0, planks.copy());
    copyUnit.patterns().insertItem(0, planks.copy(), false);
    childUnit.patterns().insertItem(0, sticks, false);
    var codec = new PlatformResourceCodec(h.getLevel().registryAccess());
    var plankKey = codec.itemKey(new ItemStack(Items.OAK_PLANKS));
    var stickKey = codec.itemKey(new ItemStack(Items.STICK));
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(rootUnit.patternCount() == 1, "Hopper inserts pattern"))
        .thenWaitUntil(() -> valid(h, ROOT, ROOT_UNIT, COPY_UNIT, CHILD_UNIT))
        .thenExecute(
            () -> {
              var rejected =
                  h.getLevel()
                      .getCapability(
                          Capabilities.ItemHandler.BLOCK, h.absolutePos(ROOT_UNIT), Direction.UP)
                      .insertItem(1, new ItemStack(CraftingContent.BLANK_PATTERN.get()), false);
              h.assertTrue(rejected.getCount() == 1, "Blank patterns and other items are rejected");
              var crafting = WorldCrafting.get(h.getLevel().getServer());
              var rootIndex = crafting.index(membership(h, ROOT).scope());
              h.assertTrue(
                  rootIndex.producers(plankKey).size() == 1
                      && rootIndex.byOutput().get(plankKey).getFirst().unit()
                          == (h.absolutePos(COPY_UNIT).asLong() < h.absolutePos(ROOT_UNIT).asLong()
                              ? copyUnit
                              : rootUnit)
                      && !rootIndex.byOutput().get(plankKey).getFirst().published()
                      && !rootIndex.truncated(),
                  "Root sees one local plank pattern, kept from the first unit in stable position order");
              // The default open gateway allows CRAFT for its branch, so the child publishes
              // upward.
              h.assertTrue(
                  rootIndex.producers(stickKey).size() == 1
                      && rootIndex.byOutput().get(stickKey).getFirst().published()
                      && rootIndex.byOutput().get(stickKey).getFirst().unit() == childUnit,
                  "Root sees the child's stick pattern as published");
              var childIndex = crafting.index(membership(h, CHILD_UNIT).scope());
              h.assertTrue(
                  childIndex.producers(stickKey).size() == 1
                      && childIndex.producers(plankKey).isEmpty(),
                  "Publication is upward only: the child does not see root patterns");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void breakingDropsEveryPatternAndUnsupportedDataIsRetained(GameTestHelper h) {
    h.setBlock(ROOT_UNIT, CraftingContent.CRAFTER.get());
    CrafterEntity unit = h.getBlockEntity(ROOT_UNIT);
    unit.patterns().insertItem(0, pattern(Items.OAK_LOG, Items.OAK_PLANKS, 4), false);
    unit.patterns().insertItem(4, pattern(Items.BIRCH_LOG, Items.BIRCH_PLANKS, 4), false);
    h.startSequence()
        .thenExecute(
            () -> {
              h.getLevel().destroyBlock(h.absolutePos(ROOT_UNIT), true);
              var drops =
                  h.getLevel()
                      .getEntitiesOfClass(
                          ItemEntity.class, new AABB(h.absolutePos(ROOT_UNIT)).inflate(2));
              var counts = new HashMap<String, Integer>();
              for (var drop : drops) {
                counts.merge(
                    BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).getPath(),
                    drop.getItem().getCount(),
                    Integer::sum);
                h.assertTrue(
                    !drop.getItem().is(CraftingContent.CRAFTING_PATTERN.get())
                        || drop.getItem().get(CraftingContent.PATTERN.get()) != null,
                    "Dropped patterns keep their contents");
                drop.discard();
              }
              h.assertTrue(
                  counts.equals(Map.of("crafter", 1, "crafting_pattern", 2)),
                  "Unit and both patterns drop: " + counts);
              h.setBlock(ROOT_UNIT, CraftingContent.CRAFTER.get());
              CrafterEntity future = h.getBlockEntity(ROOT_UNIT);
              var tag = new CompoundTag();
              tag.putInt("Schema", 2);
              tag.putString("Future", "kept");
              future.loadWithComponents(tag, h.getLevel().registryAccess());
              h.assertTrue(
                  !future.diagnostic().isEmpty()
                      && future
                              .patterns()
                              .insertItem(0, pattern(Items.OAK_LOG, Items.OAK_PLANKS, 4), false)
                              .getCount()
                          == 1
                      && future.saveWithoutMetadata(h.getLevel().registryAccess()).equals(tag),
                  "Unsupported data is inert and saved unchanged");
              h.getLevel().destroyBlock(h.absolutePos(ROOT_UNIT), true);
              var retained =
                  h.getLevel()
                      .getEntitiesOfClass(
                          ItemEntity.class, new AABB(h.absolutePos(ROOT_UNIT)).inflate(2));
              h.assertTrue(retained.size() == 1, "Only the unit drops");
              var device = retained.getFirst().getItem().copy();
              retained.getFirst().discard();
              h.setBlock(ROOT_UNIT, CraftingContent.CRAFTER.get());
              CrafterEntity placed = h.getBlockEntity(ROOT_UNIT);
              placed.applyComponentsFromItemStack(device);
              h.assertTrue(
                  placed.saveWithoutMetadata(h.getLevel().registryAccess()).equals(tag),
                  "Placement retains the data again");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void terminalEncodesExactRecipeOntoOneBlankPattern(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    TestPlayers.connect(player);
    player.moveTo(Vec3.atCenterOf(h.absolutePos(ROOT)));
    player.getInventory().clearContent();
    NetworkNodeEntity root = Net.in(h, player.getUUID()).controller(ROOT);
    h.setBlock(TERMINAL, TerminalContent.BLOCK.get());
    TerminalEntity terminal = h.getBlockEntity(TERMINAL);
    var inventory = player.getInventory();
    for (int slot = 0; slot < 3; slot++) inventory.setItem(slot, new ItemStack(Items.MILK_BUCKET));
    inventory.setItem(3, new ItemStack(Items.SUGAR, 2));
    inventory.setItem(4, new ItemStack(Items.EGG));
    inventory.setItem(5, new ItemStack(Items.WHEAT, 3));
    var codec = new PlatformResourceCodec(player.registryAccess());
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(terminal.canUse(player), "Terminal ready"))
        .thenExecute(
            () -> {
              h.useBlock(TERMINAL, player);
              var menu = (TerminalMenu) player.containerMenu;
              int[] sources = {0, 0, 0, 3, 4, 3, 5, 5, 5};
              for (int cell = 0; cell < 9; cell++) {
                var key = codec.itemKey(inventory.getItem(sources[cell]));
                menu.gridCommand(
                    player,
                    new TerminalCraftingPackets.Command(
                        menu.containerId,
                        menu.session(),
                        menu.view().revision(),
                        menu.gridView().gridRevision(),
                        TerminalCraftingPackets.SET_OWN,
                        cell,
                        sources[cell],
                        1,
                        TerminalPackets.fingerprint(key)));
              }
              menu.gridCommand(
                  player,
                  new TerminalCraftingPackets.Command(
                      menu.containerId,
                      menu.session(),
                      menu.view().revision(),
                      menu.gridView().gridRevision(),
                      TerminalCraftingPackets.ENCODE,
                      -1,
                      0,
                      1,
                      new byte[32]));
              menu.broadcastChanges();
              h.assertTrue(
                  menu.gridView().status().equals("no_blank"),
                  "Encoding without a blank pattern changes nothing");
              inventory.setItem(10, new ItemStack(CraftingContent.BLANK_PATTERN.get(), 2));
            })
        .thenIdle(3)
        .thenExecute(
            () -> {
              var menu = (TerminalMenu) player.containerMenu;
              menu.gridCommand(
                  player,
                  new TerminalCraftingPackets.Command(
                      menu.containerId,
                      menu.session(),
                      menu.view().revision(),
                      menu.gridView().gridRevision(),
                      TerminalCraftingPackets.ENCODE,
                      -1,
                      0,
                      1,
                      new byte[32]));
              menu.broadcastChanges();
              h.assertTrue(menu.gridView().status().equals("encoded"), "Pattern encoded");
              ItemStack encoded = ItemStack.EMPTY;
              for (int slot = 0; slot < 36; slot++)
                if (inventory.getItem(slot).is(CraftingContent.CRAFTING_PATTERN.get()))
                  encoded = inventory.getItem(slot);
              var contents = encoded.get(CraftingContent.PATTERN.get());
              h.assertTrue(contents != null, "Encoded pattern item present");
              var pattern = contents.pattern(codec);
              h.assertTrue(
                  inventory.getItem(10).getCount() == 1
                      && inventory.getItem(0).is(Items.MILK_BUCKET)
                      && inventory.getItem(5).getCount() == 3,
                  "Exactly one blank used; ingredients untouched");
              h.assertTrue(
                  pattern.output().equals(codec.itemKey(new ItemStack(Items.CAKE)))
                      && pattern.outputAmount() == 1
                      && pattern.inputs().get(codec.itemKey(new ItemStack(Items.MILK_BUCKET))) == 3
                      && pattern
                          .byproducts()
                          .equals(Map.of(codec.itemKey(new ItemStack(Items.BUCKET)), 3L))
                      && pattern.version() == 1,
                  "Server recipe output and bucket remainders are encoded exactly");
              player.closeContainer();
              player.connection.disconnect(Component.literal("Encode fixture complete"));
            })
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void patternAndUnitRecipesUseVanillaMaterials(GameTestHelper h) {
    var expected =
        Map.of(
            "blank_pattern", Map.of("paper", 4, "redstone", 4, "iron_ingot", 1),
            "crafter",
                Map.of("iron_ingot", 4, "gold_ingot", 2, "redstone", 2, "crafting_table", 1));
    for (var entry : expected.entrySet()) {
      var id = ResourceLocation.fromNamespaceAndPath("factorycore", entry.getKey());
      var recipe = h.getLevel().getRecipeManager().byKey(id).orElseThrow().value();
      var costs = new HashMap<String, Integer>();
      for (var ingredient : recipe.getIngredients())
        for (var stack : ingredient.getItems()) {
          var item = BuiltInRegistries.ITEM.getKey(stack.getItem());
          h.assertTrue(item.getNamespace().equals("minecraft"), "Vanilla ingredients only");
          costs.merge(item.getPath(), 1, Integer::sum);
        }
      h.assertTrue(costs.equals(entry.getValue()), entry.getKey() + " recipe: " + costs);
      h.assertTrue(
          h.getLevel()
                  .getServer()
                  .getAdvancements()
                  .get(
                      ResourceLocation.fromNamespaceAndPath(
                          "factorycore", "recipes/misc/" + entry.getKey()))
              != null,
          "Unlock loaded");
    }
    var blank =
        h.getLevel()
            .getRecipeManager()
            .byKey(ResourceLocation.fromNamespaceAndPath("factorycore", "blank_pattern"))
            .orElseThrow()
            .value()
            .getResultItem(h.getLevel().registryAccess());
    h.assertTrue(blank.getCount() == 4, "Four blank patterns per craft");
    h.succeed();
  }

  /** U12 (ADR 0060): one Crafter holds both pattern kinds; its faced side is the machine's. */
  @GameTest(template = "empty")
  public static void crafterHoldsBothPatternKindsAndKeepsItsMachineFaceFree(GameTestHelper h) {
    var position = new BlockPos(2, 1, 2);
    h.setBlock(
        position,
        CraftingContent.CRAFTER
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    CrafterEntity crafter = h.getBlockEntity(position);
    var processing = new ItemStack(CraftingContent.PROCESSING_PATTERN.get());
    processing.set(
        CraftingContent.PROCESSING.get(),
        new ProcessingContents(
            UUID.randomUUID(),
            1,
            0,
            List.of(new ItemStack(Items.RAW_IRON)),
            List.of(new ItemStack(Items.IRON_INGOT))));
    h.assertTrue(
        crafter
                .patterns()
                .insertItem(0, pattern(Items.OAK_LOG, Items.OAK_PLANKS, 4), false)
                .isEmpty()
            && crafter.patterns().insertItem(1, processing, false).isEmpty(),
        "A crafting-table pattern and a processing pattern both fit in one Crafter");
    h.assertFalse(
        crafter.patterns().isItemValid(2, new ItemStack(Items.PAPER)), "Anything else is refused");
    // H8: every side with a machine is closed to the pattern handler, not only the faced one, so
    // a machine never pulls patterns; the other sides still take them from hoppers.
    h.setBlock(position.east(), Blocks.FURNACE);
    h.setBlock(position.west(), Blocks.FURNACE);
    var absolute = h.absolutePos(position);
    h.getLevel().invalidateCapabilities(absolute);
    var level = h.getLevel();
    h.assertTrue(
        level.getCapability(Capabilities.ItemHandler.BLOCK, absolute, Direction.EAST) == null
            && level.getCapability(Capabilities.ItemHandler.BLOCK, absolute, Direction.WEST) == null
            && level.getCapability(Capabilities.ItemHandler.BLOCK, absolute, Direction.UP) != null
            && level.getCapability(Capabilities.ItemHandler.BLOCK, absolute, Direction.NORTH)
                != null,
        "Hoppers reach the patterns from every side without a machine");
    h.succeed();
  }
}
