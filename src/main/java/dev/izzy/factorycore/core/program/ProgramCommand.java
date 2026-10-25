package dev.izzy.factorycore.core.program;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import java.util.Objects;
import java.util.UUID;

/**
 * Staged effect of one rule invocation. Commands are published only after the invocation succeeds;
 * a failed invocation publishes none. Accepted commands may still partially succeed. A null machine
 * means authorized network storage.
 */
public sealed interface ProgramCommand {
  record Keep(UUID machine, ResourceFilter filter, long amount) implements ProgramCommand {
    public Keep {
      Objects.requireNonNull(machine);
      Objects.requireNonNull(filter);
    }
  }

  /** A null {@code from} or {@code to} is storage; both set is a machine-to-machine move. */
  record Move(ResourceFilter filter, UUID from, UUID to, long limit) implements ProgramCommand {
    public Move {
      Objects.requireNonNull(filter);
      // ADR 0062: machine to machine is allowed; storage to storage is not a move.
      if (from == null && to == null)
        throw new IllegalArgumentException("A move needs a machine on at least one side");
    }
  }

  record Craft(String item, long amount) implements ProgramCommand {}

  record Log(String text) implements ProgramCommand {}
}
