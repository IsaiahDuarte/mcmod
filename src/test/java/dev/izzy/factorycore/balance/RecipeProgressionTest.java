package dev.izzy.factorycore.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.resource.WirelessBudget;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.core.storage.FuelGenerator;
import dev.izzy.factorycore.core.storage.ItemBuffer;
import dev.izzy.factorycore.platform.crafting.PatternHolderEntity;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.wireless.AccessPointEntity;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import dev.izzy.factorycore.platform.wireless.WirelessTerminalItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * D06 balance version two (ADR 0048): parses every shipped recipe JSON and proves survival
 * reachability from vanilla materials, R11 stage ordering and the frozen numeric table.
 */
class RecipeProgressionTest {
  /** R11 stages, in progression order. */
  enum Stage {
    STARTER,
    EXPANDED,
    ADVANCED,
    ENDGAME
  }

  /**
   * Every vanilla recipe input, with the earliest stage a survival player reasonably holds it. Each
   * is survival-obtainable in vanilla 1.21.1 without commands; a new vanilla input must be
   * classified here before the test passes.
   */
  private static final Map<String, Stage> VANILLA =
      Map.ofEntries(
          Map.entry("minecraft:iron_ingot", Stage.STARTER),
          Map.entry("minecraft:copper_ingot", Stage.STARTER),
          Map.entry("minecraft:redstone", Stage.STARTER),
          Map.entry("minecraft:glass", Stage.STARTER),
          Map.entry("minecraft:chest", Stage.STARTER),
          Map.entry("minecraft:hopper", Stage.STARTER),
          Map.entry("minecraft:furnace", Stage.STARTER),
          Map.entry("minecraft:bucket", Stage.STARTER),
          Map.entry("minecraft:paper", Stage.STARTER),
          Map.entry("minecraft:book", Stage.STARTER),
          Map.entry("minecraft:crafting_table", Stage.STARTER),
          Map.entry("minecraft:repeater", Stage.STARTER),
          Map.entry("minecraft:gold_ingot", Stage.EXPANDED),
          Map.entry("minecraft:quartz", Stage.EXPANDED),
          Map.entry("minecraft:ender_pearl", Stage.EXPANDED),
          Map.entry("minecraft:diamond", Stage.ADVANCED),
          Map.entry("minecraft:ender_eye", Stage.ADVANCED),
          Map.entry("minecraft:netherite_ingot", Stage.ADVANCED),
          Map.entry("minecraft:nether_star", Stage.ENDGAME));

  /**
   * Registered items obtained by an in-world action instead of a recipe: sequential offhand module
   * upgrades (ADR 0006) and terminal pattern encoding (ADR 0030/0032). Values are the consumed
   * inputs.
   */
  private static final Map<String, List<String>> NON_RECIPE =
      Map.ofEntries(
          upgrade("expanded_item_cell", "starter_item_cell", "expansion_module"),
          upgrade("advanced_item_cell", "expanded_item_cell", "advanced_module"),
          upgrade("infinite_item_cell", "advanced_item_cell", "infinite_module"),
          upgrade("expanded_fluid_cell", "starter_fluid_cell", "expansion_module"),
          upgrade("advanced_fluid_cell", "expanded_fluid_cell", "advanced_module"),
          upgrade("expanded_energy_bank", "starter_energy_bank", "expansion_module"),
          upgrade("advanced_energy_bank", "expanded_energy_bank", "advanced_module"),
          Map.entry("factorycore:crafting_pattern", List.of("factorycore:blank_pattern")),
          Map.entry("factorycore:processing_pattern", List.of("factorycore:blank_pattern")));

