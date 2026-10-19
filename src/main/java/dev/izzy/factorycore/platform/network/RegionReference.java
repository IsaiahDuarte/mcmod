package dev.izzy.factorycore.platform.network;

import java.util.Objects;

/**
 * Owner-thread redirects for retained region references. Rank bounds lookup by the logarithm of
 * references merged, independently of how often discovery replaces a region. No world scan or
 * recursive traversal is needed when ownership moves.
 */
final class RegionReference<T> {
  private RegionReference<T> parent = this;
  private int rank;
  private T owner;

  RegionReference(T owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  T current() {
    return root().owner;
  }

  void replaceWith(RegionReference<T> replacement) {
    var previousRoot = root();
    var replacementRoot = replacement.root();
    if (previousRoot == replacementRoot) return;
    T next = replacementRoot.owner;
    if (previousRoot.rank > replacementRoot.rank) {
      replacementRoot.parent = previousRoot;
      replacementRoot.owner = null;
      previousRoot.owner = next;
    } else {
      previousRoot.parent = replacementRoot;
      previousRoot.owner = null;
      replacementRoot.owner = next;
      if (previousRoot.rank == replacementRoot.rank) replacementRoot.rank++;
    }
  }

  private RegionReference<T> root() {
    var current = this;
    // A rank-n root requires at least 2^n distinct merged references. Retain a hard
    // fail-closed bound even for an exceptionally long-lived coordinator.
    for (int visited = 0; visited < 32; visited++) {
      if (current.parent == current) return current;
      current = current.parent;
    }
    throw new IllegalStateException("Network ownership rank limit");
  }
}
