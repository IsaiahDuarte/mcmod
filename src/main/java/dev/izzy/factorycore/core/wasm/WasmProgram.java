package dev.izzy.factorycore.core.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.runtime.StackFrame;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import dev.izzy.factorycore.core.Failures;
import dev.izzy.factorycore.core.program.ProgramCommand;
import dev.izzy.factorycore.core.program.ProgramEvaluator;
import dev.izzy.factorycore.core.program.ProgramIr;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * One bounded {@code factory_v1} guest instance (ADR 0037). {@code factory_init} registers rules
 * once; each {@code factory_rule(index)} callback stages the same {@link ProgramCommand}s as the
 * friendly language through host calls. Any limit, host failure or guest trap discards the
 * callback's staged commands and permanently fails the instance; a deployment then needs a fresh
 * instance. No WASI, threads, tables growth or suspended stacks exist.
 */
public final class WasmProgram {
  public static final int INIT_INSTRUCTIONS = 100_000;
  public static final int RULE_INSTRUCTIONS = 65_536;
  public static final int MAX_HOST_CALLS = 512;
  public static final int MAX_FRAMES = 64;
  public static final int MAX_OPERANDS = 4_096;
  public static final int MAX_TEXT = 128;
  public static final long DEADLINE_NANOS = 50_000_000;

  /** Persistent guest state quota in bytes (one blob per deployment). */
  public static final int MAX_STATE = 4_096;

  /** A registered persistent rule; registering the same key twice fails initialization. */
  public record RuleSpec(String key, int interval) {}

  /**
   * Staged commands and guest instructions used, for publication and FE accounting; {@code
   * stateChanged} reports a committed {@code state_write} (read it with {@link #state()}).
   */
  public record Outcome(List<ProgramCommand> commands, int instructions, boolean stateChanged) {}

  private final LongSupplier clock;
  private final Instance instance;
  private final List<RuleSpec> rules = new ArrayList<>();
  private final List<ProgramCommand> staged = new ArrayList<>();

  /** A machine handle: the machine and the face/slots {@code with_access} gave it (or null). */
  private record Selected(UUID machine, ProgramCommand.Access access) {}

  private final List<Selected> selected = new ArrayList<>();
  private ProgramEvaluator.Host host;
  private boolean initializing, failed, wrote;
  private byte[] committed, pending;
  private int remaining, budget, hostCalls;
  private long started;