  /** The ADR 0048 stage of every survival item; it must equal the computed stage. */
  private static final Map<String, Stage> EXPECTED =
      Map.ofEntries(
          entry("factory_guide", Stage.STARTER),
          entry("network_controller", Stage.STARTER),
          entry("network_cable", Stage.STARTER),
          entry("storage_drive", Stage.STARTER),
          entry("starter_item_cell", Stage.STARTER),
          entry("starter_fluid_cell", Stage.STARTER),
          entry("storage_terminal", Stage.STARTER),
          entry("importer", Stage.STARTER),
          entry("exporter", Stage.STARTER),
          entry("interface", Stage.STARTER),
          entry("starter_energy_bank", Stage.STARTER),
          entry("fuel_generator", Stage.STARTER),
          entry("blank_pattern", Stage.STARTER),
          entry("crafting_pattern", Stage.STARTER),
          entry("processing_pattern", Stage.STARTER),
          entry("label_tool", Stage.STARTER),
          entry("expansion_module", Stage.EXPANDED),
          entry("expanded_item_cell", Stage.EXPANDED),
          entry("expanded_fluid_cell", Stage.EXPANDED),
          entry("expanded_energy_bank", Stage.EXPANDED),
          entry("crafter", Stage.EXPANDED),
          entry("program_controller", Stage.EXPANDED),
          entry("gateway", Stage.EXPANDED),
          entry("wireless_access_point", Stage.EXPANDED),
          entry("wireless_terminal", Stage.EXPANDED),
          entry("advanced_module", Stage.ADVANCED),
          entry("advanced_item_cell", Stage.ADVANCED),
          entry("advanced_fluid_cell", Stage.ADVANCED),
          entry("advanced_energy_bank", Stage.ADVANCED),
          entry("wireless_link", Stage.ADVANCED),
          entry("throughput_upgrade", Stage.ADVANCED),
          entry("infinite_module", Stage.ENDGAME),
          entry("overdrive_upgrade", Stage.ENDGAME),
          entry("infinite_item_cell", Stage.ENDGAME));

  private record Recipe(String file, String result, int count, List<String> inputs, String grid) {}

  private static Map.Entry<String, List<String>> upgrade(
      String target, String from, String module) {
    return Map.entry(
        "factorycore:" + target, List.of("factorycore:" + from, "factorycore:" + module));
  }

  private static Map.Entry<String, Stage> entry(String path, Stage stage) {
    return Map.entry("factorycore:" + path, stage);
  }

  private static List<Recipe> recipes() throws Exception {
    var url = RecipeProgressionTest.class.getResource("/data/factorycore/recipe");
    assertNotNull(url, "Recipe directory on the test classpath");
    var recipes = new ArrayList<Recipe>();
    try (var files = Files.list(Path.of(url.toURI()))) {
      for (var file : files.sorted().toList()) {
        assertTrue(file.toString().endsWith(".json"), "Only recipe JSON: " + file);
        var json =
            JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
        recipes.add(parse(file.getFileName().toString(), json));
      }
    }
    assertFalse(recipes.isEmpty(), "Recipes must be inspected");
    return recipes;
  }

  private static String item(JsonElement ingredient, String file) {
    assertTrue(
        ingredient.isJsonObject() && ingredient.getAsJsonObject().has("item"),
        "Only exact item ingredients are classified: " + file);
    return ingredient.getAsJsonObject().get("item").getAsString();
  }

  private static Recipe parse(String file, JsonObject json) {
    var type = json.get("type").getAsString();
    var inputs = new ArrayList<String>();
    var grid = new StringBuilder(type).append('|');
    if (type.equals("minecraft:crafting_shaped")) {
      var key = json.getAsJsonObject("key");
      for (var row : json.getAsJsonArray("pattern")) {
        for (char symbol : row.getAsString().toCharArray()) {
          if (symbol == ' ') {
            grid.append('_');
            continue;
          }
          var input = item(key.get(String.valueOf(symbol)), file);
          inputs.add(input);
          grid.append(input).append(',');
        }
        grid.append('/');
      }
    } else if (type.equals("minecraft:crafting_shapeless")) {
      var sorted = new ArrayList<String>();
      for (var ingredient : json.getAsJsonArray("ingredients")) sorted.add(item(ingredient, file));
      inputs.addAll(sorted);
      sorted.sort(String::compareTo);
      grid.append(sorted);
    } else {
      throw new AssertionError("Unclassified recipe type " + type + " in " + file);
    }
    var result = json.getAsJsonObject("result");
    int count = result.has("count") ? result.get("count").getAsInt() : 1;
    return new Recipe(file, result.get("id").getAsString(), count, inputs, grid.toString());
  }

