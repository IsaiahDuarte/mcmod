package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.client.ui.ToggleButton;
import dev.izzy.factorycore.platform.network.GatewayPolicyMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Four choices (U8): Shared, Read-only and Isolated apply an exact preset; Custom unlocks the three
 * columns of toggles (branch grants, inherited permissions and inherited kinds). The toggles always
 * show the current policy, so a preset's effect is visible; under a preset they are locked and the
 * preset's description sits below them. Built on the panel kit (ADR 0064).
 */
public final class GatewayPolicyScreen extends AbstractContainerScreen<GatewayPolicyMenu> {
  /** Lines a column header may wrap to; checked by the terminal tour. */
  public static final int HEADER_LINES = 3;

  /** Column headers, then one toggle row per permission; then the description or note. */
  private static final int HEADER = HEADER_LINES * PanelLayout.LINE,
      COLUMNS_HEIGHT = HEADER + 7 * ToggleButton.HEIGHT,
      TEXT_HEIGHT = 4 * PanelLayout.LINE,
      BODY = PanelLayout.ROW + PanelLayout.GAP + COLUMNS_HEIGHT + PanelLayout.GAP + TEXT_HEIGHT;

  private final PanelLayout layout = new PanelLayout(PanelLayout.WIDE, PanelLayout.heightFor(BODY));
  private final List<ToggleButton> toggles = new ArrayList<>();
  private final List<ChoiceButton> presets = new ArrayList<>();
  private ChoiceButton custom;

  /** Panel-relative areas, set by {@link #init}. */
  private Rect columns, note, status;

  /** Custom view; starts on when the synchronized policy matches no preset. */
  private boolean customView;

  /** False until the policy data slots arrive; the first view is chosen from real data. */
  private boolean initialized;

  public GatewayPolicyScreen(GatewayPolicyMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  /** Width of one toggle column, for checks that its header fits in {@link #HEADER_LINES}. */
  public static int columnWidth() {
    return PanelLayout.columns(
            new Rect(0, 0, PanelLayout.WIDE - 2 * PanelLayout.EDGE, 1), 3, PanelLayout.GAP)
        .getFirst()
        .width();
  }

  @Override
  protected void init() {
    super.init();
    toggles.clear();
    presets.clear();
    var rows = new PanelLayout(layout.width(), layout.height());

    var choices = GatewayPolicy.Preset.values();
    var labels = new Component[choices.length + 1];
    var minimum = new int[labels.length];
    for (int i = 0; i < labels.length; i++) {
      String key = i < choices.length ? choices[i].name().toLowerCase(Locale.ROOT) : "custom";
      labels[i] = Component.translatable("gui.factorycore.gateway_policy.preset." + key);
      minimum[i] = font.width(labels[i]) + 10;
    }
    var cells = PanelLayout.fit(rows.row(), minimum, PanelLayout.GAP);
    for (var preset : choices) {
      int index = GatewayPolicyMenu.PRESET_BUTTON + preset.ordinal();
      String key = preset.name().toLowerCase(Locale.ROOT);
      var button =
          new ChoiceButton(
              cells.get(preset.ordinal()).offset(leftPos, topPos),
              labels[preset.ordinal()],
              b -> {
                customView = false;
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, index);
              });
      button.setTooltip(
          Tooltip.create(
              Component.translatable("gui.factorycore.gateway_policy.preset." + key + ".help")));
      presets.add(addRenderableWidget(button));
    }
    custom =
        addRenderableWidget(
            new ChoiceButton(
                cells.getLast().offset(leftPos, topPos),
                labels[choices.length],
                b -> customView = true));
    custom.setTooltip(
        Tooltip.create(
            Component.translatable("gui.factorycore.gateway_policy.preset.custom.help")));

    columns = rows.row(COLUMNS_HEIGHT);
    note = rows.row(TEXT_HEIGHT);
    var column = PanelLayout.columns(columns, 3, PanelLayout.GAP);
    for (int bit = 0; bit < GatewayPolicy.BITS; bit++) {
      var cell = column.get(bit < 7 ? 0 : bit < 14 ? 1 : 2);
      int row = bit < 14 ? bit % 7 : bit - 14;
      int index = bit;
      toggles.add(
          addRenderableWidget(
              new ToggleButton(
                  leftPos + cell.x(),
                  topPos + cell.y() + HEADER + row * ToggleButton.HEIGHT,
                  cell.width(),
                  name(bit),
                  b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, index))));
    }

