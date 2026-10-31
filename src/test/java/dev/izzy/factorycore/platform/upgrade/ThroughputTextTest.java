package dev.izzy.factorycore.platform.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** Balance numbers reach tooltips only as arguments from {@link ThroughputTier}. */
class ThroughputTextTest {
  private static final Pattern PLACEHOLDER = Pattern.compile("%s");
  private static final Pattern DIGIT_RUN = Pattern.compile("\\d{2,}");

  /** Key → number of arguments the rendering code passes; a line may use a prefix of them. */
  private static final Map<String, Integer> PARAMETERIZED =
      Map.of(
          "tooltip.factorycore.throughput.advanced", 5,
          "tooltip.factorycore.throughput.endgame", 5,
          "gui.factorycore.buffer.rate.help", 3,
          "gui.factorycore.buffer.target.help", 1,
          "gui.factorycore.machine.rate.help", 3);

  private static JsonObject lang() throws Exception {
    try (var stream =
        ThroughputTextTest.class.getResourceAsStream("/assets/factorycore/lang/en_us.json")) {
      assertNotNull(stream, "lang on the test classpath");
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  @Test
  void parameterizedLinesTakeEveryNumberAsAnArgument() throws Exception {
    var lang = lang();
    for (var entry : PARAMETERIZED.entrySet()) {
      var text = lang.get(entry.getKey()).getAsString();
      long placeholders = PLACEHOLDER.matcher(text).results().count();
      assertTrue(
          placeholders >= 1 && placeholders <= entry.getValue(),
          entry.getKey() + " uses 1.." + entry.getValue() + " arguments, has " + placeholders);
      assertFalse(DIGIT_RUN.matcher(text).find(), entry.getKey() + " hard-codes a number");
    }
  }

  @Test
  void upgradeLinesCarryTierLimits() {
    for (var tier : List.of(ThroughputTier.ADVANCED, ThroughputTier.ENDGAME)) {
      var contents = (TranslatableContents) ThroughputContent.describe(tier).getContents();
      assertEquals(PARAMETERIZED.get(contents.getKey()), contents.getArgs().length);
    }
    var endgame =
        (TranslatableContents) ThroughputContent.describe(ThroughputTier.ENDGAME).getContents();
    assertEquals(List.of("1,024", "16,000", "32,000", "576", "4"), List.of(endgame.getArgs()));
  }

  /**
   * Q19 (M88): each upgrade's tooltip names every device that takes it by its block name, and the
   * upgrade slot's lines say what it accepts.
   */
  @Test
  void upgradeTooltipsNameTheDevicesThatTakeThem() throws Exception {
    var lang = lang();
    var fits = lang.get("tooltip.factorycore.throughput.fits.advanced").getAsString();
    for (var block :
        List.of(
            "importer", "exporter", "interface", "cable_port", "crafter", "wireless_access_point"))
      assertTrue(
          fits.contains(lang.get("block.factorycore." + block).getAsString()),
          "Throughput tooltip names " + block + ": " + fits);
    assertTrue(
        lang.get("tooltip.factorycore.throughput.fits.endgame")
            .getAsString()
            .contains(lang.get("block.factorycore.gateway").getAsString()),
        "Overdrive tooltip names the Gateway");
    var throughput = lang.get("item.factorycore.throughput_upgrade").getAsString();
    var overdrive = lang.get("item.factorycore.overdrive_upgrade").getAsString();
    var accepts = lang.get("gui.factorycore.upgrade_slot.accepts").getAsString();
    assertTrue(accepts.contains(throughput) && accepts.contains(overdrive), accepts);
    var link = lang.get("gui.factorycore.upgrade_slot.accepts.link").getAsString();
    assertTrue(link.contains(overdrive) && !link.contains(throughput), link);
  }
}