  /** Producers per item: recipes plus in-world non-recipe actions. */
  private static Map<String, List<List<String>>> producers(List<Recipe> recipes) {
    var producers = new TreeMap<String, List<List<String>>>();
    for (var recipe : recipes)
      producers.computeIfAbsent(recipe.result(), ignored -> new ArrayList<>()).add(recipe.inputs());
    NON_RECIPE.forEach(
        (item, inputs) ->
            producers.computeIfAbsent(item, ignored -> new ArrayList<>()).add(inputs));
    return producers;
  }

  /**
   * Least-fixpoint reachability from vanilla inputs: an item becomes reachable only after one of
   * its producers has every input reachable, so a cyclic unlock never qualifies. The stage of an
   * item is the cheapest producer's latest input stage.
   */
  private static Map<String, Stage> reachableStages(List<Recipe> recipes) {
    var stages = new HashMap<String, Stage>(VANILLA);
    var producers = producers(recipes);
    boolean changed = true;
    while (changed) {
      changed = false;
      for (var produced : producers.entrySet()) {
        for (var inputs : produced.getValue()) {
          if (!stages.keySet().containsAll(inputs)) continue;
          var stage = Stage.STARTER;
          for (var input : inputs)
            if (stages.get(input).compareTo(stage) > 0) stage = stages.get(input);
          var known = stages.get(produced.getKey());
          if (known == null || stage.compareTo(known) < 0) {
            stages.put(produced.getKey(), stage);
            changed = true;
          }
        }
      }
    }
    return stages;
  }

  @Test
  void everyRecipeUsesClassifiedVanillaOrModInputsAndProducesAKnownItem() throws Exception {
    for (var recipe : recipes()) {
      assertTrue(EXPECTED.containsKey(recipe.result()), "Unknown recipe result " + recipe);
      assertEquals(
          recipe.file(),
          recipe.result().substring("factorycore:".length()) + ".json",
          "Recipe file is named after its result");
      assertTrue(recipe.count() >= 1, "Positive output " + recipe.file());
      for (var input : recipe.inputs()) {
        if (input.startsWith("minecraft:"))
          assertTrue(VANILLA.containsKey(input), "Unclassified vanilla input " + input);
        else assertTrue(EXPECTED.containsKey(input), "Unknown mod input " + input);
        assertFalse(
            input.endsWith("_cell") || input.endsWith("_energy_bank"),
            "No recipe consumes a storage cell or bank (populated contents could be lost)");
      }
    }
  }

  @Test
  void everySurvivalItemHasExactlyOneDocumentedSource() throws Exception {
    var sources = new HashSet<String>();
    for (var recipe : recipes())
      assertTrue(sources.add(recipe.result()), "One recipe per item: " + recipe.result());
    for (var item : NON_RECIPE.keySet())
      assertTrue(sources.add(item), "Upgrade/encoding items have no recipe: " + item);
    assertEquals(EXPECTED.keySet(), sources, "Every registered survival item has a source");
  }

  @Test
  void everySurvivalItemIsReachableFromVanillaWithoutCircularUnlocks() throws Exception {
    var stages = reachableStages(recipes());
    var actual = new TreeMap<String, Stage>();
    for (var item : EXPECTED.keySet()) {
      assertTrue(stages.containsKey(item), "Unreachable from vanilla materials: " + item);
      actual.put(item, stages.get(item));
    }
    assertEquals(new TreeMap<>(EXPECTED), actual, "R11 stage of every item (ADR 0048 table)");
  }

