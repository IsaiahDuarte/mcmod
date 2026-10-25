package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.storage.FilterEdits;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * B1: sixteen ghost filter slots shared by the Buffer, Machine preset and Storage screens. Click an
 * empty slot holding an item to add it; click a filled slot to change how it matches (Item, Exact,
 * each tag); right-click to remove it. Edits only send the screen's existing commands; no item is
 * moved.
 */
final class FilterSlots extends AbstractWidget {
  static final int COLUMNS = 8, SLOTS = 16;

  interface Actions {
    void addCarried();

    void cycle(int index, String shown);

    void remove(int index);
  }

  private final Actions actions;
  private final IntConsumer selected;
  private List<FilterEdits.Rule> rules = List.of();
  private ResourceKind kind = ResourceKind.ITEM;
  private int selection = -1, hovered = -2;

  FilterSlots(int x, int y, Actions actions, IntConsumer selected) {
    super(
        x,
        y,
        COLUMNS * 18,
        (SLOTS / COLUMNS) * 18,
        Component.translatable("gui.factorycore.filter"));
    this.actions = actions;
    this.selected = selected;
  }

  void show(List<FilterEdits.Rule> rules, ResourceKind kind, int selection) {
    this.rules = rules;
    this.kind = kind;
    this.selection = selection;
  }

  private int slotAt(double mouseX, double mouseY) {
    int column = (int) Math.floor((mouseX - getX()) / 18),
        row = (int) Math.floor((mouseY - getY()) / 18);
    return column < 0 || row < 0 || column >= COLUMNS || row >= SLOTS / COLUMNS
        ? -1
        : row * COLUMNS + column;
  }

  @Override
  protected boolean isValidClickButton(int button) {
    return button == 0 || button == 1;
  }

  @Override
  public void onClick(double mouseX, double mouseY, int button) {
    int slot = slotAt(mouseX, mouseY);
    if (slot < 0) return;
    var player = Minecraft.getInstance().player;
    boolean holding = player != null && !player.containerMenu.getCarried().isEmpty();
    if (slot >= rules.size()) {
      if (holding && button == 0) actions.addCarried();
      return;
    }
    selected.accept(slot);
    if (button == 1) actions.remove(slot);
    else if (holding) actions.addCarried();
    else actions.cycle(slot, rules.get(slot).icon());
  }

  @Override
  protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    var font = Minecraft.getInstance().font;
    for (int slot = 0; slot < SLOTS; slot++) {
      int x = getX() + (slot % COLUMNS) * 18, y = getY() + (slot / COLUMNS) * 18;
      graphics.fill(x, y, x + 18, y + 18, slot == selection ? 0xffbb8659 : 0xff151b20);
      graphics.fill(x + 1, y + 1, x + 17, y + 17, active ? 0xff30373e : 0xff262b30);
      if (slot >= rules.size()) continue;
      var rule = rules.get(slot);
      if (kind == ResourceKind.FLUID) {
        if (!ResourceIcons.renderFluid(graphics, rule.icon(), x + 1, y + 1))
          icon(graphics, "minecraft:bucket", x, y);
      } else icon(graphics, rule.icon(), x, y);
      // "=" for an exact match, "#" for a tag; a plain item match has no badge.
      String badge =
          rule.type() == FilterEdits.EXACT ? "=" : rule.type() == FilterEdits.TAG ? "#" : "";
      if (!badge.isEmpty()) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        graphics.fill(x + 1, y + 1, x + 7, y + 8, 0xd01a1d21);
        graphics.drawString(font, badge, x + 2, y + 1, 0xffe4a050, false);
        graphics.pose().popPose();
      }
    }
    int slot = isHovered() ? slotAt(mouseX, mouseY) : -1;
    if (slot != hovered) {
      hovered = slot;
      setTooltip(tooltip(slot));
    }
  }

  private Tooltip tooltip(int slot) {
    if (slot < 0 || slot >= rules.size())
      return Tooltip.create(Component.translatable("gui.factorycore.filter.empty.help"));
    var rule = rules.get(slot);
    return Tooltip.create(
        Component.literal(rule.label())
            .append("\n")
            .append(Component.translatable("gui.factorycore.filter.match." + rule.type()))
            .append("\n")
            .append(Component.translatable("gui.factorycore.filter.slot.help")));
  }

  /**
   * The selected rule beside the slots in at most three lines: its label on one line, then how it
   * matches wrapped to two. Coordinates are screen-relative, like the caller's labels.
   */
  static void describe(
      GuiGraphics graphics,
      net.minecraft.client.gui.Font font,
      FilterEdits.Rule rule,
      int x,
      int y,
      int width) {
    graphics.drawString(font, font.plainSubstrByWidth(rule.label(), width), x, y, 0xc9dbed, false);
    var lines =
        font.split(Component.translatable("gui.factorycore.filter.match." + rule.type()), width);
    for (int line = 0; line < Math.min(2, lines.size()); line++)
      graphics.drawString(font, lines.get(line), x, y + 10 + 10 * line, 0xa5b5c5, false);
  }

  private static void icon(GuiGraphics graphics, String id, int x, int y) {
    var location = ResourceLocation.tryParse(id);
    if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) return;
    graphics.renderFakeItem(new ItemStack(BuiltInRegistries.ITEM.get(location)), x + 1, y + 1);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(
        NarratedElementType.TITLE,
        Component.translatable("gui.factorycore.filter.narration", rules.size(), SLOTS));
  }
}
