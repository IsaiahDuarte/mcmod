package dev.izzy.factorycore.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.crafting.JobStatus;
import dev.izzy.factorycore.platform.storage.StagingClass;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every translation key the code names as a literal exists in {@code en_us.json}, and every literal
 * prefix of a key built at runtime ({@code "status.factorycore." + state}) matches at least one
 * key. Catches a renamed or deleted lang entry that only a player would otherwise see as raw text.
 */
class LangKeysTest {
  private static final Pattern LITERAL =
      Pattern.compile("(?:translatable|I18n\\.get|I18n\\.exists)\\(\\s*\"([a-z0-9_.]+)\"");

  @Test
  void everyLiteralKeyAndPrefixResolves() throws Exception {
    Set<String> lang;
    try (var stream = getClass().getResourceAsStream("/assets/factorycore/lang/en_us.json")) {
      assertNotNull(stream, "lang on the test classpath");
      lang =
          JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .keySet();
    }
    var missing = new TreeSet<String>();
    int checked = 0;
    try (var files = Files.walk(Path.of(System.getProperty("factorycore.test.sources")))) {
      for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        var matcher = LITERAL.matcher(Files.readString(file));
        while (matcher.find()) {
          var key = matcher.group(1);
          checked++;
          boolean prefix = key.endsWith(".") || key.endsWith("_");
          boolean found =
              prefix ? lang.stream().anyMatch(entry -> entry.startsWith(key)) : lang.contains(key);
          if (!found) missing.add(key + " (" + file.getFileName() + ")");
        }
      }
    }
    assertTrue(checked > 100, "Translation keys must be inspected: " + checked);
    assertEquals(Set.of(), missing);
  }

  /**
   * M52: typed job, rule, step and staging values are shown through a key built from the value, so
   * every value needs its entry; a value added without one would show raw text.
   */
  @Test
  void everyTypedReasonAndStatusHasItsKey() throws Exception {
    Set<String> lang;
    try (var stream = getClass().getResourceAsStream("/assets/factorycore/lang/en_us.json")) {
      assertNotNull(stream, "lang on the test classpath");
      lang =
          JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .keySet();
    }
    var expected = new TreeSet<String>();
    for (var value : JobReason.values())
      expected.add("gui.factorycore.terminal.job.reason." + value.key());
    for (var value : JobStatus.values())
      expected.add("gui.factorycore.terminal.job.status." + value.key());
    for (var value : CraftingJob.State.values())
      expected.add("gui.factorycore.terminal.job.state." + EnumKeys.key(value));
    for (var value : TerminalJobPackets.StepStatus.values())
      expected.add("gui.factorycore.terminal.job.step." + value.key());
    for (var value : StagingClass.values()) {
      expected.add("gui.factorycore.recovery.class." + value.key());
      expected.add("gui.factorycore.recovery.next." + value.key());
      expected.add("command.factorycore.recovery." + value.key());
    }
    var missing = new TreeSet<>(expected);
    missing.removeAll(lang);
    assertEquals(Set.of(), missing);
  }

  /**
   * A key written twice silently keeps only one text (Gson takes the last), so a new button can
   * overwrite an unrelated message, as the A2 Plan button once replaced the plan summary line.
   */
  @Test
  void everyKeyIsDefinedOnce() throws Exception {
    String text;
    try (var stream = getClass().getResourceAsStream("/assets/factorycore/lang/en_us.json")) {
      assertNotNull(stream, "lang on the test classpath");
      text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    var seen = new TreeSet<String>();
    var duplicates = new TreeSet<String>();
    var key = Pattern.compile("(?m)^\\s*\"([^\"]+)\"\\s*:").matcher(text);
    while (key.find()) if (!seen.add(key.group(1))) duplicates.add(key.group(1));
    assertTrue(seen.size() > 100, "Keys must be inspected: " + seen.size());
    assertEquals(Set.of(), duplicates);
  }

  /**
   * Implementation words that mean nothing to a player (docs/UX_PLAN.md U1). "Draft" is allowed
   * only in the program editor, where a saved draft is a real, separately deployed program.
   */
  private static final Pattern INTERNAL_WORD =
      Pattern.compile(
          "\\b(stag(?:ing|ed)|principal|segment|budget|draft|canonical|leased?|quarantin\\w*"
              + "|backing|schema|handler|provider|branch(?:es)?|endpoints?)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern PLAYER_KEY =
      Pattern.compile("(?:gui|tooltip|guide|status|message)\\.factorycore\\..*");

  @Test
  void playerTextAvoidsInternalWords() throws Exception {
    var offending = new TreeSet<String>();
    int checked = 0;
    try (var stream = getClass().getResourceAsStream("/assets/factorycore/lang/en_us.json")) {
      assertNotNull(stream, "lang on the test classpath");
      var lang =
          JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var entry : lang.entrySet()) {
        if (!PLAYER_KEY.matcher(entry.getKey()).matches()) continue;
        checked++;
        var matcher = INTERNAL_WORD.matcher(entry.getValue().getAsString());
        while (matcher.find()) {
          boolean programDraft =
              entry.getKey().startsWith("gui.factorycore.program.")
                  && matcher.group().equalsIgnoreCase("draft");
          if (!programDraft) offending.add(entry.getKey() + " (" + matcher.group() + ")");
        }
      }
    }
    assertTrue(checked > 300, "Player-facing keys must be inspected: " + checked);
    assertEquals(Set.of(), offending);
  }
}
