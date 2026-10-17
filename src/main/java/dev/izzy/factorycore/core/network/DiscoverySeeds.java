package dev.izzy.factorycore.core.network;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Owner-thread flat seed sources; each empty transition consumes exactly one charged visit. */
public final class DiscoverySeeds implements Iterator<NodeRegistry.Position> {
  public static final int MAX_SOURCES = 8192;

  private static final class Source {
    final Iterator<NodeRegistry.Position> positions;
    final NodeRegistry.Position anchor;
    Source next;

    Source(Iterator<NodeRegistry.Position> positions, NodeRegistry.Position anchor) {
      this.positions = positions;
      this.anchor = anchor;
    }
  }

  private final Thread thread = Thread.currentThread();
  private final NodeRegistry.Position anchor;
  private Source first, last;
  private int sources;
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
    admit(1);
    link(new Source(positions, anchor));
  }

  /** Transfer the remaining flat queue in constant work after cancelling its previous producer. */
  public void append(DiscoverySeeds remaining) {
    checkThread();
    requireWritable();
    Objects.requireNonNull(remaining).checkThread();
    if (remaining == this) throw new IllegalArgumentException("Recursive discovery source");
    if (remaining.borrowed) throw new IllegalStateException("Discovery source already borrowed");
    if (!remaining.hasNext()) return;
    // Preserve the charged completion visit formerly supplied by the nested iterator wrapper.
    admit(remaining.sources + 1);
    if (last == null) first = remaining.first;
    else last.next = remaining.first;
    last = remaining.last;
    sources += remaining.sources;
    link(new Source(Collections.emptyIterator(), anchor));
    remaining.first = null;
    remaining.last = null;
    remaining.sources = 0;
    remaining.borrowed = true;
  }

  private void link(Source source) {
    if (last == null) first = source;
    else last.next = source;
    last = source;
    sources++;
  }

  @Override
  public boolean hasNext() {
    checkThread();
    return first != null;
  }

  @Override
  public NodeRegistry.Position next() {
    checkThread();
    if (first == null) throw new NoSuchElementException();
    if (first.positions.hasNext()) return Objects.requireNonNull(first.positions.next());
    var completed = first;
    first = completed.next;
    completed.next = null;
    sources--;
    if (first == null) last = null;
    return completed.anchor;
  }

  private void requireWritable() {
    if (borrowed) throw new IllegalStateException("Borrowed discovery source cannot grow");
  }

  private void admit(int extra) {
    if (extra > MAX_SOURCES - sources)
      throw new IllegalStateException("Discovery source queue limit");
  }

  private void checkThread() {
    if (thread != Thread.currentThread())
      throw new IllegalStateException("Discovery sources require their owner thread");
  }
}
