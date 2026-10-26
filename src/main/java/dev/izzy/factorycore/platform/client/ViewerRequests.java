package dev.izzy.factorycore.platform.client;

import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * A5: hands a recipe viewer's craft request to the terminal screen it was made from. The request
 * belongs to that menu object, held weakly, not to its container ID: IDs are reused, so a request
 * left pending when its terminal closed must never reach a later terminal. Client thread only.
 */
final class ViewerRequests {
  private WeakReference<Object> menu = new WeakReference<>(null);
  private String item;

  /** Records {@code item} for {@code owner}, replacing any earlier pending request. */
  void offer(Object owner, String item) {
    menu = new WeakReference<>(Objects.requireNonNull(owner));
    this.item = Objects.requireNonNull(item);
  }

  /** The pending request for {@code owner}, removed as it is returned, or null. */
  String take(Object owner) {
    if (item == null || menu.get() != owner) return null;
    var taken = item;
    clear();
    return taken;
  }

  /** Drops a request pending for {@code owner}, when its terminal really closes. */
  void drop(Object owner) {
    if (menu.get() == owner) clear();
  }

  private void clear() {
    menu = new WeakReference<>(null);
    item = null;
  }
}
