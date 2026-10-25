package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded evaluation of one rule invocation into staged commands. It performs no world access
 * beyond the read-only {@link Host}; exceeding a bound or a host failure is a trap that discards
 * every staged command of that invocation.
 */
public final class ProgramEvaluator {
  public static final int MAX_STEPS = 1_024;
  public static final int MAX_COMMANDS = 256;
  public static final int MAX_MACHINES_PER_LABEL = 64;

  /** Source × target pairs one machine-to-machine move statement may produce (ADR 0062). */
  public static final int MAX_MOVE_PAIRS = 64;

  /** Host queries (label lookups and stock reads) per invocation, matching the Wasm ABI bound. */
  public static final int MAX_HOST_CALLS = 512;

  /** Read-only, already-authorized view of the program's segment. */
  public interface Host {
    /** Machine interface IDs with exactly this local label, in stable order. */
    List<UUID> machines(String label);

    /** Available matching units in the program's storage view. */
    long stock(ResourceFilter filter);
  }

  /** Thrown for a bounded-work or host failure; no commands from the invocation are published. */
  public static final class Trap extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Trap(String message) {
      super(message);
    }
  }

  private final Host host;
  private final List<ProgramCommand> staged = new ArrayList<>();
  private int steps, hostCalls;

  private ProgramEvaluator(Host host) {
    this.host = Objects.requireNonNull(host);
  }

  /** Staged commands and the evaluation steps they cost. */
  public record Outcome(List<ProgramCommand> commands, int steps) {}

  /** Evaluates {@code rule} once; returns its staged commands or throws {@link Trap}. */
  public static List<ProgramCommand> invoke(ProgramIr.Rule rule, Host host) {
    return evaluate(rule, host).commands();
  }

  /** As {@link #invoke}, also reporting evaluation steps for power accounting. */
  public static Outcome evaluate(ProgramIr.Rule rule, Host host) {
    var evaluator = new ProgramEvaluator(host);
    evaluator.run(rule.body(), new HashMap<>());
    return new Outcome(List.copyOf(evaluator.staged), evaluator.steps);
  }

  private void step() {
    if (++steps > MAX_STEPS) throw new Trap("Rule exceeded " + MAX_STEPS + " evaluation steps");
  }

  private void emit(ProgramCommand command) {
    if (staged.size() == MAX_COMMANDS)
      throw new Trap("Rule exceeded " + MAX_COMMANDS + " commands");
    staged.add(command);
  }

  private void hostCall() {
    if (++hostCalls > MAX_HOST_CALLS)
      throw new Trap("Rule exceeded " + MAX_HOST_CALLS + " host queries");
  }

  private List<UUID> machines(String label) {
    hostCall();
    List<UUID> found;
    try {
      found = host.machines(label);
    } catch (RuntimeException failure) {
      throw new Trap("Machine lookup failed: " + failure.getMessage());
    }
    if (found.size() > MAX_MACHINES_PER_LABEL)
      throw new Trap("More than " + MAX_MACHINES_PER_LABEL + " machines labeled " + label);
    return found;
  }

  private List<UUID> resolve(ProgramIr.Target target, Map<String, UUID> scope) {
    if (target instanceof ProgramIr.Variable variable) {
      var machine = scope.get(variable.name());
      if (machine == null) throw new Trap("Unbound machine variable " + variable.name());
      return List.of(machine);
    }
    return machines(((ProgramIr.Label) target).label());
  }

  private void run(List<ProgramIr.Statement> body, Map<String, UUID> scope) {
    for (var statement : body) {
      step();
      switch (statement) {
        case ProgramIr.For loop -> {
          for (var machine : machines(loop.label())) {
            step();
            var inner = new HashMap<>(scope);
            inner.put(loop.variable(), machine);
            run(loop.body(), inner);
          }
        }
        case ProgramIr.Keep keep -> {
          for (var machine : resolve(keep.target(), scope))
            emit(new ProgramCommand.Keep(machine, keep.filter(), keep.amount()));
        }
        case ProgramIr.Move move -> {
          if (move.from() instanceof ProgramIr.Machine from
              && move.to() instanceof ProgramIr.Machine to) {
            var sources = resolve(from.target(), scope);
            var targets = resolve(to.target(), scope);
            if ((long) sources.size() * targets.size() > MAX_MOVE_PAIRS)
              throw new Trap(
                  "A machine-to-machine move pairs more than " + MAX_MOVE_PAIRS + " machines");
            for (var source : sources)
              for (var target : targets)
                if (!source.equals(target))
                  emit(new ProgramCommand.Move(move.filter(), source, target, move.limit()));
          } else if (move.from() instanceof ProgramIr.Machine from)
            for (var machine : resolve(from.target(), scope))
              emit(new ProgramCommand.Move(move.filter(), machine, null, move.limit()));
          else
            for (var machine : resolve(((ProgramIr.Machine) move.to()).target(), scope))
              emit(new ProgramCommand.Move(move.filter(), null, machine, move.limit()));
        }
        case ProgramIr.Craft craft -> emit(new ProgramCommand.Craft(craft.item(), craft.amount()));
        case ProgramIr.If condition -> {
          long stock;
          hostCall();
          try {
            stock = host.stock(condition.filter());
          } catch (RuntimeException failure) {
            throw new Trap("Stock query failed: " + failure.getMessage());
          }
          boolean holds =
              switch (condition.comparison()) {
                case LESS -> stock < condition.amount();
                case LESS_EQUAL -> stock <= condition.amount();
                case GREATER -> stock > condition.amount();
                case GREATER_EQUAL -> stock >= condition.amount();
                case EQUAL -> stock == condition.amount();
              };
          if (holds) run(condition.body(), scope);
        }
        case ProgramIr.Log log -> emit(new ProgramCommand.Log(log.text()));
      }
    }
  }
}
