package dev.izzy.factorycore.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.crafting.JobStatus;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.network.AccessPackets;
import dev.izzy.factorycore.platform.program.ProgramControllerEntity;
import dev.izzy.factorycore.platform.signal.SignalPackets;
import dev.izzy.factorycore.platform.storage.BufferSettingsPackets;
import dev.izzy.factorycore.platform.storage.DrivePackets;
import dev.izzy.factorycore.platform.storage.MachinePresetPackets;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import dev.izzy.factorycore.platform.storage.RecoveryActions;
import dev.izzy.factorycore.platform.storage.RecoveryPackets;
import dev.izzy.factorycore.platform.storage.StagingClass;
import dev.izzy.factorycore.platform.storage.TerminalCraftingPackets;
import dev.izzy.factorycore.platform.storage.TerminalJobPackets;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
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
      Pattern.compile(
          "(?:translatable|I18n\\.get|I18n\\.exists|Refusal\\.state|Refusal\\.argument)"
              + "\\(\\s*\"([a-z0-9_.]+)\"");

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
   * M142: each menu's typed status is shown through a key built from the value, so every value
   * needs its entry.
   */
  @Test
  void everyMenuStatusHasItsKey() throws Exception {
    Set<String> lang;
    try (var stream = getClass().getResourceAsStream("/assets/factorycore/lang/en_us.json")) {
      assertNotNull(stream, "lang on the test classpath");
      lang =
          JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .keySet();
    }
    var expected = new TreeSet<String>();
    keys(expected, "gui.factorycore.signal.status.", SignalPackets.Status.class);
    keys(expected, "gui.factorycore.machine.status.", MachineSettingsPackets.Status.class);
    keys(expected, "gui.factorycore.preset.status.", MachinePresetPackets.Status.class);
    keys(expected, "gui.factorycore.buffer.status.", BufferSettingsPackets.Status.class);
    keys(expected, "gui.factorycore.drive.status.", DrivePackets.Status.class);
    keys(expected, "gui.factorycore.access.status.", AccessPackets.Status.class);
    keys(expected, "command.factorycore.recovery.", RecoveryActions.Result.class);
    for (var value : RecoveryPackets.Status.values()) expected.add(value.translation());
    keys(expected, "gui.factorycore.program.status.", WorldPrograms.RuntimeStatus.class);
    keys(expected, "status.factorycore.program.", ProgramControllerEntity.State.class);
    keys(expected, "gui.factorycore.terminal.status.", TerminalPackets.Status.class);
    keys(
        expected,
        "gui.factorycore.terminal.grid.status.",
        TerminalCraftingPackets.GridStatus.class);
    keys(expected, "gui.factorycore.status.network.", NetworkTopology.Status.class);
    keys(
        expected,
        "gui.factorycore.crafter.activity.",
        dev.izzy.factorycore.platform.crafting.CrafterPackets.Activity.class);
    keys(
        expected,
        "gui.factorycore.crafter.machine.",
        dev.izzy.factorycore.platform.crafting.CrafterPackets.MachineState.class);
    for (var side : net.minecraft.core.Direction.values())
      expected.add("gui.factorycore.crafter.side." + side.getSerializedName());
    keys(
        expected,
        "gui.factorycore.terminal.patterns.status.",
        dev.izzy.factorycore.platform.storage.TerminalPatternPackets.Status.class);
    var missing = new TreeSet<>(expected);
    missing.removeAll(lang);
    assertEquals(Set.of(), missing);
  }

  private static void keys(Set<String> into, String prefix, Class<? extends Enum<?>> type) {
    for (var value : type.getEnumConstants()) into.add(prefix + EnumKeys.key(value));
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

  /**
   * Q84: another language file may only use keys that {@code en_us.json} has, with the same number
   * of {@code %s} placeholders, so a renamed key or a dropped argument cannot ship silently. Checks
   * every shipped language plus a stub French file in test resources, and that the check itself
   * catches an unknown key and a placeholder mismatch.
   */
  @Test
  void otherLanguagesUseOnlyKnownKeysWithMatchingPlaceholders() throws Exception {
    var english = read(getClass().getResourceAsStream("/assets/factorycore/lang/en_us.json"));
    var shipped =
        Path.of(System.getProperty("factorycore.test.sources"))
            .getParent()
            .resolve("resources/assets/factorycore/lang");
    var files = new java.util.ArrayList<Path>();
    try (var listing = Files.list(shipped)) {
      listing
          .filter(path -> path.toString().endsWith(".json"))
          .filter(path -> !path.getFileName().toString().equals("en_us.json"))
          .forEach(files::add);
    }
    // read() asserts the stub second language is on the test classpath and closes it.
    var problems =
        new TreeSet<String>(
            languageProblems(
                english,
                read(getClass().getResourceAsStream("/lang-stub/fr_fr.json")),
                "fr_fr (stub)"));
    for (var file : files)
      problems.addAll(
          languageProblems(
              english, read(Files.newInputStream(file)), file.getFileName().toString()));
    assertEquals(Set.of(), problems);

    var broken = new java.util.HashMap<String, String>();
    broken.put("message.factorycore.no_such_key", "x");
    broken.put("message.factorycore.refused", "Refusé");
    assertEquals(
        Set.of(
            "bad: unknown key message.factorycore.no_such_key",
            "bad: message.factorycore.refused has 0 placeholders, en_us has 1"),
        languageProblems(english, broken, "bad"));
  }

  private static java.util.Map<String, String> read(java.io.InputStream stream)
      throws java.io.IOException {
    assertNotNull(stream, "lang file");
    try (stream) {
      var map = new java.util.TreeMap<String, String>();
      for (var entry :
          JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
              .getAsJsonObject()
              .entrySet()) map.put(entry.getKey(), entry.getValue().getAsString());
      return map;
    }
  }

  private static Set<String> languageProblems(
      java.util.Map<String, String> english, java.util.Map<String, String> other, String name) {
    var problems = new TreeSet<String>();
    for (var entry : other.entrySet()) {
      var base = english.get(entry.getKey());
      if (base == null) {
        problems.add(name + ": unknown key " + entry.getKey());
        continue;
      }
      long expected = placeholders(base), actual = placeholders(entry.getValue());
      if (expected != actual)
        problems.add(
            name
                + ": "
                + entry.getKey()
                + " has "
                + actual
                + " placeholders, en_us has "
                + expected);
    }
    return problems;
  }

  private static long placeholders(String text) {
    return Pattern.compile("%(?:\\d+\\$)?s").matcher(text.replace("%%", "")).results().count();
  }
}
