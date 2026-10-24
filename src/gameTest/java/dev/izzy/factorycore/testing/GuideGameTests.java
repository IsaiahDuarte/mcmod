package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.platform.guide.GuideContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class GuideGameTests {
  private GuideGameTests() {}

  @GameTest(template = "empty")
  public static void firstLoginGivesExactlyOneGuide(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    h.assertTrue(
        Boolean.getBoolean("factorycore.noWelcomeGuide"), "Fixture login skips the welcome guide");
    h.assertTrue(
        player.getInventory().countItem(GuideContent.GUIDE.get()) == 0,
        "Fixture players start clean");
    player.getInventory().clearContent();
    h.assertTrue(GuideContent.welcome(player), "First welcome gives the guide");
    h.assertFalse(GuideContent.welcome(player), "Later logins do not");
    h.assertTrue(
        player.getInventory().countItem(GuideContent.GUIDE.get()) == 1,
        "Exactly one guide in inventory");
    player.connection.disconnect(Component.literal("done"));
    h.succeed();
  }

  /** U9: every pictured id is a real item and each page's first picture has a crafting recipe. */
  @GameTest(template = "empty")
  public static void everyGuidePictureIsAnItemWithARecipe(GameTestHelper h) {
    h.assertTrue(
        GuideContent.PICTURES.size() == GuideContent.PAGES, "One picture list per guide page");
    var recipes = h.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING);
    var access = h.getLevel().registryAccess();
    for (int page = 0; page < GuideContent.PAGES; page++) {
      var ids = GuideContent.PICTURES.get(page);
      h.assertFalse(ids.isEmpty(), "Page " + page + " has a picture");
      for (var id : ids)
        h.assertTrue(
            BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id)),
            "Page " + page + " pictures a registered item: " + id);
      var first = BuiltInRegistries.ITEM.get(ResourceLocation.parse(ids.getFirst()));
      h.assertTrue(
          recipes.stream().anyMatch(holder -> holder.value().getResultItem(access).is(first)),
          "Page " + page + " shows a craftable item's recipe: " + ids.getFirst());
    }
    h.succeed();
  }
}
