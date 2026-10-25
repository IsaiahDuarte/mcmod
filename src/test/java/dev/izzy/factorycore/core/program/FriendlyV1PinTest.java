package dev.izzy.factorycore.core.program;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR 0062: language v2 is a strict superset. Every v1 fixture and template must compile to the IR
 * pinned in {@code program/v1-ir.txt}; v2 fixtures name the slice that must make them compile.
 * Regenerate the pin only for an ADR-approved change by setting {@code FACTORYCORE_UPDATE_PIN} to
 * the absolute path of {@code src/test/resources/program/v1-ir.txt}.
 */
class FriendlyV1PinTest {
  private static Path root() {
    try {
      var url = FriendlyV1PinTest.class.getResource("/program/v1");
      assertNotNull(url, "Program fixtures on the test classpath");
      return Path.of(url.toURI()).getParent();
    } catch (java.net.URISyntaxException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  /** Slices D2–D6 add themselves here when their fixtures compile. */
  private static final Set<String> IMPLEMENTED = Set.of("D2", "D3", "D4");

  private static final Pattern SLICE = Pattern.compile("(?m)^# slice: (D[2-6])$");
  private static final Pattern FORM = Pattern.compile("(?m)^# form: (.+)$");

  private static Map<String, String> sources(String folder) throws IOException {
    var found = new TreeMap<String, String>();
    try (Stream<Path> files = Files.list(root().resolve(folder))) {
      for (var file : files.filter(p -> p.toString().endsWith(".fc")).toList())
        found.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
    }
    return found;
  }

  private static String pinned() throws IOException {
    var lines = new ArrayList<String>();
    for (var fixture : sources("v1").entrySet())
      lines.add(entry(fixture.getKey(), fixture.getValue()));
    for (var template : ProgramTemplates.ALL)
      lines.add(entry("template " + template.name(), template.source()));
    return String.join("\n", lines) + "\n";
  }

  private static String entry(String name, String source) {
    var result = FriendlyCompiler.compile(source);
    assertTrue(result.ok(), () -> name + ": " + result.diagnostics());
    return name + "\t" + result.program();
  }

  @Test
  void v1ProgramsCompileToThePinnedIr() throws IOException {
    var now = pinned();
    var update = System.getenv("FACTORYCORE_UPDATE_PIN");
    if (update != null && !update.isEmpty()) Files.writeString(Path.of(update), now);
    var pin = root().resolve("v1-ir.txt");
    assertTrue(Files.isRegularFile(pin), "Pinned v1 IR exists");
    assertEquals(Files.readString(pin, StandardCharsets.UTF_8), now, "v1 IR changed (ADR 0062)");
  }

  @Test
  void everyV2FormHasAFixtureNamingItsSlice() throws IOException {
    var forms = new ArrayList<String>();
    for (var fixture : sources("v2").entrySet()) {
      var slice = SLICE.matcher(fixture.getValue());
      var form = FORM.matcher(fixture.getValue());
      assertTrue(slice.find() && form.find(), fixture.getKey() + " names its slice and form");
      forms.add(form.group(1));
      var result = FriendlyCompiler.compile(fixture.getValue());
      if (IMPLEMENTED.contains(slice.group(1)))
        assertTrue(result.ok(), () -> fixture.getKey() + ": " + result.diagnostics());
      else
        assertFalse(
            result.ok(),
            fixture.getKey() + " compiles before " + slice.group(1) + " is marked done");
    }
    for (var required :
        List.of(
            "move between two machines",
            "side and slots on an endpoint",
            "else and else if",
            "and, or, not, parentheses",
            "target has CMP INT of filter",
            "one * in an item ID path",
            "filter except filter",
            "redstone(target)",
            "emit redstone INT on target"))
      assertTrue(forms.contains(required), "ADR 0062 form has a fixture: " + required);
  }
}
