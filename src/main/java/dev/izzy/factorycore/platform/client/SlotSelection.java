package dev.izzy.factorycore.platform.client;

/**
 * Slot-window picking rules shared by the Machine and Storage settings screens (P5). A window is
 * {@code first} plus {@code count} contiguous slots or tanks. Pure so it is unit-tested.
 */
final class SlotSelection {
  /** Containers with more slots than this keep the First slot / Slot count number fields. */
  static final int MAX_PICKABLE = 64;

  /** Cells per picker row; larger containers wrap onto a second row. */
  static final int COLUMNS = 32;

  private SlotSelection() {}

  static boolean pickable(int slots) {
    return slots >= 1 && slots <= MAX_PICKABLE;
  }

  /**
   * The window after clicking {@code clicked}: a plain click selects that one slot; with {@code
   * extend} (Shift) the window spans from the current first slot to the clicked one, either way.
   */
  static int[] click(int first, int clicked, boolean extend) {
    if (!extend) return new int[] {clicked, 1};
    int low = Math.min(first, clicked), high = Math.max(first, clicked);
    return new int[] {low, high - low + 1};
  }

  static boolean selected(int first, int count, int slot) {
    return slot >= first && slot - first < count;
  }

  static int columns(int slots) {
    return Math.min(COLUMNS, Math.max(1, slots));
  }

  static int rows(int slots) {
    return (Math.max(1, slots) + COLUMNS - 1) / COLUMNS;
  }

  /** The slot at a cell, or -1 outside the grid or past the last slot. */
  static int at(int slots, int width, int height, int x, int y) {
    int columns = columns(slots), rows = rows(slots);
    int cellWidth = width / columns, cellHeight = height / rows;
    if (x < 0 || y < 0 || cellWidth == 0 || cellHeight == 0) return -1;
    int column = x / cellWidth, row = y / cellHeight;
    if (column >= columns || row >= rows) return -1;
    int slot = row * COLUMNS + column;
    return slot < slots ? slot : -1;
  }
}