    var access = Component.translatable("gui.factorycore.gateway_policy.access");
    var footer = rows.footer();
    var accessCell =
        PanelLayout.alignRight(footer, new int[] {font.width(access) + 12}, PanelLayout.GAP)
            .getFirst();
    addRenderableWidget(
        Button.builder(
                access,
                button ->
                    minecraft.gameMode.handleInventoryButtonClick(
                        menu.containerId, GatewayPolicyMenu.ACCESS_BUTTON))
            .bounds(
                leftPos + accessCell.x(),
                topPos + accessCell.y(),
                accessCell.width(),
                accessCell.height())
            .tooltip(
                Tooltip.create(
                    Component.translatable("gui.factorycore.gateway_policy.access.tooltip")))
            .build());
    status =
        new Rect(
            footer.x(), footer.y(), accessCell.x() - PanelLayout.GAP - footer.x(), footer.height());
  }

  private static Component name(int bit) {
    String key =
        bit < 14
            ? "permission.factorycore."
                + Permission.values()[bit % 7].name().toLowerCase(Locale.ROOT)
            : "kind.factorycore." + ResourceKind.values()[bit - 14].name().toLowerCase(Locale.ROOT);
    return Component.translatable(key);
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    var policy = menu.policy();
    var current = GatewayPolicy.Preset.matching(policy);
    if (!initialized && menu.synced()) {
      initialized = true;
      customView = current == null;
    }
    // Someone else's toggle edit leaves no preset, so show the toggles it changed.
    if (initialized && current == null) customView = true;
    for (var preset : GatewayPolicy.Preset.values()) {
      var button = presets.get(preset.ordinal());
      boolean selected = !customView && preset == current;
      button.setSelected(selected);
      button.active = menu.editable() && !selected;
    }
    custom.setSelected(customView);
    custom.active = !customView;
    int mask = policy.mask();
    for (int bit = 0; bit < toggles.size(); bit++) {
      var toggle = toggles.get(bit);
      toggle.active = customView && menu.editable();
      toggle.setOn((mask & 1 << bit) != 0);
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, leftPos, topPos, layout);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0);
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), status.x(), status.y() + 5, status.width());
    String[] headers = {"branch", "inherited", "kinds"};
    var cells = PanelLayout.columns(columns, 3, PanelLayout.GAP);
    for (int column = 0; column < 3; column++) {
      var cell = cells.get(column);
      Panels.wrapped(
          graphics,
          font,
          Component.translatable("gui.factorycore.gateway_policy." + headers[column]),
          new Rect(cell.x(), cell.y(), cell.width(), HEADER),
          Panels.TEXT);
    }
    var text = note;
    if (!menu.editable()) {
      int lines =
          Panels.wrapped(
              graphics,
              font,
              Component.translatable("gui.factorycore.gateway_policy.readonly"),
              text,
              Panels.WARN);
      int used = lines * PanelLayout.LINE;
      text = new Rect(text.x(), text.y() + used, text.width(), text.height() - used);
    }
    // The selected choice's description: a preset's exact effect, or Custom's narrowing rule.
    var preset = GatewayPolicy.Preset.matching(menu.policy());
    Component description =
        customView || preset == null
            ? Component.translatable("gui.factorycore.gateway_policy.help")
            : Component.translatable(
                "gui.factorycore.gateway_policy.preset."
                    + preset.name().toLowerCase(Locale.ROOT)
                    + ".description");
    Panels.wrapped(graphics, font, description, text, Panels.MUTED);
  }

  @Override
  protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
    super.renderTooltip(graphics, mouseX, mouseY);
    StatusStrip.tooltip(
        graphics,
        font,
        menu.deviceStatus(),
        leftPos + status.x(),
        topPos + status.y() + 5,
        status.width(),
        mouseX,
        mouseY);
  }
}