  private static final class Limit extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Limit(String message) {
      super(message, null, false, false);
    }
  }

  private final class BoundedMachine extends InterpreterMachine {
    BoundedMachine(Instance instance) {
      super(instance);
    }

    @Override
    protected long[] call(
        MStack stack,
        Instance instance,
        Deque<StackFrame> frames,
        int function,
        long[] arguments,
        FunctionType callType,
        boolean popResults) {
      if (frames.size() >= MAX_FRAMES) throw new Limit("call depth over " + MAX_FRAMES);
      return super.call(stack, instance, frames, function, arguments, callType, popResults);
    }
  }

  /** Validates, instantiates and initializes {@code artifact}; throws on any failure. */
  public WasmProgram(byte[] artifact, LongSupplier clock) {
    this(artifact, clock, null);
  }

  /**
   * As {@link #WasmProgram(byte[], LongSupplier)}, restoring previously committed persistent {@code
   * state} (or null) into the fresh instance before {@code factory_init} runs.
   */
  public WasmProgram(byte[] artifact, LongSupplier clock, byte[] state) {
    this.clock = clock;
    if (state != null && state.length > MAX_STATE)
      throw new IllegalArgumentException("Guest state over " + MAX_STATE + " bytes");
    committed = state == null ? null : state.clone();
    var module = Parser.parse(WasmPreflight.validate(artifact));
    var imports = ImportValues.builder();
    for (var hostImport : HostImport.values())
      imports.addFunction(
          function(
              hostImport,
              switch (hostImport) {
                case REGISTER_RULE -> this::register;
                case MACHINES -> this::machines;
                case KEEP_STOCKED -> a -> machineOp(a, 0);
                case MOVE_TO_STORAGE -> a -> machineOp(a, 1);
                case MOVE_FROM_STORAGE -> a -> machineOp(a, 2);
                case CRAFT -> this::craft;
                case STOCK -> this::stock;
                case LOG -> this::log;
                case STATE_READ -> this::stateRead;
                case STATE_WRITE -> this::stateWrite;
                case WITH_ACCESS -> this::withAccess;
                case MOVE2 -> this::move2;
                case HAS -> this::has;
                case REDSTONE_IN -> this::redstoneIn;
                case EMIT -> this::emitSignal;
                case PANIC -> this::panic;
              }));
    instance =
        Instance.builder(module)
            .withStart(false)
            .withImportValues(imports.build())
            .withMachineFactory(BoundedMachine::new)
            .withUnsafeExecutionListener(
                (instruction, stack) -> {
                  if (remaining-- <= 0) throw new Limit("instruction budget exhausted");
                  if (stack.size() >= MAX_OPERANDS)
                    throw new Limit("operand stack over " + MAX_OPERANDS);
                  if (clock.getAsLong() - started >= DEADLINE_NANOS) throw new Limit("time limit");
                })
            .build();
    initializing = true;
    try {
      run("factory_init", INIT_INSTRUCTIONS);
    } finally {
      initializing = false;
      staged.clear();
    }
    if (rules.isEmpty()) throw new IllegalArgumentException("Guest registered no rules");
  }

  private interface Body {
    long[] apply(long[] arguments);
  }

  private static HostFunction function(HostImport hostImport, Body body) {
    return new HostFunction(
        WasmPreflight.MODULE,
        hostImport.importName(),
        hostImport.type(),
        (owner, arguments) -> body.apply(arguments));
  }

  public List<RuleSpec> rules() {
    return List.copyOf(rules);
  }

  public boolean failed() {
    return failed;
  }

  /** The last committed persistent state, or null when the guest never wrote any. */
  public byte[] state() {
    return committed == null ? null : committed.clone();
  }

  public int memoryPages() {
    return instance.memory().pages();
  }

  private void run(String export, int instructions, long... arguments) {
    if (failed) throw new IllegalStateException("Failed guest instance must be replaced");
    remaining = instructions;
    budget = instructions;
    hostCalls = 0;
    started = clock.getAsLong();
    try {
      var function = instance.export(export);
      if (function == null) throw new IllegalArgumentException("Guest does not export " + export);
      function.apply(arguments);
    } catch (RuntimeException failure) {
      failed = true;
      staged.clear();
      throw new ProgramEvaluator.Trap(export + ": " + (Failures.message(failure)));
    }
  }

  /**
   * Runs one rule callback against {@code host}; throws {@link ProgramEvaluator.Trap} on failure.
   */
  public Outcome invoke(int rule, ProgramEvaluator.Host host) {
    if (rule < 0 || rule >= rules.size()) throw new IllegalArgumentException("Unknown rule");
    this.host = host;
    selected.clear();
    wrote = false;
    pending = null;
    try {
      run("factory_rule", RULE_INSTRUCTIONS, rule);
      // A write commits only with a successful callback; traps leave the previous state.
      if (wrote) committed = pending;
      return new Outcome(List.copyOf(staged), budget - Math.max(0, remaining), wrote);
    } finally {
      staged.clear();
      selected.clear();
      wrote = false;
      pending = null;
      this.host = null;
    }
  }

  private void call() {
    if (++hostCalls > MAX_HOST_CALLS)
      throw new Limit("more than " + MAX_HOST_CALLS + " host calls");
  }

  private String text(long pointer, long length) {
    if (length < 0 || length > MAX_TEXT) throw new Limit("text longer than " + MAX_TEXT + " bytes");
    var bytes = instance.memory().readBytes((int) pointer, (int) length);
    var decoder = StandardCharsets.UTF_8.newDecoder();
    try {
      return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalid) {
      throw new Limit("text is not UTF-8");
    }
  }

  private void stage(ProgramCommand command) {
    if (staged.size() == ProgramEvaluator.MAX_COMMANDS)
      throw new Limit("more than " + ProgramEvaluator.MAX_COMMANDS + " commands");
    staged.add(command);
  }

  private void requireCallback() {
    if (initializing || host == null) throw new Limit("host call outside a rule callback");
  }

  private long[] register(long[] a) {
    call();
    if (!initializing) throw new Limit("register_rule outside factory_init");
    var key = text(a[0], a[1]);
    long interval = a[2];
    if (key.isEmpty() || key.length() > 64 || rules.stream().anyMatch(r -> r.key().equals(key)))
      throw new Limit("invalid or duplicate rule key");
    if (interval < ProgramIr.MIN_INTERVAL || interval > ProgramIr.MAX_INTERVAL)
      throw new Limit("rule interval outside 10..72000 ticks");
    if (rules.size() == ProgramIr.MAX_RULES) throw new Limit("more than 64 rules");
    rules.add(new RuleSpec(key, (int) interval));
    return new long[] {rules.size() - 1};
  }

  /** Appends the label's machines to this callback's table; returns (base << 32) | count. */
  private long[] machines(long[] a) {
    call();
    requireCallback();
    var label = text(a[0], a[1]);
    var found = host.machines(label);
    if (found.size() > ProgramEvaluator.MAX_MACHINES_PER_LABEL)
      throw new Limit("more than 64 machines labeled " + label);
    if (selected.size() + found.size() > 1_024) throw new Limit("too many machine handles");
    long base = selected.size();
    for (var machine : found) selected.add(new Selected(machine, null));
    return new long[] {(base << 32) | found.size()};
  }

  /**
   * Filter from a resource kind (0 item, 1 fluid, 2 energy, 3 item tag) and a registry ID. Since D7
   * an item or fluid ID may hold one {@code *} (a glob) and {@code " except "}-separated IDs to
   * remove (ADR 0062); such text used to trap, so no older guest changes meaning.
   */
  private ResourceFilter filter(long kind, String id) {
    if (kind != 2 && kind != 3 && (id.contains("*") || id.contains(" except "))) {
      var parts = id.split(" except ", -1);
      if (parts.length > 8) throw new Limit("a filter has at most 8 terms");
      var terms = new ArrayList<ResourceFilter.Term>();
      for (var part : parts) terms.add(term(part.strip()));
      var resourceKind = kind == 1 ? ResourceKind.FLUID : ResourceKind.ITEM;
      try {
        return new ResourceFilter(
            resourceKind,
            ResourceFilter.Mode.INCLUDE,
            List.of(
                terms.size() == 1
                    ? terms.getFirst()
                    : new ResourceFilter.Except(terms.getFirst(), terms.subList(1, terms.size()))));
      } catch (IllegalArgumentException invalid) {
        throw new Limit("invalid filter " + id);
      }
    }
    return plainFilter(kind, id);
  }

  private ResourceFilter.Term term(String id) {
    try {
      if (id.contains("*")) return new ResourceFilter.Glob(id);
      return new ResourceFilter.Registry(id.contains(":") ? id : "minecraft:" + id);
    } catch (IllegalArgumentException invalid) {
      throw new Limit("invalid registry ID or pattern " + id);
    }
  }

  private ResourceFilter plainFilter(long kind, String id) {
    if (kind == 2) {
      return new ResourceFilter(
          ResourceKind.ENERGY,
          ResourceFilter.Mode.INCLUDE,
          List.of(new ResourceFilter.Registry(ResourceKey.ENERGY.registryId())));
    }
    if (kind < 0 || kind > 3) throw new Limit("invalid resource kind");
    if (!id.contains(":")) id = "minecraft:" + id;
    try {
      return new ResourceFilter(
          kind == 1 ? ResourceKind.FLUID : ResourceKind.ITEM,
          ResourceFilter.Mode.INCLUDE,
          List.of(kind == 3 ? new ResourceFilter.Tag(id) : new ResourceFilter.Registry(id)));
    } catch (IllegalArgumentException invalid) {
      throw new Limit("invalid registry ID " + id);
    }
  }

  private long[] machineOp(long[] a, int op) {
    call();
    requireCallback();
    long handle = a[0];
    if (handle < 0 || handle >= selected.size()) throw new Limit("unknown machine handle");
    var filter = filter(a[1], text(a[2], a[3]));
    long amount = a[4];
    if (amount < 1 || amount > (op == 0 ? ProgramIr.MAX_AMOUNT : 4_096))
      throw new Limit("amount out of range");
    var chosen = selected.get((int) handle);
    var machine = chosen.machine();
    if (op == 0 && chosen.access() != null) throw new Limit("keep_stocked takes no side or slots");
    stage(
        switch (op) {
          case 0 -> new ProgramCommand.Keep(machine, filter, amount);
          case 1 -> new ProgramCommand.Move(filter, machine, null, amount, chosen.access(), null);
          default -> new ProgramCommand.Move(filter, null, machine, amount, null, chosen.access());
        });
    return new long[] {0};
  }

  private Selected handle(long value) {
    if (value < 0 || value >= selected.size()) throw new Limit("unknown machine handle");
    return selected.get((int) value);
  }

  /**
   * {@code with_access(machine, side, first, last)}: appends a handle with that face/slots; -1
   * keeps the side or slots the given handle already had.
   */
  private long[] withAccess(long[] a) {
    call();
    requireCallback();
    var chosen = handle(a[0]);
    // i32 arguments: cast so -1 is -1 however the runtime widens them.
    long side = (int) a[1], first = (int) a[2], last = (int) a[3];
    if (side < -1 || side > 5) throw new Limit("side is -1 (keep) or 0..5 (down..east)");
    if (side == -1 && first == -1) throw new Limit("with_access needs a side or slots");
    if (first == -1 ? last != -1 : first < 0 || last < first || last > ProgramIr.MAX_SLOT)
      throw new Limit("slots are 0..1023, low to high, or -1 to keep");
    if (selected.size() == 1_024) throw new Limit("too many machine handles");
    // Chained calls merge: -1 keeps what an earlier with_access on this handle set.
    var before = chosen.access();
    var keptSide = before == null ? null : before.side();
    int keptFirst = before == null ? -1 : before.first(),
        keptLast = before == null ? -1 : before.last();
    selected.add(
        new Selected(
            chosen.machine(),
            new ProgramCommand.Access(
                side < 0 ? keptSide : ProgramIr.Side.values()[(int) side],
                first < 0 ? keptFirst : (int) first,
                first < 0 ? keptLast : (int) last)));
    return new long[] {selected.size() - 1};
  }

  /** {@code move2(from, to, kind, id, len, limit)}: -1 is storage on either side, not both. */
  private long[] move2(long[] a) {
    call();
    requireCallback();
    int fromHandle = (int) a[0], toHandle = (int) a[1];
    if (fromHandle == -1 && toHandle == -1)
      throw new Limit("a move needs a machine on at least one side");
    var from = fromHandle == -1 ? null : handle(fromHandle);
    var to = toHandle == -1 ? null : handle(toHandle);
    if (from != null && to != null && from.machine().equals(to.machine()))
      throw new Limit("a machine cannot move to itself");
    var filter = filter(a[2], text(a[3], a[4]));
    if (a[5] < 1 || a[5] > 4_096) throw new Limit("amount out of range");
    stage(
        new ProgramCommand.Move(
            filter,
            from == null ? null : from.machine(),
            to == null ? null : to.machine(),
            a[5],
            from == null ? null : from.access(),
            to == null ? null : to.access()));
    return new long[] {0};
  }

  /** {@code has(machine, kind, id, len)}: one call, plus one per extra observation page. */
  private long[] has(long[] a) {
    call();
    requireCallback();
    var chosen = handle(a[0]);
    var filter = filter(a[1], text(a[2], a[3]));
    ProgramEvaluator.Contents read;
    try {
      read = host.contents(chosen.machine(), filter);
    } catch (RuntimeException failure) {
      throw new Limit("has query failed: " + failure.getMessage());
    }
    for (int page = 1; page < read.pages(); page++) call();
    return new long[] {read.amount()};
  }

  private long[] redstoneIn(long[] a) {
    call();
    requireCallback();
    var chosen = handle(a[0]);
    try {
      return new long[] {host.powered(chosen.machine()) ? 1 : 0};
    } catch (RuntimeException failure) {
      throw new Limit("redstone query failed: " + failure.getMessage());
    }
  }

  private long[] emitSignal(long[] a) {
    call();
    requireCallback();
    var label = text(a[0], a[1]);
    if (label.isEmpty() || label.length() > 64) throw new Limit("invalid label");
    if (a[2] < 0 || a[2] > 15) throw new Limit("redstone strength is 0..15");
    stage(new ProgramCommand.Emit(label, (int) a[2]));
    return new long[] {0};
  }

  private long[] craft(long[] a) {
    call();
    requireCallback();
    var id = text(a[0], a[1]);
    if (!id.contains(":")) id = "minecraft:" + id;
    try {
      new ResourceKey(ResourceKind.ITEM, id, new byte[0]);
    } catch (IllegalArgumentException invalid) {
      throw new Limit("invalid item ID " + id);
    }
    if (a[2] < 1 || a[2] > ProgramIr.MAX_AMOUNT) throw new Limit("amount out of range");
    stage(new ProgramCommand.Craft(id, a[2]));
    return new long[] {0};
  }

  private long[] stock(long[] a) {
    call();
    requireCallback();
    var filter = filter(a[0], text(a[1], a[2]));
    try {
      return new long[] {host.stock(filter)};
    } catch (RuntimeException failure) {
      throw new Limit("stock query failed: " + failure.getMessage());
    }
  }

  private long[] log(long[] a) {
    call();
    requireCallback();
    stage(new ProgramCommand.Log(text(a[0], a[1])));
    return new long[] {0};
  }

  /** {@code state_read(ptr, cap)}: copies up to cap bytes; returns the full length or -1. */
  private long[] stateRead(long[] a) {
    call();
    var current = wrote ? pending : committed;
    if (a[1] < 0) throw new Limit("negative state buffer");
    if (current == null) return new long[] {-1};
    int copied = (int) Math.min(a[1], current.length);
    instance.memory().write((int) a[0], Arrays.copyOf(current, copied));
    return new long[] {current.length};
  }

  /** {@code state_write(ptr, len)}: stages the whole state blob (callbacks only, ≤ quota). */
  private long[] stateWrite(long[] a) {
    call();
    requireCallback();
    if (a[1] < 0 || a[1] > MAX_STATE) throw new Limit("state over " + MAX_STATE + " bytes");
    pending = instance.memory().readBytes((int) a[0], (int) a[1]);
    wrote = true;
    return new long[] {0};
  }

  /**
   * {@code panic(ptr, len)}: traps with the guest's own failure message, allowed in any phase. A
   * message over {@link #MAX_TEXT} bytes is cut to that prefix and decoded leniently, because the
   * call is already a failure report.
   */
  private long[] panic(long[] a) {
    call();
    if (a[1] < 0) throw new Limit("guest panicked");
    var bytes = instance.memory().readBytes((int) a[0], (int) Math.min(a[1], MAX_TEXT));
    throw new Limit("guest panicked: " + new String(bytes, StandardCharsets.UTF_8));
  }
}
