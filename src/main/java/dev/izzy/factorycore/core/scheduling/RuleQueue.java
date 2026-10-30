package dev.izzy.factorycore.core.scheduling;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One program controller's pending commands, by rule (M145). Each rule keeps only its latest batch:
 * a rule that runs again replaces what it still had queued, so missed work coalesces instead of
 * piling up. Commands are taken round-robin, one per rule with something queued in turn, each rule
 * in its own order, so a rule whose batch never drains cannot keep the next rule waiting. The total
 * held is bounded; a batch that does not fit is cut, and {@link #replace} says so.
 *
 * <p>Before this, a rule that ran again moved its batch to the end of a single queue. Rules run in
 * source order, so the first rule always re-queued first and a later rule fell behind it on every
 * interval: when commands drained slower than the interval (a network rebuild or a busy server
 * slowing storage replies), the later rule never ran at all.
 *
 * @param <C> the command type
 */
public final class RuleQueue<C> {
  private final int capacity;
  private final Map<String, ArrayDeque<C>> batches = new LinkedHashMap<>();
  private int size;

  public RuleQueue(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Rule queue capacity");
    this.capacity = capacity;
  }

  /**
   * Replaces {@code rule}'s queued commands with {@code commands}. A rule that still had commands
   * keeps its turn; one that had none takes its turn after every rule already waiting. Returns how
   * many commands were dropped because the queue was full.
   */
  public int replace(String rule, List<C> commands) {
    var old = batches.get(rule);
    if (old != null) size -= old.size();
    var batch = new ArrayDeque<C>();
    int dropped = 0;
    for (var command : commands)
      if (size + batch.size() < capacity) batch.add(command);
      else dropped++;
    if (batch.isEmpty()) batches.remove(rule);
    else if (old != null) batches.replace(rule, batch);
    else batches.put(rule, batch);
    size += batch.size();
    return dropped;
  }

  /** The next command, from the rule whose turn it is (which then waits behind the others). */
  public Entry<C> poll() {
    var iterator = batches.entrySet().iterator();
    if (!iterator.hasNext()) return null;
    var head = iterator.next();
    String rule = head.getKey();
    var batch = head.getValue();
    var command = batch.poll();
    size--;
    iterator.remove();
    if (!batch.isEmpty()) batches.put(rule, batch);
    return new Entry<>(rule, command);
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public int size() {
    return size;
  }

  public void clear() {
    batches.clear();
    size = 0;
  }

  /** A command and the rule that queued it. */
  public record Entry<C>(String rule, C command) {}
}
