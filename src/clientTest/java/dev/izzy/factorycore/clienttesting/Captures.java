package dev.izzy.factorycore.clienttesting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Screenshot evidence for one graphical suite. The names live once, in {@code
 * captures/<suite>.txt}: Gradle requires every listed file after the run, and {@link #grab} refuses
 * a name that is not listed, so the list and the verification cannot drift apart.
 */
final class Captures {
  private final String suite;
  private final Set<String> expected;
  private final Path directory;
  private final StringBuilder evidence;

  /** Screenshots go to {@code screenshots/} next to {@code report}; evidence lines may be null. */
  Captures(String suite, Path report, StringBuilder evidence) {
    this.suite = suite;
    expected = manifest(suite);
    directory = report.getParent();
    this.evidence = evidence;
  }

  static Set<String> manifest(String suite) {
    try (InputStream stream = Captures.class.getResourceAsStream("/captures/" + suite + ".txt")) {
      if (stream == null) throw new IllegalStateException("No capture manifest for " + suite);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .collect(Collectors.toUnmodifiableSet());
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read capture manifest " + suite, error);
    }
  }

  /** Saves the last rendered frame as {@code name} and records the window geometry. */
  void grab(Minecraft client, String name) {
    if (!expected.contains(name))
      throw new IllegalStateException(name + " is not listed in captures/" + suite + ".txt");
    try {
      Files.createDirectories(directory.resolve("screenshots"));
    } catch (IOException error) {
      throw new IllegalStateException("Cannot prepare screenshot directory", error);
    }
    var window = client.getWindow();
    if (evidence != null)
      evidence
          .append(name)
          .append(" framebuffer=")
          .append(window.getWidth())
          .append('x')
          .append(window.getHeight())
          .append(" gui=")
          .append(window.getGuiScaledWidth())
          .append('x')
          .append(window.getGuiScaledHeight())
          .append(" scale=")
          .append(window.getGuiScale())
          .append('\n');
    Screenshot.grab(
        directory.toFile(),
        name,
        client.getMainRenderTarget(),
        message ->
            System.out.println("FACTORYCORE_SCREENSHOT " + suite + " " + message.getString()));
  }
}
