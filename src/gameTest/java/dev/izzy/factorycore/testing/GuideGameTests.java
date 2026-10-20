package dev.izzy.factorycore.testing;

import dev.izzy.factorycore.platform.guide.GuideContent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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
}
