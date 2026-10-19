package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Every registered Factory Core block has a blockstate and every item an item model. */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class AssetCoverageGameTests {
  private AssetCoverageGameTests() {}

  private static boolean present(String path) {
    // Resolve through the mod file: the test mod's module cannot see main-mod resources directly.
    var file = net.neoforged.fml.ModList.get().getModFileById(FactoryCoreMod.MOD_ID).getFile();
    return java.nio.file.Files.isRegularFile(file.findResource("assets", "factorycore", path));
  }

  @GameTest(template = "empty")
  public static void everyRegisteredBlockAndItemHasArt(GameTestHelper helper) {
    int blocks = 0;
    int items = 0;
    for (var id : BuiltInRegistries.BLOCK.keySet())
      if (id.getNamespace().equals(FactoryCoreMod.MOD_ID)) {
        blocks++;
        helper.assertTrue(
            present("blockstates/" + id.getPath() + ".json"), "Blockstate for block " + id);
      }
    for (var id : BuiltInRegistries.ITEM.keySet())
      if (id.getNamespace().equals(FactoryCoreMod.MOD_ID)) {
        items++;
        helper.assertTrue(
            present("models/item/" + id.getPath() + ".json"), "Item model for item " + id);
      }
    helper.assertTrue(blocks == 17 && items == 32, "Inventory " + blocks + "/" + items);
    helper.succeed();
  }
}
