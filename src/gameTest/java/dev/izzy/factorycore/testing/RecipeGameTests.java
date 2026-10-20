package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.storage.CellItem;
import dev.izzy.factorycore.platform.storage.CellUpgradeItem;
import dev.izzy.factorycore.platform.storage.StorageContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * D06 survival-recipe evidence through the real server RecipeManager and vanilla crafting-table
 * menu (ADR 0048). Replaces the skipped human survival playtest for recipe reachability only.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class RecipeGameTests {
  /** Registered items obtained by module upgrades or terminal encoding instead of a recipe. */
  private static final Set<String> NON_RECIPE =
      Set.of(
          "expanded_item_cell",
          "advanced_item_cell",
          "infinite_item_cell",
          "expanded_fluid_cell",
          "advanced_fluid_cell",
          "expanded_energy_bank",
          "advanced_energy_bank",
          "crafting_pattern",
          "processing_pattern");

  private static final BlockPos TABLE = new BlockPos(1, 1, 1);

  private RecipeGameTests() {}

  private static List<RecipeHolder<CraftingRecipe>> modRecipes(GameTestHelper h) {
    var recipes = new ArrayList<RecipeHolder<CraftingRecipe>>();
    for (var holder : h.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING))
      if (holder.id().getNamespace().equals("factorycore")) recipes.add(holder);
    return recipes;
  }

  private static ItemStack first(Ingredient ingredient) {
    var options = ingredient.getItems();
    return options.length == 0 ? ItemStack.EMPTY : options[0].copyWithCount(1);
  }

  /** The canonical grid for a recipe: shaped recipes at their own size, shapeless in one row. */
  private static CraftingInput input(CraftingRecipe recipe) {
    var items = new ArrayList<ItemStack>();
    for (var ingredient : recipe.getIngredients()) items.add(first(ingredient));
    if (recipe instanceof ShapedRecipe shaped)
      return CraftingInput.of(shaped.getWidth(), shaped.getHeight(), items);
    return CraftingInput.of(items.size(), 1, items);
  }

  @GameTest(template = "empty")
  public static void everyRecipeResolvesUniquelyAndEveryRegisteredItemIsObtainable(
      GameTestHelper h) {
    var manager = h.getLevel().getRecipeManager();
    var results = new HashSet<String>();
    var unlocked = new HashSet<ResourceLocation>();
    for (var advancement : h.getLevel().getServer().getAdvancements().getAllAdvancements())
      unlocked.addAll(advancement.value().rewards().recipes());
    var recipes = modRecipes(h);
    h.assertTrue(
        recipes.size() == 25,
        "All 25 Factory Core crafting recipes, including speed upgrades, load");
    for (var holder : recipes) {
      var input = input(holder.value());
      var found = manager.getRecipeFor(RecipeType.CRAFTING, input, h.getLevel());
      h.assertTrue(
          found.isPresent() && found.get().id().equals(holder.id()),
          "Canonical grid crafts this recipe, not a vanilla or sibling one: " + holder.id());
      var result = holder.value().assemble(input, h.getLevel().registryAccess());
      var key = BuiltInRegistries.ITEM.getKey(result.getItem());
      h.assertTrue(
          key.getNamespace().equals("factorycore")
              && BuiltInRegistries.ITEM.containsKey(key)
              && result.get(DataComponents.CUSTOM_DATA) == null,
          "Recipe yields a registered, unbound Factory Core item: " + holder.id());
      h.assertTrue(results.add(key.getPath()), "One recipe per item: " + key);
      h.assertTrue(unlocked.contains(holder.id()), "Recipe-book unlock exists: " + holder.id());
      for (var ingredient : holder.value().getIngredients())
        for (var option : ingredient.getItems())
          h.assertTrue(
              !(option.getItem() instanceof CellItem), "No recipe consumes a cell: " + holder.id());
    }
    for (var key : BuiltInRegistries.ITEM.keySet()) {
      if (!key.getNamespace().equals("factorycore")) continue;
      h.assertTrue(
          results.contains(key.getPath()) != NON_RECIPE.contains(key.getPath()),
          "Exactly one survival source (recipe or documented upgrade/encoding): " + key);
    }
    h.succeed();
  }

  @GameTest(template = "empty")
  public static void paidThroughputRecipesConsumeExactMaterialsAndProduceFourOverdrives(
      GameTestHelper h) {
    var kit = new LinkedHashMap<Item, Integer>();
    kit.put(Items.IRON_INGOT, 4);
    kit.put(Items.DIAMOND, 6);
    kit.put(Items.REDSTONE, 2);
    kit.put(Items.HOPPER, 1);
    kit.put(Items.NETHERITE_INGOT, 4);
    kit.put(Items.NETHER_STAR, 1);
    var player = playerWith(h, kit);
    var menu = table(h, player);
    craft(h, player, menu, "throughput_upgrade");
    craft(h, player, menu, "overdrive_upgrade");
    menu.removed(player);
    assertOnly(h, player, Map.of("throughput_upgrade", 1, "overdrive_upgrade", 4));
    player.connection.disconnect(Component.literal("Throughput recipe fixture complete"));
    h.succeed();
  }

  /**
   * Moves one matching item per ingredient from the inventory into the grid, then takes the result.
   */
  private static ItemStack craft(
      GameTestHelper h, ServerPlayer player, CraftingMenu menu, String id) {
    var holder =
        h.getLevel()
            .getRecipeManager()
            .byKey(ResourceLocation.fromNamespaceAndPath("factorycore", id))
            .orElseThrow();
    var recipe = (CraftingRecipe) holder.value();
    int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
    var ingredients = recipe.getIngredients();
    var inventory = player.getInventory();
    for (int i = 0; i < ingredients.size(); i++) {
      var ingredient = ingredients.get(i);
      if (ingredient.isEmpty()) continue;
      int source = -1;
      for (int slot = 0; slot < inventory.getContainerSize() && source < 0; slot++)
        if (ingredient.test(inventory.getItem(slot))) source = slot;
      h.assertTrue(source >= 0, "Inventory holds an ingredient for " + id);
      menu.getSlot(1 + (i / width) * 3 + i % width).set(inventory.removeItem(source, 1));
    }
    var preview = menu.getSlot(0).getItem().copy();
    h.assertTrue(
        BuiltInRegistries.ITEM.getKey(preview.getItem()).toString().equals("factorycore:" + id),
        "Crafting table shows the result for " + id + " but shows " + preview);
    menu.quickMoveStack(player, 0);
    for (int slot = 1; slot <= 9; slot++)
      h.assertTrue(menu.getSlot(slot).getItem().isEmpty(), "Grid fully consumed for " + id);
    return preview;
  }

  private static ServerPlayer playerWith(GameTestHelper h, Map<Item, Integer> kit) {
    var player = TestPlayers.mock(h);
    player.setGameMode(GameType.SURVIVAL);
    player.getInventory().clearContent();
    for (var entry : kit.entrySet()) {
      int remaining = entry.getValue();
      while (remaining > 0) {
        int count = Math.min(remaining, entry.getKey().getDefaultMaxStackSize());
        h.assertTrue(
            player.getInventory().add(new ItemStack(entry.getKey(), count)), "Kit fits inventory");
        remaining -= count;
      }
    }
    return player;
  }

  private static void assertOnly(
      GameTestHelper h, ServerPlayer player, Map<String, Integer> expected) {
    var actual = new LinkedHashMap<String, Integer>();
    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
      var stack = player.getInventory().getItem(slot);
      if (stack.isEmpty()) continue;
      actual.merge(
          BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
    }
    h.assertTrue(
        actual.equals(expected),
        "Exactly the crafted items remain, every vanilla input consumed: " + actual);
  }

  private static CraftingMenu table(GameTestHelper h, ServerPlayer player) {
    h.setBlock(TABLE, Blocks.CRAFTING_TABLE);
    return new CraftingMenu(
        1, player.getInventory(), ContainerLevelAccess.create(h.getLevel(), h.absolutePos(TABLE)));
  }

  /** The starter kit: early iron, copper, redstone, sand and wood only (R11 Starter). */
  @GameTest(template = "empty")
  public static void starterNetworkCraftsFromAVanillaInventory(GameTestHelper h) {
    var kit = new LinkedHashMap<Item, Integer>();
    kit.put(Items.IRON_INGOT, 39);
    kit.put(Items.REDSTONE, 35);
    kit.put(Items.COPPER_INGOT, 12);
    kit.put(Items.GLASS, 13);
    kit.put(Items.HOPPER, 3);
    kit.put(Items.CHEST, 1);
    kit.put(Items.FURNACE, 1);
    kit.put(Items.BUCKET, 1);
    kit.put(Items.BOOK, 1);
    kit.put(Items.PAPER, 4);
    var player = playerWith(h, kit);
    var menu = table(h, player);
    var expected = new LinkedHashMap<String, Integer>();
    for (var id :
        List.of(
            "network_controller",
            "network_cable",
            "storage_drive",
            "starter_item_cell",
            "starter_fluid_cell",
            "storage_terminal",
            "buffered_interface",
            "storage_connector",
            "machine_interface",
            "fuel_generator",
            "starter_energy_bank",
            "factory_guide",
            "blank_pattern")) {
      var crafted = craft(h, player, menu, id);
      expected.merge(id, crafted.getCount(), Integer::sum);
    }
    h.assertTrue(
        expected.get("network_cable") == 8 && expected.get("blank_pattern") == 4,
        "Batch recipes yield 8 cables and 4 blank patterns");
    menu.removed(player);
    assertOnly(h, player, expected);
    player.connection.disconnect(Component.literal("done"));
    h.succeed();
  }

  /**
   * Expanded, Advanced and Endgame items craft in dependency order from vanilla materials, then the
   * crafted modules take a freshly crafted Starter Item Cell to Infinite through offhand upgrades.
   */
  @GameTest(template = "empty")
  public static void laterTiersCraftInOrderAndReachAnInfiniteCell(GameTestHelper h) {
    var kit = new LinkedHashMap<Item, Integer>();
    kit.put(Items.IRON_INGOT, 45);
    kit.put(Items.GOLD_INGOT, 16);
    kit.put(Items.REDSTONE, 27);
    kit.put(Items.QUARTZ, 3);
    kit.put(Items.ENDER_PEARL, 5);
    kit.put(Items.CRAFTING_TABLE, 1);
    kit.put(Items.HOPPER, 1);
    kit.put(Items.REPEATER, 1);
    kit.put(Items.GLASS, 3);
    kit.put(Items.COPPER_INGOT, 5);
    kit.put(Items.CHEST, 1);
    kit.put(Items.DIAMOND, 8);
    kit.put(Items.NETHERITE_INGOT, 9);
    kit.put(Items.ENDER_EYE, 2);
    kit.put(Items.NETHER_STAR, 1);
    var player = playerWith(h, kit);
    var menu = table(h, player);
    for (var id :
        List.of(
            "starter_item_cell",
            "expansion_module",
            "crafting_unit",
            "crafting_interface",
            "program_controller",
            "gateway",
            "wireless_access_point",
            "wireless_access_point",
            "wireless_access_point",
            "storage_terminal",
            "wireless_terminal",
            "advanced_module",
            "wireless_link_endpoint",
            "wireless_link_endpoint",
            "infinite_module")) craft(h, player, menu, id);
    menu.removed(player);
    assertOnly(
        h,
        player,
        Map.ofEntries(
            Map.entry("starter_item_cell", 1),
            Map.entry("expansion_module", 1),
            Map.entry("crafting_unit", 1),
            Map.entry("crafting_interface", 1),
            Map.entry("program_controller", 1),
            Map.entry("gateway", 1),
            Map.entry("wireless_access_point", 1),
            Map.entry("wireless_terminal", 1),
            Map.entry("advanced_module", 1),
            Map.entry("wireless_link_endpoint", 2),
            Map.entry("infinite_module", 1)));
    var inventory = player.getInventory();
    var cell =
        inventory.getItem(findSlot(player, StorageContent.cell(CellTier.STARTER_ITEM))).copy();
    var modules =
        new CellUpgradeItem[] {
          StorageContent.EXPANSION.get(),
          StorageContent.ADVANCED.get(),
          StorageContent.INFINITE.get()
        };
    var crafted = new ArrayList<ItemStack>();
    for (var module : modules) crafted.add(inventory.getItem(findSlot(player, module)).copy());
    inventory.clearContent();
    player.setItemInHand(InteractionHand.OFF_HAND, cell);
    for (int i = 0; i < modules.length; i++) {
      player.setItemInHand(InteractionHand.MAIN_HAND, crafted.get(i));
      h.assertTrue(
          modules[i].use(h.getLevel(), player, InteractionHand.MAIN_HAND).getResult()
                  == InteractionResult.CONSUME
              && player.getMainHandItem().isEmpty(),
          "Crafted module upgrades the crafted cell and is consumed");
    }
    h.assertTrue(
        player.getOffhandItem().is(StorageContent.cell(CellTier.INFINITE_ITEM)),
        "A survival-crafted Starter Item Cell reaches the Infinite tier");
    player.connection.disconnect(Component.literal("done"));
    h.succeed();
  }

  private static int findSlot(ServerPlayer player, Item item) {
    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
      if (player.getInventory().getItem(slot).is(item)) return slot;
    throw new AssertionError("Missing " + item);
  }
}
