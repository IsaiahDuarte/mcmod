package dev.izzy.factorycore.core;

import java.util.List;
import java.util.Objects;

/**
 * Q84: a refusal a player can see, as a translation key plus plain arguments (strings or numbers)
 * instead of an English message. {@link State} and {@link Argument} keep the usual exception types,
 * so existing handlers catch them unchanged; {@link #getMessage()} stays readable English for logs.
 * The platform turns {@link Translated} into a translatable component.
 */
public final class Refusal {
  private Refusal() {}

  /** A refusal's key and arguments. */
  public interface Translated {
    String key();

    List<Object> args();
  }

  public static IllegalStateException state(String key, String english, Object... args) {
    return new State(key, english, List.of(args));
  }

  public static IllegalArgumentException argument(String key, String english, Object... args) {
    return new Argument(key, english, List.of(args));
  }

  private static final class State extends IllegalStateException implements Translated {
    private static final long serialVersionUID = 1L;
    private final String key;
    private final transient List<Object> args;

    State(String key, String english, List<Object> args) {
      super(english);
      this.key = Objects.requireNonNull(key);
      this.args = args;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public List<Object> args() {
      return args;
    }
  }

  private static final class Argument extends IllegalArgumentException implements Translated {
    private static final long serialVersionUID = 1L;
    private final String key;
    private final transient List<Object> args;

    Argument(String key, String english, List<Object> args) {
      super(english);
      this.key = Objects.requireNonNull(key);
      this.args = args;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public List<Object> args() {
      return args;
    }
  }
}
