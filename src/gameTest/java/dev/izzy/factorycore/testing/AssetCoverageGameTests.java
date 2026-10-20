package dev.izzy.factorycore.testing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Packaged-resource coverage for every registered Factory Core block and item: each block state has
 * a blockstate entry that selects at least one model, every referenced Factory Core model and
 * texture exists, every item has an item model and an English name, and translated item
 * descriptions only name registered items. This proves resource wiring, not appearance; client
 * captures cover appearance.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class AssetCoverageGameTests {
  private static final String NAMESPACE = FactoryCoreMod.MOD_ID;

  private AssetCoverageGameTests() {}

  private static Path resource(String path) {
    // Resolve through the mod file: the test mod's module cannot see main-mod resources directly.
    var file = net.neoforged.fml.ModList.get().getModFileById(NAMESPACE).getFile();
    return file.findResource(("assets/" + NAMESPACE + "/" + path).split("/"));
  }

  private static boolean present(String path) {
    return Files.isRegularFile(resource(path));
  }

  private static JsonObject json(String path) {
    try {
      return JsonParser.parseString(Files.readString(resource(path))).getAsJsonObject();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  /** {@code factorycore:block/x} → {@code block/x}; null for another namespace. */
  private static String local(String location) {
    if (location.startsWith("#")) return null;
    if (!location.contains(":")) return null; // Unqualified locations are vanilla.
    var parts = location.split(":", 2);
    return parts[0].equals(NAMESPACE) ? parts[1] : null;
  }

  private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  private static boolean matchesKey(BlockState state, String key) {
    if (key.isEmpty()) return true;
    for (var condition : key.split(",")) {
      var pair = condition.split("=", 2);
      var property = state.getBlock().getStateDefinition().getProperty(pair[0]);
      if (property == null || !value(state, property).equals(pair[1])) return false;
    }
    return true;
  }

  private static boolean matchesWhen(BlockState state, JsonObject when) {
    if (when.has("OR")) {
      for (var alternative : when.getAsJsonArray("OR"))
        if (matchesWhen(state, alternative.getAsJsonObject())) return true;
      return false;
    }
    for (var entry : when.entrySet()) {
      var property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
      if (property == null) return false;
      var accepted = Set.of(entry.getValue().getAsString().split("\\|"));
      if (!accepted.contains(value(state, property))) return false;
    }
    return true;
  }

  private static void models(JsonElement apply, List<String> out) {
    if (apply.isJsonArray()) for (var entry : apply.getAsJsonArray()) models(entry, out);
    else out.add(apply.getAsJsonObject().get("model").getAsString());
  }

  /** Models selected for {@code state}; empty means the state would render the missing model. */
  private static List<String> selected(BlockState state, JsonObject definition) {
    var out = new ArrayList<String>();
    if (definition.has("variants")) {
      for (var variant : definition.getAsJsonObject("variants").entrySet())
        if (matchesKey(state, variant.getKey())) models(variant.getValue(), out);
    }
    if (definition.has("multipart")) {
      for (var part : definition.getAsJsonArray("multipart")) {
        var object = part.getAsJsonObject();
        if (!object.has("when") || matchesWhen(state, object.getAsJsonObject("when")))
          models(object.get("apply"), out);
      }
    }
    return out;
  }

  /** Checks a Factory Core model, its Factory Core parents and their Factory Core textures. */
  private static void checkModel(
      GameTestHelper helper, String model, String from, Set<String> visited) {
    var path = local(model);
    if (path == null || !visited.add(path)) return;
    helper.assertTrue(present("models/" + path + ".json"), "Model " + model + " used by " + from);
    var object = json("models/" + path + ".json");
    if (object.has("parent"))
      checkModel(helper, object.get("parent").getAsString(), model, visited);
    if (object.has("textures"))
      for (var texture : object.getAsJsonObject("textures").entrySet()) {
        var texturePath = local(texture.getValue().getAsString());
        if (texturePath != null)
          helper.assertTrue(
              present("textures/" + texturePath + ".png"),
              "Texture " + texture.getValue().getAsString() + " in " + model);
      }
  }

  @GameTest(template = "empty")
  public static void everyRegisteredBlockAndItemHasArt(GameTestHelper helper) {
    var lang = json("lang/en_us.json");
    var visited = new HashSet<String>();
    int blocks = 0;
    int states = 0;
    for (var id : BuiltInRegistries.BLOCK.keySet()) {
      if (!id.getNamespace().equals(NAMESPACE)) continue;
      blocks++;
      Block block = BuiltInRegistries.BLOCK.get(id);
      String blockstate = "blockstates/" + id.getPath() + ".json";
      helper.assertTrue(present(blockstate), "Blockstate for block " + id);
      helper.assertTrue(lang.has(block.getDescriptionId()), "English name for block " + id);
      var definition = json(blockstate);
      for (var state : block.getStateDefinition().getPossibleStates()) {
        states++;
        var models = selected(state, definition);
        helper.assertTrue(!models.isEmpty(), "No model selected for " + state);
        for (var model : models) checkModel(helper, model, state.toString(), visited);
      }
    }
    int items = 0;
    var registered = new HashSet<String>();
    for (var id : BuiltInRegistries.ITEM.keySet()) {
      if (!id.getNamespace().equals(NAMESPACE)) continue;
      items++;
      registered.add(id.getPath());
      var item = BuiltInRegistries.ITEM.get(id);
      helper.assertTrue(
          present("models/item/" + id.getPath() + ".json"), "Item model for item " + id);
      checkModel(helper, NAMESPACE + ":item/" + id.getPath(), id.toString(), visited);
      helper.assertTrue(lang.has(item.getDescriptionId()), "English name for item " + id);
      if (item instanceof BlockItem blockItem)
        helper.assertTrue(
            BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).getPath().equals(id.getPath()),
            "Block item shares its block's id: " + id);
    }
    for (var key : lang.keySet())
      if (key.startsWith("tooltip." + NAMESPACE + ".") && key.endsWith(".description")) {
        var path = key.substring(("tooltip." + NAMESPACE + ".").length(), key.length() - 12);
        helper.assertTrue(registered.contains(path), "Description for unregistered item " + key);
      }
    for (var key :
        List.of(
            "tooltip.factorycore.lamp_legend",
            "tooltip.factorycore.lamp_legend_program",
            "tooltip.factorycore.lamp_legend_link"))
      helper.assertTrue(lang.has(key), "Lamp legend " + key);
    // Lower bounds catch an empty registry without pinning the count other slices extend.
    helper.assertTrue(
        blocks >= 17 && items >= 32 && states > blocks,
        "Inventory " + blocks + " blocks / " + states + " states / " + items + " items");
    helper.succeed();
  }
}