  @Test
  void upgradeTiersAreStrictlyOrderedAndConsumeTheirPredecessor() throws Exception {
    var stages = reachableStages(recipes());
    var modules =
        List.of(
            "factorycore:expansion_module",
            "factorycore:advanced_module",
            "factorycore:infinite_module");
    for (int i = 1; i < modules.size(); i++)
      assertTrue(
          stages.get(modules.get(i)).compareTo(stages.get(modules.get(i - 1))) > 0,
          "Each module belongs to a strictly later stage: " + modules.get(i));
    for (var chain :
        List.of(
            List.of("starter_item_cell", "expanded_item_cell", "advanced_item_cell"),
            List.of("advanced_item_cell", "infinite_item_cell"),
            List.of("starter_fluid_cell", "expanded_fluid_cell", "advanced_fluid_cell"),
            List.of("starter_energy_bank", "expanded_energy_bank", "advanced_energy_bank"))) {
      for (int i = 1; i < chain.size(); i++) {
        var higher = "factorycore:" + chain.get(i);
        var lower = "factorycore:" + chain.get(i - 1);
        assertTrue(NON_RECIPE.get(higher).contains(lower), higher + " consumes " + lower);
        assertTrue(stages.get(higher).compareTo(stages.get(lower)) > 0, higher + " is later");
      }
    }
    var recipes = new LinkedHashMap<String, Recipe>();
    for (var recipe : recipes()) recipes.put(recipe.result(), recipe);
    assertTrue(
        recipes
            .get("factorycore:wireless_terminal")
            .inputs()
            .contains("factorycore:storage_terminal"),
        "The handheld consumes the wired terminal it extends");
    assertTrue(
        recipes
            .get("factorycore:wireless_link")
            .inputs()
            .contains("factorycore:wireless_access_point"),
        "Paired links consume the earlier access point");
    assertTrue(
        stages.get("factorycore:network_cable").compareTo(Stage.STARTER) == 0
            && stages.get("factorycore:wireless_link").compareTo(Stage.ADVANCED) == 0,
        "R11: wired connections are cheaper than equivalent wireless pairs");
  }

  /**
   * ADR 0054: paid speed is a separate axis. The Throughput Upgrade is Advanced (diamonds), the
   * Overdrive Upgrade Endgame (Nether star), so R11's "higher per-interface throughput" and
   * "high-throughput factory upgrades" land in their stages and never gate earlier storage.
   */
  @Test
  void throughputUpgradesBelongToAdvancedAndEndgame() throws Exception {
    var stages = reachableStages(recipes());
    assertEquals(Stage.ADVANCED, stages.get("factorycore:throughput_upgrade"));
    assertEquals(Stage.ENDGAME, stages.get("factorycore:overdrive_upgrade"));
    for (var recipe : recipes())
      if (!recipe.result().endsWith("_upgrade"))
        for (var input : recipe.inputs())
          assertFalse(input.endsWith("_upgrade"), "No storage/link recipe needs a speed upgrade");
  }

  /** ADR 0054 numeric table, pinned like the rest of the D06 balance. */
  @Test
  void throughputTiersMatchTheTableAndKeepWiredAtLeastAsFastAsWireless() {
    var table = new LinkedHashMap<ThroughputTier, List<Long>>();
    for (var tier : ThroughputTier.values())
      table.put(
          tier,
          List.of(
              (long) tier.portRate(ResourceKind.ITEM),
              (long) tier.portRate(ResourceKind.FLUID),
              (long) tier.portRate(ResourceKind.ENERGY),
              (long) tier.bufferRate(),
              (long) tier.linkScale()));
    assertEquals(
        Map.of(
            ThroughputTier.BASE, List.of(128L, 1_000L, 1_000L, 64L, 1L),
            ThroughputTier.ADVANCED, List.of(256L, 4_000L, 8_000L, 256L, 1L),
            ThroughputTier.ENDGAME, List.of(1_024L, 16_000L, 32_000L, 576L, 4L)),
        table,
        "Port items/mB/FE, buffer items and link-pair multiplier per tick");
    assertEquals(
        (long) ItemBuffer.SLOTS * 64,
        ThroughputTier.ENDGAME.bufferRate(),
        "Overdrive moves a whole buffer per tick and no more");
    // R11: an equivalent-tier wired port offers at least a wireless pair's throughput. Links are
    // Advanced; an Overdrive pair is Endgame.
    for (var kind : ResourceKind.values()) {
      assertTrue(
          ThroughputTier.ADVANCED.portRate(kind) >= WirelessBudget.limit(kind),
          "Advanced wired port >= base link pair: " + kind);
      assertTrue(
          ThroughputTier.ENDGAME.portRate(kind)
              >= WirelessBudget.limit(kind) * ThroughputTier.ENDGAME.linkScale(),
          "Overdrive wired port >= Overdrive link pair: " + kind);
    }
  }

  @Test
  void noTwoRecipesShareAGrid() throws Exception {
    var grids = new HashMap<String, String>();
    for (var recipe : recipes()) {
      var previous = grids.put(recipe.grid(), recipe.file());
      assertTrue(previous == null, "Ambiguous grid: " + previous + " and " + recipe.file());
    }
  }

