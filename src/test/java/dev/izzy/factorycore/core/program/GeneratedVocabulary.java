package dev.izzy.factorycore.core.program;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The vanilla-plus-Factory-Core vocabulary that {@code runData} generates (ADR 0068). */
final class GeneratedVocabulary {
  private static Vocabulary loaded;

  private GeneratedVocabulary() {}

  static synchronized Vocabulary get() {
    if (loaded == null)
      try (var stream =
          GeneratedVocabulary.class.getResourceAsStream(
              "/data/factorycore/program_vocabulary.txt")) {
        if (stream == null) throw new IllegalStateException("Run runData: no program vocabulary");
        loaded = Vocabulary.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    return loaded;
  }
}
