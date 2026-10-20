package dev.izzy.factorycore.core.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.runtime.StackFrame;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
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

  private static final FunctionType REGISTER =
      FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I32));
  private static final FunctionType LABEL =
      FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.I64));
  private static final FunctionType MACHINE_OP =
      FunctionType.of(
          List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I64),
          List.of(ValType.I32));
  private static final FunctionType CRAFT =
      FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I64), List.of(ValType.I32));
  private static final FunctionType STOCK =
      FunctionType.of(
          List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of(ValType.I64));
  private static final FunctionType LOG =
      FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.I32));
  private static final FunctionType STATE =
      FunctionType.of(List.of(ValType.I32, ValType.I32), List.of(ValType.I32));

  private final LongSupplier clock;
  private final Instance instance;
  private final List<RuleSpec> rules = new ArrayList<>();
  private final List<ProgramCommand> staged = new ArrayList<>();
  private final List<UUID> selected = new ArrayList<>();
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
    var imports =
        ImportValues.builder()
            .addFunction(function("register_rule", REGISTER, this::register))
            .addFunction(function("machines", LABEL, this::machines))
            .addFunction(function("keep", MACHINE_OP, a -> machineOp(a, 0)))
            .addFunction(function("move_from", MACHINE_OP, a -> machineOp(a, 1)))
            .addFunction(function("move_to", MACHINE_OP, a -> machineOp(a, 2)))
            .addFunction(function("craft", CRAFT, this::craft))
            .addFunction(function("stock", STOCK, this::stock))
            .addFunction(function("log", LOG, this::log))
            .addFunction(function("state_read", STATE, this::stateRead))
            .addFunction(function("state_write", STATE, this::stateWrite))
            .build();
    instance =
        Instance.builder(module)
            .withStart(false)
            .withImportValues(imports)
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

  private static HostFunction function(String name, FunctionType type, Body body) {
    return new HostFunction(
        WasmPreflight.MODULE, name, type, (owner, arguments) -> body.apply(arguments));
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
      throw new ProgramEvaluator.Trap(
          export
              + ": "
              + (failure.getMessage() == null
                  ? failure.getClass().getSimpleName()
                  : failure.getMessage()));
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
    selected.addAll(found);
    return new long[] {(base << 32) | found.size()};
  }

  /** Filter from (kind 0 item, 1 fluid, 2 energy; tag flag) and a registry ID. */
  private ResourceFilter filter(long kind, String id, long tag) {
    if (kind == 2) {
      return new ResourceFilter(
          ResourceKind.ENERGY,
          ResourceFilter.Mode.INCLUDE,
          List.of(new ResourceFilter.Registry(ResourceKey.ENERGY.registryId())));
    }
    if (kind != 0 && kind != 1 || tag != 0 && (tag != 1 || kind != 0))
      throw new Limit("invalid resource kind");
    if (!id.contains(":")) id = "minecraft:" + id;
    try {
      return new ResourceFilter(
          kind == 0 ? ResourceKind.ITEM : ResourceKind.FLUID,
          ResourceFilter.Mode.INCLUDE,
          List.of(tag == 1 ? new ResourceFilter.Tag(id) : new ResourceFilter.Registry(id)));
    } catch (IllegalArgumentException invalid) {
      throw new Limit("invalid registry ID " + id);
    }
  }

  private long[] machineOp(long[] a, int op) {
    call();
    requireCallback();
    long handle = a[0];
    if (handle < 0 || handle >= selected.size()) throw new Limit("unknown machine handle");
    var filter = filter(a[1], text(a[2], a[3]), a[4]);
    long amount = a[5];
    if (amount < 1 || amount > (op == 0 ? ProgramIr.MAX_AMOUNT : 4_096))
      throw new Limit("amount out of range");
    var machine = selected.get((int) handle);
    stage(
        switch (op) {
          case 0 -> new ProgramCommand.Keep(machine, filter, amount);
          case 1 -> new ProgramCommand.Move(filter, machine, null, amount);
          default -> new ProgramCommand.Move(filter, null, machine, amount);
        });
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
    var filter = filter(a[0], text(a[1], a[2]), a[3]);
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
}
