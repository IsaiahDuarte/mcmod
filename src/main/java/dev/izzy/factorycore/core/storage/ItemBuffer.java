package dev.izzy.factorycore.core.storage;

import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ResourcePort;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Nine real owned item slots; a transfer port and slot view share the same quantities. */
public final class ItemBuffer implements ResourcePort {
  public static final int SLOTS = 9;
  public static final int MAX_STACK = 64;

  public record Slot(ResourceKey key, int count, int limit) {
    public Slot {
      if (key == null
          ? count != 0 || limit != 0
          : key.kind() != ResourceKind.ITEM
              || count < 1
              || limit < 1
              || limit > MAX_STACK
              || count > limit) throw new IllegalArgumentException("Invalid buffered item slot");
    }

    public static Slot empty() {
      return new Slot(null, 0, 0);
    }
  }

  public record State(UUID backing, List<Slot> slots) {
    public State {
      Objects.requireNonNull(backing);
      slots = List.copyOf(slots);
      if (slots.size() != SLOTS) throw new IllegalArgumentException("Buffer requires nine slots");
    }
  }

  private final Thread thread = Thread.currentThread();
  private final UUID backing;
  private final Slot[] slots;
  private final ToIntFunction<ResourceKey> stackLimit;
  private final Runnable changed;
  private final Predicate<State> admission;
  private boolean notifying;
  private boolean resolving;

  /** Resolver is bounded/nonthrowing for valid item keys and supplies their current stack limit. */
  public ItemBuffer(State restored, ToIntFunction<ResourceKey> stackLimit, Runnable changed) {
    this(restored, stackLimit, changed, state -> true);
  }

  /** Admission checks proposed owned state before mutation, for canonical metadata quotas. */
  public ItemBuffer(
      State restored,
      ToIntFunction<ResourceKey> stackLimit,
      Runnable changed,
      Predicate<State> admission) {
    backing = restored.backing();
    slots = restored.slots().toArray(Slot[]::new);
    this.stackLimit = Objects.requireNonNull(stackLimit);
    this.changed = Objects.requireNonNull(changed);
    this.admission = Objects.requireNonNull(admission);
  }

  public static State empty(UUID backing) {
    return new State(backing, java.util.Collections.nCopies(SLOTS, Slot.empty()));
  }

  @Override
  public UUID backingId() {
    checkThread();
    return backing;
  }

  public Slot slot(int index) {
    checkThread();
    checkSlot(index);
    return slots[index];
  }

  public State snapshot() {
    checkThread();
    return new State(backing, Arrays.asList(slots.clone()));
  }

  public long total(ResourceKey key) {
    checkThread();
    Objects.requireNonNull(key);
    long total = 0;
    for (var slot : slots) if (key.equals(slot.key())) total += slot.count();
    return total;
  }

  /** Physical inventory admission: returns accepted units, leaving the caller's remainder owned. */
  public long insertSlot(int index, ResourceKey key, long offered, boolean simulate) {
    checkMutation();
    checkSlot(index);
    OperationResult.requireQuantity(offered);
    int limit = limit(key);
    long accepted = Math.min(offered, space(slots[index], key, limit));
    if (accepted > 0) {
      var proposed = slots.clone();
      var old = proposed[index];
      proposed[index] =
          new Slot(key, old.count() + (int) accepted, old.key() == null ? limit : old.limit());
      if (!admitted(proposed)) return 0;
      if (!simulate) {
        add(index, key, (int) accepted, limit);
        notifyChanged();
      }
    }
    return accepted;
  }

  /** A simulation returns an immutable item description without changing owned slots. */
  public Slot extractSlot(int index, long requested, boolean simulate) {
    checkMutation();
    checkSlot(index);
    OperationResult.requireQuantity(requested);
    var slot = slots[index];
    int amount = (int) Math.min(requested, slot.count());
    if (amount == 0) return Slot.empty();
    var extracted = new Slot(slot.key(), amount, slot.limit());
    if (!simulate) {
      remove(index, amount);
      notifyChanged();
    }
    return extracted;
  }

  @Override
  public long simulateInsert(ResourceKey key, long offered) {
    return admitInsert(key, offered).amount();
  }

