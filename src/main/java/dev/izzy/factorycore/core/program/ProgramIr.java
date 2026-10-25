package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Validated, immutable program representation shared by every authoring frontend. A program is a
 * set of persistent rules with stable keys; each rule runs its statements on a fixed interval.
 * Statements only describe intent; evaluation turns them into bounded {@link ProgramCommand}s.
 */
public final class ProgramIr {
  public static final int MAX_RULES = 64;
  public static final int MIN_INTERVAL = 10;
  public static final int MAX_INTERVAL = 72_000;
  public static final long MAX_AMOUNT = 1L << 20;

  private ProgramIr() {}

  public record Program(List<Rule> rules) {
    public Program {
      rules = List.copyOf(rules);
      if (rules.size() > MAX_RULES) throw new IllegalArgumentException("Too many rules");
      var keys = new HashSet<String>();
      for (var rule : rules)
        if (!keys.add(rule.key())) throw new IllegalArgumentException("Duplicate rule key");
    }
  }

  /** Persistent rule; registering the same key again replaces it instead of accumulating. */
  public record Rule(String key, int interval, List<Statement> body) {
    public Rule {
      Objects.requireNonNull(key);
      if (key.isEmpty() || key.length() > 64) throw new IllegalArgumentException("Rule key");
      if (interval < MIN_INTERVAL || interval > MAX_INTERVAL)
        throw new IllegalArgumentException("Rule interval");
      body = List.copyOf(body);
    }
  }

  /** A machine selection: a loop variable or every interface with a local label. */
  public sealed interface Target permits Variable, Label {}

  public record Variable(String name) implements Target {}

  public record Label(String label) implements Target {}

  /** A move endpoint: authorized network storage, or a machine target. */
  public sealed interface Place permits Storage, Machine, MachineAt {}

  public record Storage() implements Place {}

  public record Machine(Target target) implements Place {}

  /** Highest zero-based slot or tank index a statement may name (ADR 0062). */
  public static final int MAX_SLOT = 1_023;

  /** A face of the machine block (not of the Interface), as named after {@code side}. */
  public enum Side {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST
  }

  /**
   * D3 (ADR 0062): a machine endpoint with a statement-level face and/or slot range. {@code side}
   * null keeps the Interface's own face; {@code first} -1 keeps its whole bound window. Only
   * programs that use {@code side} or {@code slots} produce it, so v1 IR is unchanged.
   */
  public record MachineAt(Target target, Side side, int first, int last) implements Place {
    public MachineAt {
      Objects.requireNonNull(target);
      if (side == null && first < 0
          || first < -1
          || first > MAX_SLOT
          || (first < 0 ? last != -1 : last < first || last > MAX_SLOT))
        throw new IllegalArgumentException("Invalid machine endpoint");
    }
  }

  public enum Comparison {
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    EQUAL
  }

  public sealed interface Statement permits For, Keep, Move, Craft, If, Log {}

  public record For(String variable, String label, List<Statement> body) implements Statement {
    public For {
      body = List.copyOf(body);
    }
  }

  /** Request the current deficit so each target machine holds {@code amount} matching units. */
  public record Keep(Target target, ResourceFilter filter, long amount) implements Statement {}

  /** Move at most {@code limit} matching units from one place to another. */
  public record Move(ResourceFilter filter, Place from, Place to, long limit)
      implements Statement {}

  /** Request crafting of {@code amount} units of a registry item, deduplicated per rule. */
  public record Craft(String item, long amount) implements Statement {}

  public record If(ResourceFilter filter, Comparison comparison, long amount, List<Statement> body)
      implements Statement {
    public If {
      body = List.copyOf(body);
    }
  }

  public record Log(String text) implements Statement {}
}
