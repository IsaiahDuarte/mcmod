package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
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
    show(rules, kind, selection, List.of());
  }

  /** Q100: {@code statuses} is empty, or one Exporter status per rule, drawn as a corner dot. */
  void show(
      List<FilterEdits.Rule> rules,
      ResourceKind kind,
      int selection,
      List<dev.izzy.factorycore.platform.storage.MachinePresetPackets.RuleStatus> statuses) {
    if (!statuses.equals(this.statuses)) hovered = -2;
    this.rules = rules;
    this.kind = kind;
    this.selection = selection;
    this.statuses = statuses;
  }

  private List<dev.izzy.factorycore.platform.storage.MachinePresetPackets.RuleStatus> statuses =
      List.of();

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
      Panels.slot(graphics, x, y);
      if (slot == selection) Panels.selectedEdge(graphics, new Rect(x, y, 18, 18));
      if (!active) Panels.disabled(graphics, new Rect(x + 1, y + 1, 16, 16));
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
        Panels.badge(graphics, font, badge, new Rect(x + 1, y + 1, 6, 7));
        graphics.pose().popPose();
      }
      int dot =
          slot >= statuses.size()
              ? 0
              : switch (statuses.get(slot)) {
                case STOCKED -> 0xff3fbf3f;
                case CRAFTABLE -> 0xff3fbfbf;
                case EMPTY -> 0xffd04040;
                default -> 0;
              };
      if (dot != 0) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        graphics.fill(x + 12, y + 12, x + 17, y + 17, 0xff202020);
        graphics.fill(x + 13, y + 13, x + 16, y + 16, dot);
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
    var text =
        Component.literal(rule.label())
            .append("\n")
            .append(Component.translatable("gui.factorycore.filter.match." + rule.type()));
    if (slot < statuses.size()
        && statuses.get(slot)
            != dev.izzy.factorycore.platform.storage.MachinePresetPackets.RuleStatus.UNKNOWN)
      text.append("\n")
          .append(
              Component.translatable("gui.factorycore.filter.status." + statuses.get(slot).key()));
    return Tooltip.create(
        text.append("\n").append(Component.translatable("gui.factorycore.filter.slot.help")));
  }

  /**
   * The selected rule in at most three lines, in kit panel colors: its label on one line, then how
   * it matches wrapped to two. Coordinates are screen-relative, like the caller's labels.
   */
  static void describe(
      GuiGraphics graphics,
      net.minecraft.client.gui.Font font,
      FilterEdits.Rule rule,
      int x,
      int y,
      int width) {
    graphics.drawString(
        font,
        font.plainSubstrByWidth(rule.label(), width),
        x,
        y,
        dev.izzy.factorycore.platform.client.ui.Panels.TEXT,
        false);
    var lines =
        font.split(Component.translatable("gui.factorycore.filter.match." + rule.type()), width);
    for (int line = 0; line < Math.min(2, lines.size()); line++)
      graphics.drawString(
          font,
          lines.get(line),
          x,
          y + 10 + 10 * line,
          dev.izzy.factorycore.platform.client.ui.Panels.MUTED,
          false);
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
