package dev.izzy.factorycore.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.runtime.MStack;
import com.dylibso.chicory.runtime.StackFrame;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/** Test-only feasibility harness: no world operations, persistent SDK or production embedding. */
final class ProbeRuntime {
  private final LongSupplier clock;
  private final Instance instance;
  private final List<Long> staged = new ArrayList<>(16);
  private int remainingInstructions;
  private long started;
  private boolean failed;
  private int hostCalls;

  ProbeRuntime(byte[] artifact, LongSupplier clock) {
    this.clock = clock;
    resetBudget();
    var module = Parser.parse(ProbePreflight.validate(artifact));
    var record =
        new HostFunction(
            "factory_probe",
            "record",
            FunctionType.of(List.of(ValType.I64), List.of(ValType.I64)),
            (owner, arguments) -> {
              if (++hostCalls > 16) {
                throw new LimitException("host calls");
              }
              if (arguments[0] < 0) {
                return new long[] {-1};
              }
              staged.add(arguments[0]);
              return new long[] {arguments[0]};
            });
    instance =
        Instance.builder(module)
            .withStart(false)
            .withImportValues(ImportValues.builder().addFunction(record).build())
            .withMachineFactory(BoundedMachine::new)
            .withUnsafeExecutionListener(
                (instruction, stack) -> {
                  if (remainingInstructions-- <= 0) {
                    throw new LimitException("instructions");
                  }
                  if (clock.getAsLong() - started >= 1_000_000_000L) {
                    throw new LimitException("deadline");
                  }
                  if (stack.size() >= 4096) {
                    throw new LimitException("operand stack");
                  }
                })
            .build();
  }

  Result invoke(String export, long... arguments) {
    if (failed) {
      throw new IllegalStateException("failed instance must be replaced");
    }
    resetBudget();
    try {
      long[] returned = instance.export(export).apply(arguments);
      return new Result(returned == null ? new long[0] : returned, List.copyOf(staged));
    } catch (RuntimeException exception) {
      failed = true;
      throw exception;
    } finally {
      staged.clear();
    }
  }

  int stagedCount() {
    return staged.size();
  }

  int hostCalls() {
    return hostCalls;
  }

  int memoryPages() {
    return instance.memory().pages();
  }

  private void resetBudget() {
    remainingInstructions = 10000;
    hostCalls = 0;
    started = clock.getAsLong();
  }

  record Result(long[] values, List<Long> committed) {}

  static final class LimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LimitException(String message) {
      super(message);
    }
  }

  private static final class BoundedMachine extends InterpreterMachine {
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
      if (frames.size() >= 64) {
        throw new LimitException("call stack");
      }
      return super.call(stack, instance, frames, function, arguments, callType, popResults);
    }
  }
}
