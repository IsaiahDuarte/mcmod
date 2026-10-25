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

    /**
     * D4: matching units inside one machine's bound window and the observation pages that cost.
     * Hosts without machine access refuse, which traps the invocation.
     */
    default Contents contents(UUID machine, ResourceFilter filter) {
      throw new UnsupportedOperationException("This host cannot read machine contents");
    }
  }

  /** A machine read: matching {@code amount} and the {@code pages} (≥ 1) it observed. */
  public record Contents(long amount, int pages) {
    public Contents {
      if (amount < 0 || pages < 1) throw new IllegalArgumentException("Invalid contents read");
    }
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

  /** Storage is one null endpoint; a machine place resolves its target. */
  private List<UUID> endpoint(ProgramIr.Place place, Map<String, UUID> scope) {
    return switch (place) {
      case ProgramIr.Storage storage -> java.util.Collections.singletonList(null);
      case ProgramIr.Machine machine -> resolve(machine.target(), scope);
      case ProgramIr.MachineAt machine -> resolve(machine.target(), scope);
    };
  }

  private static ProgramCommand.Access access(ProgramIr.Place place) {
    return place instanceof ProgramIr.MachineAt at
        ? new ProgramCommand.Access(at.side(), at.first(), at.last())
        : null;
  }

  private static boolean compare(long value, ProgramIr.Comparison comparison, long amount) {
    return switch (comparison) {
      case LESS -> value < amount;
      case LESS_EQUAL -> value <= amount;
      case GREATER -> value > amount;
      case GREATER_EQUAL -> value >= amount;
      case EQUAL -> value == amount;
    };
  }

  /** Short-circuit evaluation; every atom is a step, and each host read is a host query. */
  private boolean holds(ProgramIr.Condition condition, Map<String, UUID> scope) {
    step();
    return switch (condition) {
      case ProgramIr.Not not -> !holds(not.inner(), scope);
      case ProgramIr.And and -> holds(and.left(), scope) && holds(and.right(), scope);
      case ProgramIr.Or or -> holds(or.left(), scope) || holds(or.right(), scope);
      case ProgramIr.StockCondition stock -> {
        hostCall();
        long value;
        try {
          value = host.stock(stock.filter());
        } catch (RuntimeException failure) {
          throw new Trap("Stock query failed: " + failure.getMessage());
        }
        yield compare(value, stock.comparison(), stock.amount());
      }
      case ProgramIr.HasCondition has -> {
        long total = 0;
        for (var machine : resolve(has.target(), scope)) {
          hostCall();
          Contents read;
          try {
            read = host.contents(machine, has.filter());
          } catch (RuntimeException failure) {
            throw new Trap("Machine contents query failed: " + failure.getMessage());
          }
          for (int page = 1; page < read.pages(); page++) hostCall();
          total = total > Long.MAX_VALUE - read.amount() ? Long.MAX_VALUE : total + read.amount();
        }
        yield compare(total, has.comparison(), has.amount());
      }
    };
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
          var sources = endpoint(move.from(), scope);
          var targets = endpoint(move.to(), scope);
          var fromAccess = access(move.from());
          var toAccess = access(move.to());
          if ((long) sources.size() * targets.size() > MAX_MOVE_PAIRS)
            throw new Trap(
                "A machine-to-machine move pairs more than " + MAX_MOVE_PAIRS + " machines");
          for (var source : sources)
            for (var target : targets)
              if (source == null || !source.equals(target))
                emit(
                    new ProgramCommand.Move(
                        move.filter(), source, target, move.limit(), fromAccess, toAccess));
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
        case ProgramIr.Branch branch ->
            run(holds(branch.condition(), scope) ? branch.then() : branch.otherwise(), scope);
        case ProgramIr.Log log -> emit(new ProgramCommand.Log(log.text()));
      }
    }
  }
}
