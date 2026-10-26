package dev.izzy.factorycore.platform.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel geometry shared by every Factory Core screen (ADR 0064). Coordinates are relative to the
 * panel's top-left corner; screens add {@code leftPos/topPos} with {@link Rect#offset}.
 *
 * <p>A panel has a fixed width ({@link #NARROW} like a vanilla chest, or {@link #WIDE}), a title
 * line, a body filled top-down by {@link #row}, and a footer row at the bottom: status on the left
 * and actions on the right. Edges match vanilla inventories, so a docked player inventory lines up
 * with slots drawn from {@link #EDGE}. Rows never stretch with the window; vanilla GUI scale sizes
 * the whole panel.
 */
public final class PanelLayout {
  /** Panel border to content, the vanilla inventory inset. */
  public static final int EDGE = 7;

  /** Space between neighbouring controls and between rows. */
  public static final int GAP = 4;

  /** Height of a button, field or choice row; equal to a slot so rows and slots share a grid. */
  public static final int ROW = 18;

  public static final int SLOT = 18;

  /** One line of text. */
  public static final int LINE = 10;

  /** Where the body starts: the title sits at y = 6, as in vanilla containers. */
  public static final int TITLE_Y = 6, BODY = 18;

  public static final int NARROW = 176, WIDE = 230;
  private final int width, height;
  private int cursor = BODY;

  public PanelLayout(int width, int height) {
    if (width < 2 * EDGE || height < BODY + ROW + EDGE)
      throw new IllegalArgumentException("Panel too small: " + width + "x" + height);
    this.width = width;
    this.height = height;
  }

  /** The panel height that fits {@code body} pixels of rows above the footer. */
  public static int heightFor(int body) {
    return BODY + body + GAP + ROW + EDGE;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  /** Width available to rows. */
  public int innerWidth() {
    return width - 2 * EDGE;
  }

  /** The whole panel. */
  public Rect bounds() {
    return new Rect(0, 0, width, height);
  }

  /** The area rows may use, between the title and the footer. */
  public Rect body() {
    return new Rect(EDGE, BODY, innerWidth(), footer().y() - GAP - BODY);
  }

  /** The footer row: status text on the left, actions on the right. */
  public Rect footer() {
    return new Rect(EDGE, height - EDGE - ROW, innerWidth(), ROW);
  }

  /** The y where the next row starts. */
  public int cursor() {
    return cursor;
  }

  /**
   * Takes the next full-width row of {@code rowHeight} and leaves {@link #GAP} below it.
   *
   * @throws IllegalStateException when the row would reach the footer; panel sizes are constants,
   *     so this is a layout bug that the tours and unit tests catch
   */
  public Rect row(int rowHeight) {
    var row = new Rect(EDGE, cursor, innerWidth(), rowHeight);
    if (!body().contains(row))
      throw new IllegalStateException(
          "Row at y=" + cursor + " (" + rowHeight + " px) overflows the panel body " + body());
    cursor += rowHeight + GAP;
    return row;
  }

  /** Takes a standard control row. */
  public Rect row() {
    return row(ROW);
  }

  /** Splits {@code row} into {@code count} equal columns separated by {@code gap}. */
  public static List<Rect> columns(Rect row, int count, int gap) {
    return fit(row, new int[count], gap);
  }

  /**
   * Splits {@code row} left to right into one cell per entry of {@code minimum}, separated by
   * {@code gap}. Each cell gets its minimum, and the remaining width is shared evenly (the leftmost
   * cells take the odd pixels) so the cells fill the row exactly. If the minimums do not fit, every
   * cell shrinks in proportion; the tours' label-fit check then reports the clipped text.
   */
  public static List<Rect> fit(Rect row, int[] minimum, int gap) {
    int count = minimum.length;
    if (count == 0) return List.of();
    int room = Math.max(0, row.width() - gap * (count - 1));
    long wanted = 0;
    for (int value : minimum) wanted += Math.max(0, value);
    var widths = new int[count];
    if (wanted <= room) {
      int extra = (int) (room - wanted);
      for (int i = 0; i < count; i++)
        widths[i] = Math.max(0, minimum[i]) + extra / count + (i < extra % count ? 1 : 0);
    } else {
      int used = 0;
      for (int i = 0; i < count; i++) {
        widths[i] = (int) (Math.max(0, minimum[i]) * (long) room / wanted);
        used += widths[i];
      }
      for (int i = 0; used < room; i = (i + 1) % count, used++) widths[i]++;
    }
    var cells = new ArrayList<Rect>(count);
    int x = row.x();
    for (int width : widths) {
      cells.add(new Rect(x, row.y(), width, row.height()));
      x += width + gap;
    }
    return cells;
  }

  /** Places cells of the given widths against the right end of {@code row}, in order. */
  public static List<Rect> alignRight(Rect row, int[] widths, int gap) {
    int total = gap * Math.max(0, widths.length - 1);
    for (int width : widths) total += width;
    var cells = new ArrayList<Rect>(widths.length);
    int x = row.right() - total;
    for (int width : widths) {
      cells.add(new Rect(x, row.y(), width, row.height()));
      x += width + gap;
    }
    return cells;
  }
}