  public OperationResult admitInsert(ResourceKey key, long offered) {
    checkMutation();
    OperationResult.requireQuantity(offered);
    Objects.requireNonNull(key);
    if (key.kind() != ResourceKind.ITEM)
      return new OperationResult(0, OperationResult.Reason.WRONG_RESOURCE);
    int limit = limit(key);
    long capacity = 0;
    for (var slot : slots) capacity += space(slot, key, limit);
    long accepted = Math.min(offered, capacity);
    if (accepted > 0 && !admitted(proposedInsert(key, accepted, limit)))
      return new OperationResult(0, OperationResult.Reason.TECHNICAL_LIMIT);
    return new OperationResult(
        accepted, accepted == offered ? OperationResult.Reason.OK : OperationResult.Reason.FULL);
  }

  @Override
  public long insert(ResourceKey key, long offered) {
    checkMutation();
    OperationResult.requireQuantity(offered);
    int limit = limit(key);
    var proposed = proposedInsert(key, offered, limit);
    long accepted = 0;
    for (int index = 0; index < SLOTS; index++)
      accepted += proposed[index].count() - slots[index].count();
    if (accepted == 0 || !admitted(proposed)) return 0;
    System.arraycopy(proposed, 0, slots, 0, SLOTS);
    notifyChanged();
    return accepted;
  }

  private Slot[] proposedInsert(ResourceKey key, long offered, int limit) {
    var proposed = slots.clone();
    long left = offered;
    // Stack with matching identities before occupying empty physical slots.
    for (int pass = 0; pass < 2 && left > 0; pass++)
      for (int index = 0; index < SLOTS && left > 0; index++) {
        var slot = proposed[index];
        if ((slot.key() == null) != (pass == 1)) continue;
        int accepted = (int) Math.min(left, space(slot, key, limit));
        if (accepted > 0) {
          proposed[index] =
              new Slot(key, slot.count() + accepted, slot.key() == null ? limit : slot.limit());
          left -= accepted;
        }
      }
    return proposed;
  }

  private boolean admitted(Slot[] proposed) {
    resolving = true;
    try {
      return admission.test(new State(backing, Arrays.asList(proposed)));
    } finally {
      resolving = false;
    }
  }

  @Override
  public long extract(ResourceKey key, long requested) {
    checkMutation();
    Objects.requireNonNull(key);
    OperationResult.requireQuantity(requested);
    long left = requested;
    for (int index = 0; index < SLOTS && left > 0; index++) {
      var slot = slots[index];
      if (!key.equals(slot.key())) continue;
      int amount = (int) Math.min(left, slot.count());
      remove(index, amount);
      left -= amount;
    }
    long extracted = requested - left;
    if (extracted > 0) notifyChanged();
    return extracted;
  }

  private int limit(ResourceKey key) {
    Objects.requireNonNull(key);
    if (key.kind() != ResourceKind.ITEM) return 0;
    int limit;
    resolving = true;
    try {
      limit = stackLimit.applyAsInt(key);
    } finally {
      resolving = false;
    }
    if (limit < 1) throw new IllegalArgumentException("Invalid current item stack limit");
    return Math.min(MAX_STACK, limit);
  }

  private static int space(Slot slot, ResourceKey key, int currentLimit) {
    if (currentLimit == 0) return 0;
    if (slot.key() == null) return currentLimit;
    return key.equals(slot.key())
        ? Math.max(0, Math.min(currentLimit, slot.limit()) - slot.count())
        : 0;
  }

  private void add(int index, ResourceKey key, int count, int limit) {
    var old = slots[index];
    slots[index] = new Slot(key, old.count() + count, old.key() == null ? limit : old.limit());
  }

  private void remove(int index, int count) {
    var old = slots[index];
    slots[index] =
        old.count() == count ? Slot.empty() : new Slot(old.key(), old.count() - count, old.limit());
  }

  private void notifyChanged() {
    notifying = true;
    try {
      changed.run();
    } finally {
      notifying = false;
    }
  }

  private static void checkSlot(int index) {
    if (index < 0 || index >= SLOTS) throw new IllegalArgumentException("Buffer slot must be 0..8");
  }

  private void checkMutation() {
    checkThread();
    if (notifying || resolving) throw new IllegalStateException("Buffer callback mutation reentry");
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Buffer requires its owning thread");
  }
}
