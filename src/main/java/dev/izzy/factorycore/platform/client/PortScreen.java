package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.core.storage.MachinePresets;
import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.MachinePresetPackets;
import dev.izzy.factorycore.platform.storage.MachineSettingsMenu;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import dev.izzy.factorycore.platform.storage.PortBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Importer and Exporter screen (playtest H6, ADR 0066), on the panel kit. It edits the port's
 * preset through the Machine-port preset commands: the resource it moves, a ghost filter (an empty
 * Importer filter means everything), whether the filter allows or blocks, the Exporter's Keep
 * amount, and redstone. Apply starts it in the viewer's name; Stop pauses it.
 */
public final class PortScreen extends AbstractContainerScreen<MachineSettingsMenu>
    implements FilterDropTarget {
  private static final ResourceKind[] KINDS = ResourceKind.values();

  private final PortBlock.Role role;
  private final PanelLayout layout;
  private final List<ChoiceButton> kinds = new ArrayList<>(), filterModes = new ArrayList<>();
  private FilterSlots filterSlots;
  private EditBox keep;
  private Button redstone, apply, stop, returning;
  private Rect hint, message, status;
  private int selectedRule = -1;

  /** Whether the Keep box was filled from the first synced preset; typing then owns it. */
  private boolean keepSynced;

  public PortScreen(MachineSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    role =
        menu.getType() == dev.izzy.factorycore.platform.storage.PortContent.IMPORTER_MENU.get()
            ? PortBlock.Role.IMPORTER
            : PortBlock.Role.EXPORTER;
    int body =
        3 * (PanelLayout.ROW + PanelLayout.GAP)
            + 2 * 18
            + PanelLayout.GAP
            + (role == PortBlock.Role.EXPORTER ? PanelLayout.ROW + PanelLayout.GAP : 0)
            + 2 * PanelLayout.LINE;
    layout = new PanelLayout(PanelLayout.NARROW, PanelLayout.heightFor(body));
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  public PortBlock.Role role() {
    return role;
  }

  @Override
  protected void init() {
    super.init();
    upgradeSlot =
        addRenderableWidget(
            UpgradeSlotWidget.beside(
                leftPos, topPos, imageWidth, menu.containerId, menu::upgradeState));
    kinds.clear();
    filterModes.clear();
    var rows = new PanelLayout(layout.width(), layout.height());

    var kindLabels = new int[KINDS.length];
    for (int i = 0; i < KINDS.length; i++) kindLabels[i] = font.width(kindName(KINDS[i])) + 10;
    var kindCells = PanelLayout.fit(rows.row(), kindLabels, PanelLayout.GAP);
    for (int i = 0; i < KINDS.length; i++) {
      var kind = KINDS[i];
      var button =
          new ChoiceButton(
              kindCells.get(i).offset(leftPos, topPos), kindName(kind), b -> switchKind(kind));
      button.setTooltip(Tooltip.create(Component.translatable("gui.factorycore.port.kind.help")));
      kinds.add(addRenderableWidget(button));
    }

    var slots = rows.row(2 * 18);
    filterSlots =
        addRenderableWidget(
            new FilterSlots(
                leftPos + slots.x() + (slots.width() - FilterSlots.COLUMNS * 18) / 2,
                topPos + slots.y(),
                new FilterSlots.Actions() {
                  @Override
                  public void addCarried() {
                    send(MachinePresetPackets.CARRIED, 0, "");
                  }

                  @Override
                  public void cycle(int index, String shown) {
                    send(MachinePresetPackets.CYCLE, index, shown);
                  }

                  @Override
                  public void remove(int index) {
                    send(MachinePresetPackets.REMOVE, index, "");
                  }
                },
                index -> selectedRule = index));

    // Only these / All but these, while the filter has entries; otherwise a hint in its place.
    var modeRow = rows.row();
    hint = modeRow;
    var modeCells = PanelLayout.columns(modeRow, 2, PanelLayout.GAP);
    var modes =
        new ResourceFilter.Mode[] {ResourceFilter.Mode.INCLUDE, ResourceFilter.Mode.EXCLUDE};
    for (int i = 0; i < 2; i++) {
      var mode = modes[i];
      var button =
          new ChoiceButton(
              modeCells.get(i).offset(leftPos, topPos),
              Component.translatable(
                  "gui.factorycore.port.filter." + mode.name().toLowerCase(Locale.ROOT)),
              b -> send(MachinePresetPackets.FILTER_MODE, mode.ordinal(), ""));
      button.setTooltip(
          Tooltip.create(
              Component.translatable(
                  "gui.factorycore.port.filter."
                      + mode.name().toLowerCase(Locale.ROOT)
                      + ".help")));
      filterModes.add(addRenderableWidget(button));
    }

    if (role == PortBlock.Role.EXPORTER) {
      var keepRow = rows.row();
      var label = Component.translatable("gui.factorycore.port.keep");
      // A resize rebuilds the screen; keep what the player typed rather than the saved goal.
      String typed = keep == null ? savedKeep() : keep.getValue();
      int labelWidth = font.width(label) + 6;
      keep =
          addRenderableWidget(
              new EditBox(
                  font,
                  leftPos + keepRow.x() + labelWidth,
                  topPos + keepRow.y() + 1,
                  keepRow.width() - labelWidth,
                  16,
                  label));
      keep.setMaxLength(12);
      keep.setHint(Component.translatable("gui.factorycore.port.keep.hint"));
      keep.setTooltip(Tooltip.create(Component.translatable("gui.factorycore.port.keep.help")));
      keep.setValue(typed);
    } else keep = null;

    var redstoneRow = rows.row();
    redstone =
        addRenderableWidget(
            RedstoneButton.create(
                menu.containerId,
                menu::redstoneState,
                leftPos + redstoneRow.x(),
                topPos + redstoneRow.y(),
                redstoneRow.width()));
    message = rows.row(2 * PanelLayout.LINE);

    var footer = rows.footer();
    var applyLabel = Component.translatable("gui.factorycore.port.apply");
    var stopLabel = Component.translatable("gui.factorycore.port.stop");
    var returnLabel = Component.translatable("gui.factorycore.preset.return");
    var cells =
        PanelLayout.alignRight(
            footer,
            new int[] {
              font.width(returnLabel) + 10, font.width(stopLabel) + 10, font.width(applyLabel) + 12
            },
            PanelLayout.GAP);
    returning =
        addRenderableWidget(
            button(
                cells.get(0),
                returnLabel,
                "gui.factorycore.preset.return.help",
                () -> send(MachinePresetPackets.RETURN, 0, "")));
    stop =
        addRenderableWidget(
            button(
                cells.get(1),
                stopLabel,
                "gui.factorycore.port.stop.help",
                () ->
                    send(
                        MachinePresetPackets.DEPLOY_AS, MachinePresets.Mode.PAUSED.ordinal(), "")));
    apply =
        addRenderableWidget(
            button(cells.get(2), applyLabel, "gui.factorycore.port.apply.help", this::apply));
    status =
        new Rect(
            footer.x(),
            footer.y(),
            cells.get(1).x() - PanelLayout.GAP - footer.x(),
            footer.height());
  }

  /** The saved Keep amount, or blank when the Exporter sends everything that matches. */
  private String savedKeep() {
    var view = menu.presetView();
    return view.mode() == MachinePresets.Mode.STOCK ? Long.toString(view.goal()) : "";
  }

  private Button button(Rect cell, Component label, String help, Runnable action) {
    return Button.builder(label, b -> action.run())
        .bounds(leftPos + cell.x(), topPos + cell.y(), cell.width(), cell.height())
        .tooltip(Tooltip.create(Component.translatable(help)))
        .build();
  }

  private static Component kindName(ResourceKind kind) {
    return Component.translatable("kind.factorycore." + kind.name().toLowerCase(Locale.ROOT));
  }

  /** Switching the resource rebinds the port; the server closes the screen once it is saved. */
  private void switchKind(ResourceKind kind) {
    var form = menu.view().form();
    PacketDistributor.sendToServer(
        new MachineSettingsPackets.Apply(
            menu.containerId,
            menu.session(),
            menu.view().generation(),
            new MachineSettingsPackets.Form(
                form.label(),
                kind,
                ConnectorSettings.Access.READ_WRITE,
                0,
                0,
                64,
                ConnectorSettings.defaults(kind).rate())));
  }

  /** Importers import; an Exporter exports, or keeps a stock when Keep has an amount. */
  private void apply() {
    if (role == PortBlock.Role.IMPORTER) {
      send(MachinePresetPackets.DEPLOY_AS, MachinePresets.Mode.IMPORT.ordinal(), "0");
      return;
    }
    String text = keep.getValue().strip();
    long amount;
    try {
      amount = text.isEmpty() ? 0 : Long.parseLong(text);
    } catch (NumberFormatException invalid) {
      amount = -1;
    }
    if (amount < 0) {
      keep.setTextColor(Panels.FIELD_ERROR);
      return;
    }
    keep.setTextColor(Panels.FIELD_TEXT);
    var mode = amount > 0 ? MachinePresets.Mode.STOCK : MachinePresets.Mode.EXPORT;
    send(MachinePresetPackets.DEPLOY_AS, mode.ordinal(), Long.toString(amount));
  }

  private void send(int action, int selection, String text) {
    PacketDistributor.sendToServer(
        new MachinePresetPackets.Command(
            menu.containerId,
            menu.session(),
            menu.presetView().revision(),
            action,
            selection,
            text,
            new byte[32]));
  }

  @Override
  public net.minecraft.client.renderer.Rect2i filterArea() {
    return filterSlots == null
        ? null
        : new net.minecraft.client.renderer.Rect2i(
            filterSlots.getX(),
            filterSlots.getY(),
            filterSlots.getWidth(),
            filterSlots.getHeight());
  }

  @Override
  public void addFilterId(ResourceKind kind, String id) {
    if (kind == menu.presetView().kind()) send(MachinePresetPackets.REGISTRY, 0, id);
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    var view = menu.presetView();
    boolean editable = view.editable() && !view.busy();
    for (int i = 0; i < KINDS.length; i++) {
      kinds.get(i).setSelected(KINDS[i] == view.kind());
      kinds.get(i).active = editable && KINDS[i] != view.kind();
    }
    boolean filtered = !view.rules().isEmpty();
    var modes =
        new ResourceFilter.Mode[] {ResourceFilter.Mode.INCLUDE, ResourceFilter.Mode.EXCLUDE};
    for (int i = 0; i < 2; i++) {
      var button = filterModes.get(i);
      button.visible = filtered;
      button.setSelected(view.filter() == modes[i]);
      button.active = editable && view.filter() != modes[i];
    }
    filterSlots.show(view.rules(), view.kind(), selectedRule, view.statuses());
    filterSlots.active = editable;
    RedstoneButton.refresh(redstone, menu.redstoneState());
    redstone.active = view.editable();
    boolean running = view.running() != MachinePresets.Mode.PAUSED;
    apply.active = editable;
    stop.active = editable && running;
    returning.visible = view.cleanup();
    returning.active = view.cleanup() && !view.busy();
    if (keep != null) {
      keep.setEditable(editable);
      // The client's placeholder view has revision 1; the server's first publish is newer.
      if (!keepSynced && view.revision() > 1) {
        keepSynced = true;
        if (keep.getValue().isEmpty()) keep.setValue(savedKeep());
      }
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, leftPos, topPos, layout);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0, Panels.titleRoom(imageWidth));
    var view = menu.presetView();
    if (view.rules().isEmpty())
      Panels.wrapped(
          graphics,
          font,
          Component.translatable(
              role == PortBlock.Role.IMPORTER
                  ? "gui.factorycore.port.everything"
                  : "gui.factorycore.port.choose"),
          new Rect(hint.x(), hint.y() + 5, hint.width(), PanelLayout.LINE),
          role == PortBlock.Role.IMPORTER ? Panels.MUTED : Panels.WARN);
    if (keep != null) {
      var label = Component.translatable("gui.factorycore.port.keep");
      graphics.drawString(
          font,
          label,
          keep.getX() - leftPos - font.width(label) - 6,
          keep.getY() - topPos + 4,
          Panels.TEXT,
          false);
    }
    var outcome = view.status();
    if (outcome != MachinePresetPackets.Status.READY) {
      Panels.wrapped(
          graphics,
          font,
          Component.translatable("gui.factorycore.preset.status." + outcome.key()),
          message,
          outcome.good() ? Panels.MUTED : Panels.WARN);
    }
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), status.x(), status.y() + 5, status.width());
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
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

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == keep) {
      apply();
      return true;
    }
    if (keep != null && keep.isFocused() && key != GLFW.GLFW_KEY_ESCAPE)
      return keep.keyPressed(key, scan, modifiers) || keep.canConsumeInput();
    return super.keyPressed(key, scan, modifiers);
  }

  /** Q19: the device's upgrade slot beside the panel. */
  private UpgradeSlotWidget upgradeSlot;

  /** A click on the upgrade slot is not a click outside, so a held upgrade is not dropped. */
  @Override
  protected boolean hasClickedOutside(double x, double y, int left, int top, int button) {
    return super.hasClickedOutside(x, y, left, top, button)
        && (upgradeSlot == null || !upgradeSlot.isMouseOver(x, y));
  }
}
