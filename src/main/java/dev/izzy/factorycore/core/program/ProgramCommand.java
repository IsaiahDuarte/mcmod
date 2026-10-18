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

  record Move(ResourceFilter filter, UUID from, UUID to, long limit) implements ProgramCommand {
    public Move {
      Objects.requireNonNull(filter);
      if ((from == null) == (to == null))
        throw new IllegalArgumentException("Exactly one side is storage");
    }
  }

  record Craft(String item, long amount) implements ProgramCommand {}

  record Log(String text) implements ProgramCommand {}
}
