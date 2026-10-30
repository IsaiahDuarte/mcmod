package dev.izzy.factorycore.core.crafting;

import java.util.List;

/**
 * Shapes a recipe-viewer recipe into the terminal's processing grid (ergonomics Q5): at most nine
 * inputs and three outputs, amounts as grid units (items one each, fluids {@link #FLUID_UNIT} mB
 * each, 1–64). A recipe with more outputs keeps the first three, and the caller says so.
 */
public final class ProcessingTransfer {
  public static final int MAX_INPUTS = GridRequirement.CELLS, MAX_OUTPUTS = 3;
  public static final int FLUID_UNIT = 250, MAX_UNITS = 64;

  private ProcessingTransfer() {}

  /** The kept outputs and how many the recipe had. */
  public record Outputs<T>(List<T> kept, int total) {
    public Outputs {
      kept = List.copyOf(kept);
    }

    public boolean truncated() {
      return total > kept.size();
    }
  }

  public static <T> Outputs<T> outputs(List<T> all) {
    return new Outputs<>(all.subList(0, Math.min(all.size(), MAX_OUTPUTS)), all.size());
  }

  /** Grid units for {@code count} items: the count, within 1–64. */
  public static int itemUnits(long count) {
    return (int) Math.max(1, Math.min(MAX_UNITS, count));
  }

  /** Grid units for {@code millibuckets}: rounded up to whole 250 mB units, within 1–64. */
  public static int fluidUnits(long millibuckets) {
    long units = millibuckets <= 0 ? 1 : (millibuckets + FLUID_UNIT - 1) / FLUID_UNIT;
    return (int) Math.max(1, Math.min(MAX_UNITS, units));
  }
}
