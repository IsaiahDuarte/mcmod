package dev.izzy.factorycore.platform.client;

import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A clickable strip of a container's slots or tanks (P5, UD4): click picks one, Shift-click extends
 * the window from the first selected slot. It only edits the screen's draft; Apply still sends the
 * validated window. Screens show it for 1–{@link SlotSelection#MAX_PICKABLE} slots and keep the
 * number fields otherwise.
 */
final class SlotPicker extends AbstractWidget {
  private final BiConsumer<Integer, Integer> changed;
  private int slots, first, count, hovered = -1;
  private boolean tanks;

  SlotPicker(int x, int y, int width, int height, BiConsumer<Integer, Integer> changed) {
    super(x, y, width, height, Component.translatable("gui.factorycore.slot_picker"));
    this.changed = changed;
  }

  /** A window field's draft value, or {@code fallback} while it does not parse. */
  static int number(net.minecraft.client.gui.components.EditBox field, int fallback) {
    try {
      return Integer.parseInt(field.getValue().strip());
    } catch (NumberFormatException invalid) {
      return fallback;
    }
  }

  /** Updates the container size and the draft window shown. */
  void show(int slots, boolean tanks, int first, int count) {
    this.slots = slots;
    this.tanks = tanks;
    this.first = first;
    this.count = count;
  }

  private int slotAt(double mouseX, double mouseY) {
    return SlotSelection.at(
        slots, width, height, (int) Math.floor(mouseX - getX()), (int) Math.floor(mouseY - getY()));
  }

  @Override
  protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    int columns = SlotSelection.columns(slots), rows = SlotSelection.rows(slots);
    int cellWidth = width / columns, cellHeight = height / rows;
    graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xff151b20);
    for (int slot = 0; slot < slots; slot++) {
      int x = getX() + (slot % SlotSelection.COLUMNS) * cellWidth;
      int y = getY() + (slot / SlotSelection.COLUMNS) * cellHeight;
      int color =
          SlotSelection.selected(first, count, slot)
              ? active ? 0xffbb8659 : 0xff8a6a50
              : 0xff59626b;
      graphics.fill(x + 1, y + 1, x + cellWidth - 1, y + cellHeight - 1, color);
    }
    int slot = isHovered() ? slotAt(mouseX, mouseY) : -1;
    if (slot != hovered) {
      hovered = slot;
      setTooltip(
          slot < 0
              ? Tooltip.create(Component.translatable("gui.factorycore.slot_picker.help"))
              : Tooltip.create(
                  Component.translatable(
                      tanks
                          ? "gui.factorycore.slot_picker.tank"
                          : "gui.factorycore.slot_picker.slot",
                      slot,
                      first,
                      first + count - 1)));
    }
  }

  @Override
  public void onClick(double mouseX, double mouseY, int button) {
    int slot = slotAt(mouseX, mouseY);
    if (slot < 0) return;
    var window = SlotSelection.click(first, slot, Screen.hasShiftDown());
    first = window[0];
    count = window[1];
    changed.accept(first, count);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(
        NarratedElementType.TITLE,
        Component.translatable(
            "gui.factorycore.slot_picker.narration", first, first + count - 1, slots));
  }
}
