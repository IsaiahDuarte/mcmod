package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import java.util.List;
import java.util.Objects;

/**
 * Canonical item selection and shared per-backing network I/O limit, independently per direction.
 * {@code target} is the Stock goal. Q73: {@code slotTargets} is empty, or one goal per filter slot
 * ({@link #DEFAULT_GOAL} for a slot that uses {@code target}). With it, an allow list stocks each
 * slot to its own goal, counting only what that slot matches first; without it, everything the
 * filter allows counts toward the one goal.
 */
public record BufferPolicy(ResourceFilter filter, int rate, int target, List<Integer> slotTargets) {
  /** A slot's goal that means "the policy's goal". */
  public static final int DEFAULT_GOAL = -1;

  public static final BufferPolicy DEFAULT =
      new BufferPolicy(ResourceFilter.all(ResourceKind.ITEM), 64);

  /** Largest stock goal: the whole nine-slot tray of 64-item stacks. */
  public static final int MAX_TARGET = 576;

  public BufferPolicy(ResourceFilter filter, int rate) {
    this(filter, rate, 64);
  }

  public BufferPolicy(ResourceFilter filter, int rate, int target) {
    this(filter, rate, target, List.of());
  }

  public BufferPolicy {
    Objects.requireNonNull(filter);
    slotTargets = List.copyOf(slotTargets);
    // All slots on the policy's goal is the same as no slot goals: keep one canonical form.
    if (slotTargets.stream().allMatch(goal -> goal == DEFAULT_GOAL)) slotTargets = List.of();
    if (!slotTargets.isEmpty()
        && (slotTargets.size() != filter.terms().size()
            || slotTargets.stream().anyMatch(goal -> goal < DEFAULT_GOAL || goal > MAX_TARGET)))
      throw new IllegalArgumentException("Buffer slot goals need one goal of 0–576 per slot");
    if (filter.kind() != ResourceKind.ITEM
        || rate < 1
        || rate > ThroughputTier.ENDGAME.bufferRate()
        || target < 0
        || target > MAX_TARGET)
      throw new IllegalArgumentException("Buffer policy requires items, rate 1–576 and goal 0–576");
  }

  /** True when Stock mode keeps each slot to its own goal (an allow list with slot goals). */
  public boolean perSlot() {
    return !slotTargets.isEmpty() && filter.mode() == ResourceFilter.Mode.INCLUDE;
  }

  /** The Stock goal of filter slot {@code slot}. */
  public int goal(int slot) {
    if (slotTargets.isEmpty()) return target;
    int goal = slotTargets.get(slot);
    return goal == DEFAULT_GOAL ? target : goal;
  }

  /** This policy with {@code rate}, keeping everything else. */
  public BufferPolicy withRate(int rate) {
    return new BufferPolicy(filter, rate, target, slotTargets);
  }

  /** The physical buffer's installed tier bounds the rate further (ADR 0054). */
  public boolean fits(ThroughputTier tier) {
    return rate <= tier.bufferRate();
  }

  /**
   * Legacy default policy fits the existing owner charge; extensions share the same global budget.
   */
  public long bytes() {
    return equals(DEFAULT) ? 0 : 128 + filter.bytes() + 4L * slotTargets.size();
  }
}
