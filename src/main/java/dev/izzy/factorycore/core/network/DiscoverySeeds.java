package dev.izzy.factorycore.core.network;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Owner-thread seed sources; empty transitions consume visits without synchronous traversal. */
public final class DiscoverySeeds implements Iterator<NodeRegistry.Position> {
  public static final int MAX_SOURCES = 8192;
  public static final int MAX_DEPTH = 32;
  private final Thread thread = Thread.currentThread();
  private final NodeRegistry.Position anchor;
  private final ArrayDeque<Iterator<NodeRegistry.Position>> queue = new ArrayDeque<>();
  private int depth = 1;
  private boolean borrowed;

  public DiscoverySeeds(NodeRegistry.Position anchor) {
    this.anchor = Objects.requireNonNull(anchor);
  }

  public void appendPositions(Iterator<NodeRegistry.Position> positions) {
    checkThread();
    requireWritable();
    Objects.requireNonNull(positions);
    if (positions instanceof DiscoverySeeds nested) {
      append(nested);
      return;
    }
    admit();
    queue.addLast(positions);
  }

  /** Borrow the remaining source iterator after its old producer is cancelled. */
  public void append(DiscoverySeeds remaining) {
    checkThread();
    requireWritable();
    Objects.requireNonNull(remaining).checkThread();
    if (remaining == this) throw new IllegalArgumentException("Recursive discovery source");
    if (!remaining.hasNext()) return;
    if (remaining.borrowed) throw new IllegalStateException("Discovery source already borrowed");
    if (remaining.depth >= MAX_DEPTH)
      throw new IllegalStateException("Discovery source depth limit");
    admit();
    queue.addLast(remaining);
    remaining.borrowed = true;
    depth = Math.max(depth, remaining.depth + 1);
  }

  @Override
  public boolean hasNext() {
    checkThread();
    return !queue.isEmpty();
  }

  @Override
  public NodeRegistry.Position next() {
    checkThread();
    if (queue.isEmpty()) throw new NoSuchElementException();
    var first = queue.getFirst();
    if (first.hasNext()) return Objects.requireNonNull(first.next());
    queue.removeFirst();
    return anchor;
  }

  private void requireWritable() {
    if (borrowed) throw new IllegalStateException("Borrowed discovery source cannot grow");
  }

  private void admit() {
    if (queue.size() >= MAX_SOURCES)
      throw new IllegalStateException("Discovery source queue limit");
  }

  private void checkThread() {
    if (thread != Thread.currentThread())
      throw new IllegalStateException("Discovery sources require their owner thread");
  }
}
