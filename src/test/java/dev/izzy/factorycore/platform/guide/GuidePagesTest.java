package dev.izzy.factorycore.platform.guide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GuidePagesTest {
  private static com.google.gson.JsonObject lang() throws Exception {
    try (var input =
        GuidePagesTest.class.getResourceAsStream("/assets/factorycore/lang/en_us.json")) {
      assertNotNull(input, "Lang file on the test classpath");
      return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject();
    }
  }

  @Test
  void everyPageIsTranslatedAndFitsOneBookPage() throws Exception {
    var lang = lang();
    for (int page = 0; page < GuideContent.PAGES; page++) {
      var text = lang.get(GuideContent.pageKey(page));
      assertNotNull(text, "Missing " + GuideContent.pageKey(page));
      // Vanilla book pages fit about 14 lines of ~19 characters.
      assertTrue(text.getAsString().length() <= 256, "Page too long: " + page);
    }
    assertFalse(lang.has(GuideContent.pageKey(GuideContent.PAGES)), "Unlisted extra page");
  }

  @Test
  void guideCoversTheRequiredOnboardingTopics() throws Exception {
    var lang = lang();
    var all = new StringBuilder();
    for (int page = 0; page < GuideContent.PAGES; page++)
      all.append(lang.get(GuideContent.pageKey(page)).getAsString()).append('\n');
    for (var topic :
        new String[] {
          "Network Controller",
          "Storage Drive",
          "hopper",
          "upgrade",
          // ADR 0059: one Interface; the guide must teach its Machine and Buffer modes.
          "Interface",
          "Machine mode",
          "Buffer mode",
          "Pattern",
          "Program Controller",
          "Wireless",
          "Gateway",
          "Progression",
          "Nether star",
          "Throughput",
          "Overdrive"
        }) assertTrue(all.toString().contains(topic), "Guide is missing topic: " + topic);
  }
}
