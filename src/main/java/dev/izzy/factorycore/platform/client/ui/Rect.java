package dev.izzy.factorycore.platform.client.ui;

/** A screen-space rectangle in GUI pixels; {@code right} and {@code bottom} are exclusive. */
public record Rect(int x, int y, int width, int height) {
  public Rect {
    if (width < 0 || height < 0)
      throw new IllegalArgumentException("Negative size " + width + "x" + height);
  }

  public int right() {
    return x + width;
  }

  public int bottom() {
    return y + height;
  }

  public Rect offset(int dx, int dy) {
    return new Rect(x + dx, y + dy, width, height);
  }

  public boolean contains(Rect other) {
    return other.x >= x && other.y >= y && other.right() <= right() && other.bottom() <= bottom();
  }

  public boolean overlaps(Rect other) {
    return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
  }
}