  @Test
  void frozenNumericTableMatchesTheImplementation() {
    var cells = new LinkedHashMap<CellTier, Long>();
    for (var tier : CellTier.values()) cells.put(tier, tier.capacity());
    assertEquals(
        Map.of(
            CellTier.STARTER_ITEM, 8_192L,
            CellTier.EXPANDED_ITEM, 65_536L,
            CellTier.ADVANCED_ITEM, 1_048_576L,
            CellTier.INFINITE_ITEM, Long.MAX_VALUE,
            CellTier.STARTER_FLUID, 16_000L,
            CellTier.EXPANDED_FLUID, 256_000L,
            CellTier.ADVANCED_FLUID, 4_096_000L),
        cells,
        "Cell capacities (item units / mB)");
    assertTrue(CellTier.INFINITE_ITEM.infinite(), "Only the Infinite item tier is unbounded");
    var banks = new LinkedHashMap<EnergyBankTier, List<Long>>();
    for (var tier : EnergyBankTier.values()) banks.put(tier, List.of(tier.capacity(), tier.rate()));
    assertEquals(
        Map.of(
            EnergyBankTier.STARTER, List.of(100_000L, 1_000L),
            EnergyBankTier.EXPANDED, List.of(1_000_000L, 4_000L),
            EnergyBankTier.ADVANCED, List.of(10_000_000L, 16_000L)),
        banks,
        "Energy bank FE capacity and FE/tick per direction");
    assertEquals(
        List.of(100_000L, 20L, 80L),
        List.of(FuelGenerator.CAPACITY, FuelGenerator.PER_BURN_TICK, FuelGenerator.MAX_OUTPUT),
        "Fuel generator buffer, FE per burn tick, FE/tick output");
    assertEquals(32, NetworkTopology.MAX_DEPTH, "Gateway nesting hard maximum");
    assertEquals(9, ItemBuffer.SLOTS, "Buffered interface slots");
    assertEquals(
        List.of(64L, 20_000L, 1_000L, 16L, 20_000L, 1_000L),
        List.of(
            (long) AccessPointEntity.RANGE,
            AccessPointEntity.CAPACITY,
            AccessPointEntity.INPUT_RATE,
            AccessPointEntity.TICK_COST,
            (long) WirelessTerminalItem.CAPACITY,
            (long) WirelessTerminalItem.CHARGE_RATE),
        "Wireless access point range/buffer/input/upkeep, handheld buffer/charge");
    assertEquals(
        List.of(256L, 20_000L, 1_000L, 32L),
        List.of(
            (long) LinkModule.RANGE,
            LinkModule.CAPACITY,
            LinkModule.INPUT_RATE,
            LinkModule.TICK_COST),
        "Link endpoint range/buffer/input/upkeep");
    assertEquals(
        List.of(10_000L, 1_000L),
        List.of(ProgramControllerEntity.CAPACITY, ProgramControllerEntity.INPUT_RATE),
        "Program controller buffer and input");
    assertEquals(9, PatternHolderEntity.SLOTS, "Patterns per Crafting Unit / Crafting Interface");
  }

  /**
   * ADR 0048 power curve: a Fuel Generator burning continuously yields PER_BURN_TICK FE/tick. Coal
   * (1,600 burn ticks) is the reference fuel.
   */
  @Test
  void powerCurveSupportsTheDocumentedSetups() {
    long sustained = FuelGenerator.PER_BURN_TICK;
    long accessPoint = AccessPointEntity.TICK_COST;
    long endpoint = LinkModule.TICK_COST;
    assertTrue(sustained >= accessPoint, "One generator sustains an open access point");
    assertTrue(
        sustained < endpoint && 2 * sustained >= endpoint,
        "A link end needs two generators (or banked FE): links are the costlier connection");
    assertEquals(32_000L, 1_600 * sustained, "One coal is 32,000 FE");
    assertEquals(1_000L, 1_600 * sustained / endpoint, "One coal runs one link end 1,000 ticks");
    assertTrue(
        FuelGenerator.MAX_OUTPUT >= 4 * sustained,
        "Output (80 FE/tick) can drain a stored buffer four times faster than burning refills it");
    assertEquals(
        6_250L,
        EnergyBankTier.STARTER.capacity() / accessPoint,
        "A full Starter Energy Bank keeps one access point open for 6,250 ticks");
  }
}
