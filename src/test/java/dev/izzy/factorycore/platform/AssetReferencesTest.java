package dev.izzy.factorycore.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.izzy.factorycore.platform.client.ui.Panels;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Resource-level art checks: every model and texture reference resolves, finished art uses only
 * Factory Core textures (vanilla parents supply geometry/transforms only), every texture is a 16x16
 * PNG and none is orphaned (screen sprites are checked separately against the panel kit). Registry
 * coverage is checked in-game by {@code AssetCoverageGameTests}.
 */
class AssetReferencesTest {
  /** Vanilla parents that carry no textures of their own. */
  private static final Set<String> VANILLA_PARENTS =
      Set.of(
          "minecraft:block/block",
          "minecraft:block/cube",
          "minecraft:block/cube_bottom_top",
          "minecraft:block/orientable",
          "minecraft:item/generated");

  /** Screen sprites (ADR 0064) are referenced from code, not models, and may be any size. */
  private static final String GUI_SPRITES = "gui/sprites/";

  private static Path assets() throws Exception {
    var url = AssetReferencesTest.class.getResource("/assets/factorycore/lang/en_us.json");
    assertNotNull(url, "Assets on the test classpath");
    return Path.of(url.toURI()).getParent().getParent();
  }

  private static JsonObject json(Path path) throws IOException {
    return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
  }

  private static List<Path> files(Path directory, String suffix) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(path -> path.toString().endsWith(suffix)).sorted().toList();
    }
  }

  private static Path model(Path root, String id) {
    assertTrue(id.startsWith("factorycore:"), "Factory Core model expected: " + id);
    return root.resolve("models/" + id.substring("factorycore:".length()) + ".json");
  }

  private static void collectModels(JsonElement element, Set<String> out) {
    if (element.isJsonObject()) {
      var object = element.getAsJsonObject();
      if (object.has("model")) out.add(object.get("model").getAsString());
      for (var entry : object.entrySet()) collectModels(entry.getValue(), out);
    } else if (element.isJsonArray()) {
      for (var child : element.getAsJsonArray()) collectModels(child, out);
    }
  }

  @Test
  void everyReferenceResolvesToFactoryCoreArt() throws Exception {
    var root = assets();
    var used = new HashSet<String>();
    var blockstateModels = new TreeSet<String>();
    for (var state : files(root.resolve("blockstates"), ".json"))
      collectModels(json(state), blockstateModels);
    for (var id : blockstateModels)
      assertTrue(Files.isRegularFile(model(root, id)), "Blockstate model missing: " + id);

    for (var path : files(root.resolve("models"), ".json")) {
      var model = json(path);
      var variables = new HashSet<String>();
      var current = model;
      while (current != null) {
        if (current.has("textures"))
          for (var entry : current.getAsJsonObject("textures").entrySet()) {
            variables.add(entry.getKey());
            var value = entry.getValue().getAsString();
            if (value.startsWith("#")) continue;
            assertTrue(
                value.startsWith("factorycore:block/") || value.startsWith("factorycore:item/"),
                path.getFileName() + " uses a non-Factory Core texture: " + value);
            var texture = root.resolve("textures/" + value.substring(12) + ".png");
            assertTrue(Files.isRegularFile(texture), "Missing texture " + value);
            used.add(value.substring(12));
          }
        if (!current.has("parent")) break;
        var parent = current.get("parent").getAsString();
        if (VANILLA_PARENTS.contains(parent)) break;
        var parentPath = model(root, parent);
        assertTrue(Files.isRegularFile(parentPath), "Missing parent " + parent);
        current = json(parentPath);
      }
      if (model.has("elements"))
        for (var element : model.getAsJsonArray("elements"))
          for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
            var texture = face.getValue().getAsJsonObject().get("texture").getAsString();
            assertTrue(
                variables.contains(texture.substring(1)),
                path.getFileName() + " face uses undefined " + texture);
          }
    }

    var textures = files(root.resolve("textures"), ".png");
    assertFalse(textures.isEmpty(), "Native textures exist");
    for (var texture : textures) {
      var name = root.resolve("textures").relativize(texture).toString().replace('\\', '/');
      if (name.startsWith(GUI_SPRITES)) continue;
      assertTrue(used.contains(name.substring(0, name.length() - 4)), "Orphan texture " + name);
      BufferedImage image;
      try (var input = Files.newInputStream(texture)) {
        image = ImageIO.read(input);
      }
      assertNotNull(image, "Readable PNG " + name);
      assertEquals(16, image.getWidth(), "16 px wide: " + name);
      assertEquals(16, image.getHeight(), "16 px tall: " + name);
    }
  }

  @Test
  void everyScreenSpriteIsUsedByThePanelKitAndExists() throws Exception {
    var directory = assets().resolve("textures/" + GUI_SPRITES);
    var onDisk = new TreeSet<String>();
    for (var sprite : files(directory, ".png")) {
      var name = sprite.getFileName().toString();
      onDisk.add(name.substring(0, name.length() - 4));
      try (var input = Files.newInputStream(sprite)) {
        assertNotNull(ImageIO.read(input), "Readable PNG " + name);
      }
    }
    var used = new TreeSet<String>();
    for (var field : Panels.class.getFields())
      if (field.getType() == ResourceLocation.class
          && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        var id = (ResourceLocation) field.get(null);
        assertEquals("factorycore", id.getNamespace(), field.getName());
        used.add(id.getPath());
      }
    assertFalse(used.isEmpty(), "The panel kit declares sprites");
    assertEquals(used, onDisk, "Panel kit sprites and generated sprite files match");
  }
}
