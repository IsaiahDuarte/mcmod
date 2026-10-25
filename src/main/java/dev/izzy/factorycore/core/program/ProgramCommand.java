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

  /**
   * D3 (ADR 0062): a statement's face of the machine block ({@code side} null keeps the Interface's
   * own) and slot range ({@code first} -1 keeps its whole bound window).
   */
  record Access(ProgramIr.Side side, int first, int last) {}

  /**
   * A null {@code from} or {@code to} is storage; both set is a machine-to-machine move. A null
   * access keeps that machine's binding as it is.
   */
  record Move(
      ResourceFilter filter, UUID from, UUID to, long limit, Access fromAccess, Access toAccess)
      implements ProgramCommand {
    public Move(ResourceFilter filter, UUID from, UUID to, long limit) {
      this(filter, from, to, limit, null, null);
    }

    public Move {
      if (from == null && fromAccess != null || to == null && toAccess != null)
        throw new IllegalArgumentException("Storage has no face or slots");
      Objects.requireNonNull(filter);
      // ADR 0062: machine to machine is allowed; storage to storage is not a move.
      if (from == null && to == null)
        throw new IllegalArgumentException("A move needs a machine on at least one side");
    }
  }

  record Craft(String item, long amount) implements ProgramCommand {}

  record Log(String text) implements ProgramCommand {}

  /** D6: drive Signal-mode Interfaces with this label at {@code strength} (ADR 0062). */
  record Emit(String label, int strength) implements ProgramCommand {}
}
