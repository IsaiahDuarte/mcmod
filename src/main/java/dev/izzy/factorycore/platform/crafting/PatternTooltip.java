package dev.izzy.factorycore.platform.crafting;

import java.util.List;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Ergonomics Q68: the recipe drawn in a pattern's tooltip. A crafting pattern carries its nine grid
 * cells (with each cell's substitute item IDs, which the tooltip cycles through) and its output; a
 * processing pattern carries its declared item and fluid inputs and outputs.
 */
public record PatternTooltip(
    boolean processing,
    List<ItemStack> inputs,
    List<List<String>> alternatives,
    List<FluidStack> fluidInputs,
    List<ItemStack> outputs,
    List<FluidStack> fluidOutputs)
    implements TooltipComponent {
  public PatternTooltip {
    inputs = inputs.stream().map(ItemStack::copy).toList();
    alternatives = alternatives.stream().map(List::copyOf).toList();
    fluidInputs = fluidInputs.stream().map(FluidStack::copy).toList();
    outputs = outputs.stream().map(ItemStack::copy).toList();
    fluidOutputs = fluidOutputs.stream().map(FluidStack::copy).toList();
  }

  static PatternTooltip of(PatternContents contents) {
    return new PatternTooltip(
        false,
        contents.cells(),
        contents.alternatives(),
        List.of(),
        List.of(contents.output()),
        List.of());
  }

  static PatternTooltip of(ProcessingContents contents) {
    return new PatternTooltip(
        true,
        contents.inputs(),
        List.of(),
        contents.fluidInputs(),
        contents.outputs(),
        contents.fluidOutputs());
  }

  /** The first output, for showing a pattern as what it makes (Shift held). */
  public ItemStack primaryOutput() {
    return outputs.isEmpty() ? ItemStack.EMPTY : outputs.getFirst().copy();
  }
}
