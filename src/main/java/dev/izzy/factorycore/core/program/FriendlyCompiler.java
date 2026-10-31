package dev.izzy.factorycore.core.program;

import java.util.List;

/**
 * The program language's entry point: sentence programs (ADR 0068) compiled into {@link ProgramIr}.
 * Compilation is pure and bounded by the limits here; it never touches the world.
 */
public final class FriendlyCompiler {
  public static final int MAX_SOURCE = 16_384;
  public static final int MAX_STATEMENTS = 512;
  public static final int MAX_DEPTH = 8;
  public static final int MAX_DIAGNOSTICS = 16;
  public static final int MAX_STRING = 128;
  public static final long DEFAULT_LIMIT = 64;
  public static final long MAX_LIMIT = 4_096;

  /**
   * One-based source location and a player-facing message, with an optional {@code hint} (such as
   * "Did you mean coal?") and {@code example} of a correct line; both may be null.
   */
  public record Diagnostic(int line, int column, String message, String hint, String example) {
    public Diagnostic(int line, int column, String message) {
      this(line, column, message, null, null);
    }
  }

  /** Exactly one of {@code program} and a nonempty {@code diagnostics} list is meaningful. */
  public record Result(ProgramIr.Program program, List<Diagnostic> diagnostics) {
    public Result {
      diagnostics = List.copyOf(diagnostics);
    }

    public boolean ok() {
      return program != null;
    }
  }

  private FriendlyCompiler() {}

  /** Compiles with no vocabulary: every name is taken literally as written (tests, reloads). */
  public static Result compile(String source) {
    return compile(source, Vocabulary.NONE);
  }

  /** Compiles with item, fluid and tag names resolved through {@code vocabulary}. */
  public static Result compile(String source, Vocabulary vocabulary) {
    return SentenceCompiler.compile(source, vocabulary);
  }

  /**
   * The vocabulary-free spelling a deployment is saved and reloaded with (ADR 0068): the same
   * sentences with every name written as its ID, so a changed mod list or language never changes
   * what a deployed program means.
   */
  public static String canonical(ProgramIr.Program program) {
    return ProgramPrinter.print(program, Vocabulary.NONE);
  }
}
