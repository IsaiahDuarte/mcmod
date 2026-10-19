package dev.izzy.factorycore.core.program;

import java.util.ArrayDeque;
import java.util.Deque;

/** Per-open-editor undo history, bounded to 64 snapshots and the compiler's source limit. */
public final class DraftHistory {
  public static final int MAX_ENTRIES = 64;
  private final Deque<String> undo = new ArrayDeque<>();
  private final Deque<String> redo = new ArrayDeque<>();
  private String current;

  public DraftHistory(String initial) {
    validate(initial);
    current = initial;
  }

  private static void validate(String text) {
    if (text.length() > 16_384) throw new IllegalArgumentException("Draft too long");
  }

  /** A distinct edit creates one undo step and discards the abandoned redo branch. */
  public void edit(String text) {
    validate(text);
    if (current.equals(text)) return;
    if (undo.size() == MAX_ENTRIES) undo.removeFirst();
    undo.addLast(current);
    current = text;
    redo.clear();
  }

  /** Opening baseline; loading a server snapshot is not an undoable player edit. */
  public void load(String text) {
    validate(text);
    current = text;
    undo.clear();
    redo.clear();
  }

  public String undo() {
    if (!undo.isEmpty()) {
      redo.addLast(current);
      current = undo.removeLast();
    }
    return current;
  }

  public String redo() {
    if (!redo.isEmpty()) {
      undo.addLast(current);
      current = redo.removeLast();
    }
    return current;
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }
}
