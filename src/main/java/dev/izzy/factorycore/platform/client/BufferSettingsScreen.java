package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.BufferPolicy;
import dev.izzy.factorycore.core.storage.BufferRegistry;
import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Buffer-mode Interface (playtest H3a, on the panel kit). Two views of one panel, chosen in the
 * title row: Settings (mode, filter, rate and goal, filter slots) and Inventory (the nine owned
 * slots, Take/Return and the player's inventory). Edits are a server-side draft that Apply saves;
 * ghost samples never become container stacks or touch the held inventory.
 */
public final class BufferSettingsScreen extends AbstractContainerScreen<BufferSettingsMenu>
    implements FilterDropTarget {
  private static final BufferRegistry.Mode[] MODES = {
    BufferRegistry.Mode.PAUSED,
    BufferRegistry.Mode.IMPORT,
    BufferRegistry.Mode.EXPORT,
    BufferRegistry.Mode.STOCK
  };
  private static final ResourceFilter.Mode[] FILTERS = ResourceFilter.Mode.values();

  /** Panel-relative rows; the player's inventory slots sit where the menu puts them. */
  private static final int CONTENTS_Y = PanelLayout.BODY,
      TAKE_Y = CONTENTS_Y + PanelLayout.ROW + PanelLayout.GAP,
      HELP_Y = TAKE_Y + PanelLayout.ROW + PanelLayout.GAP,
      MODE_Y = BufferSettingsMenu.HOTBAR_Y - 1 + PanelLayout.SLOT + PanelLayout.GAP,
      MESSAGE_Y = MODE_Y + PanelLayout.ROW + PanelLayout.GAP;

  private final PanelLayout layout =
      new PanelLayout(
          PanelLayout.WIDE, PanelLayout.heightFor(MESSAGE_Y + PanelLayout.LINE - PanelLayout.BODY));

  private Button redstone;
  private EditBox id, rate, target;
  private Button apply, reset, addId, addTag, setRate, setTarget, clear, addById, sampleButton;
  private Button takeOne, takeStack, returning;
  private ChoiceButton settingsView, inventoryView;
  private final List<ChoiceButton> modes = new ArrayList<>(), filters = new ArrayList<>();
  private final List<AbstractWidget> settingsWidgets = new ArrayList<>(),
      inventoryWidgets = new ArrayList<>();
  private int selectedRule, selectedSlot, statusWidth;

  /** B1 ghost filter slots; typed IDs are behind "Add by ID". */
  private FilterSlots filterSlots;

  private boolean typing;
  private boolean sample;
  private long shown;
  private Rect numbersRow, describeRow, modeRow, footer;

  public BufferSettingsScreen(BufferSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  @Override
  protected void init() {
    String value = id == null ? "" : id.getValue();
    String rateDraft = rate == null ? null : rate.getValue();
    String targetDraft = target == null ? null : target.getValue();
    super.init();
    upgradeSlot =
        addRenderableWidget(
            UpgradeSlotWidget.beside(
                leftPos, topPos, imageWidth, menu.containerId, menu::upgradeState));
    modes.clear();
    filters.clear();
    settingsWidgets.clear();
    inventoryWidgets.clear();

    // Settings | Inventory, right of the title.
    var settingsLabel = Component.translatable("gui.factorycore.buffer.settings");
    var inventoryLabel = Component.translatable("gui.factorycore.buffer.inventory");
    var views =
        PanelLayout.alignRight(
            new Rect(PanelLayout.EDGE, 3, layout.innerWidth(), 14),
            new int[] {font.width(settingsLabel) + 10, font.width(inventoryLabel) + 10},
            2);
    settingsView =
        choice(
            views.get(0),
            settingsLabel,
            "gui.factorycore.buffer.settings.help",
            () -> {
              menu.inventoryTab(false);
              sample = false;
              sync();
            });
    inventoryView =
        choice(
            views.get(1),
            inventoryLabel,
            "gui.factorycore.buffer.inventory.help",
            () -> {
              menu.inventoryTab(true);
              sync();
            });

    // Settings view.
    var rows = new PanelLayout(layout.width(), layout.height());
    var modeCells = fitted(rows.row(), MODES.length, i -> modeName(MODES[i]));
    for (int i = 0; i < MODES.length; i++) {
      int choice = MODES[i].ordinal() + 1;
      modes.add(
          settings(
              choice(
                  modeCells.get(i),
                  modeName(MODES[i]),
                  "gui.factorycore.buffer.mode.help",
                  () -> send(BufferSettingsPackets.MODE, 0, choice, "", new byte[32]))));
    }
    var filterCells = fitted(rows.row(), FILTERS.length, i -> filterName(FILTERS[i]));
    for (int i = 0; i < FILTERS.length; i++) {
      int choice = FILTERS[i].ordinal() + 1;
      filters.add(
          settings(
              choice(
                  filterCells.get(i),
                  filterName(FILTERS[i]),
                  "gui.factorycore.buffer.filter.help",
                  () -> send(BufferSettingsPackets.FILTER, 0, choice, "", new byte[32]))));
    }
    numbersRow = rows.row();
    var halves = PanelLayout.columns(numbersRow, 2, PanelLayout.GAP);
    var setLabel = Component.translatable("gui.factorycore.buffer.set");
    int setWidth = font.width(setLabel) + 10;
    rate = number(halves.get(0), "rate", setWidth);
    target = number(halves.get(1), "target", setWidth);
    rate.setTooltip(
        Tooltip.create(
            ThroughputContent.limits(
                "gui.factorycore.buffer.rate.help",
                ThroughputTier.BASE.bufferRate(),
                ThroughputTier.ADVANCED.bufferRate(),
                ThroughputTier.ENDGAME.bufferRate())));
    target.setTooltip(
        Tooltip.create(
            ThroughputContent.limits(
                "gui.factorycore.buffer.target.help", BufferPolicy.MAX_TARGET)));
    setRate =
        settings(
            button(
                setCell(halves.get(0), setWidth),
                "set",
                () -> number(rate, BufferSettingsPackets.RATE)));
    setTarget =
        settings(
            button(
                setCell(halves.get(1), setWidth),
                "set",
                () -> number(target, BufferSettingsPackets.TARGET)));

    // The filter slots, with Add by ID and Clear beside them (a right-click removes one slot).
    var slotsRow = rows.row(2 * PanelLayout.SLOT);
    filterSlots =
        settings(
            addRenderableWidget(
                new FilterSlots(
                    leftPos + slotsRow.x(),
                    topPos + slotsRow.y(),
                    new FilterSlots.Actions() {
                      @Override
                      public void addCarried() {
                        send(BufferSettingsPackets.CARRIED, 0, 0, "", new byte[32]);
                      }

                      @Override
                      public void cycle(int index, String shown) {
                        send(BufferSettingsPackets.CYCLE, index, 0, shown, new byte[32]);
                      }

                      @Override
                      public void remove(int index) {
                        send(BufferSettingsPackets.REMOVE, index, 0, "", new byte[32]);
                      }
                    },
                    index -> selectedRule = index)));
    int side = slotsRow.x() + FilterSlots.COLUMNS * PanelLayout.SLOT + PanelLayout.GAP;
    int sideWidth = slotsRow.right() - side;
    addById =
        settings(
            button(
                new Rect(side, slotsRow.y(), sideWidth, 17),
                "add_by_id",
                () -> {
                  typing = !typing;
                  sync();
                }));
    clear =
        settings(
            button(
                new Rect(side, slotsRow.y() + 19, sideWidth, 17),
                "clear",
                () -> send(BufferSettingsPackets.CLEAR, 0, 0, "", new byte[32])));

    // The selected slot's rule, or the typed-ID row while "Add by ID" is open.
    describeRow = rows.row(3 * PanelLayout.LINE);
    var idCells =
        PanelLayout.fit(
            new Rect(describeRow.x(), describeRow.y(), describeRow.width(), PanelLayout.ROW),
            new int[] {
              100, font.width(Component.translatable("gui.factorycore.buffer.id")) + 10, 40
            },
            PanelLayout.GAP);
    id =
        settings(
            addRenderableWidget(
                new EditBox(
                    font,
                    leftPos + idCells.get(0).x() + 1,
                    topPos + idCells.get(0).y() + 1,
                    idCells.get(0).width() - 2,
                    16,
                    Component.translatable("gui.factorycore.buffer.id"))));
    id.setMaxLength(256);
    id.setValue(value);
    addId =
        settings(
            button(
                idCells.get(1),
                "id",
                () -> send(BufferSettingsPackets.REGISTRY, 0, 0, id.getValue(), new byte[32])));
    addTag =
        settings(
            button(
                idCells.get(2),
                "tag",
                () -> send(BufferSettingsPackets.TAG, 0, 0, id.getValue(), new byte[32])));

    // Redstone control and Sample, level with the Inventory view's mode row.
    var controls = new Rect(PanelLayout.EDGE, MODE_Y, layout.innerWidth(), PanelLayout.ROW);
    var sampleLabel = Component.translatable("gui.factorycore.buffer.sample");
    var controlCells =
        PanelLayout.alignRight(controls, new int[] {font.width(sampleLabel) + 12}, 0);
    redstone =
        settings(
            addRenderableWidget(
                RedstoneButton.create(
                    menu.containerId,
                    menu::redstoneState,
                    leftPos + controls.x(),
                    topPos + controls.y(),
                    controlCells.get(0).x() - controls.x() - PanelLayout.GAP)));
    sampleButton =
        settings(
            button(
                controlCells.get(0),
                "sample",
                () -> {
                  menu.inventoryTab(true);
                  sample = true;
                  sync();
                }));

    // Inventory view: the buffer's slots, Take and Return, the player's inventory, the mode row.
    var takeCells =
        PanelLayout.columns(
            new Rect(
                BufferSettingsMenu.INVENTORY_X - 1, TAKE_Y, 9 * PanelLayout.SLOT, PanelLayout.ROW),
            3,
            PanelLayout.GAP);
    takeOne =
        inventory(
            button(
                takeCells.get(0),
                "take_one",
                () -> send(BufferSettingsPackets.TAKE, selectedSlot, 1, "", new byte[32])));
    takeStack =
        inventory(
            button(
                takeCells.get(1),
                "take_stack",
                () -> send(BufferSettingsPackets.TAKE, selectedSlot, 64, "", new byte[32])));
    returning =
        inventory(
            button(
                takeCells.get(2),
                "return",
                () -> send(BufferSettingsPackets.RETURN, 0, 0, "", new byte[32])));
    modeRow = controls;
    InterfaceModeRow.add(
        button -> inventory(addRenderableWidget(button)),
        font,
        menu.containerId,
        InterfaceKind.BUFFER,
        modeRow.offset(leftPos, topPos));

    // Footer: the device status, then Reset and Apply.
    footer = layout.footer();
    var resetLabel = Component.translatable("gui.factorycore.buffer.reset");
    var applyLabel = Component.translatable("gui.factorycore.buffer.apply");
    var actions =
        PanelLayout.alignRight(
            footer,
            new int[] {font.width(resetLabel) + 10, font.width(applyLabel) + 12},
            PanelLayout.GAP);
    statusWidth = actions.get(0).x() - footer.x() - PanelLayout.GAP;
    reset =
        button(
            actions.get(0),
            "reset",
            () -> send(BufferSettingsPackets.RESET, 0, 0, "", new byte[32]));
    apply =
        button(
            actions.get(1),
            "apply",
            () -> send(BufferSettingsPackets.APPLY, 0, 0, "", new byte[32]));
    shown = -1;
    sync();
    if (rateDraft != null) rate.setValue(rateDraft);
    if (targetDraft != null) target.setValue(targetDraft);
  }

  private <T extends AbstractWidget> T settings(T widget) {
    settingsWidgets.add(widget);
    return widget;
  }

  private <T extends AbstractWidget> T inventory(T widget) {
    inventoryWidgets.add(widget);
    return widget;
  }

  private List<Rect> fitted(Rect row, int count, java.util.function.IntFunction<Component> label) {
    var widths = new int[count];
    for (int i = 0; i < count; i++) widths[i] = font.width(label.apply(i)) + 10;
    return PanelLayout.fit(row, widths, PanelLayout.GAP);
  }

  private ChoiceButton choice(Rect cell, Component label, String help, Runnable action) {
    var button = new ChoiceButton(cell.offset(leftPos, topPos), label, b -> action.run());
    button.setTooltip(Tooltip.create(Component.translatable(help)));
    return addRenderableWidget(button);
  }

  private Button button(Rect cell, String key, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.buffer.",
            key,
            leftPos + cell.x(),
            topPos + cell.y(),
            cell.width(),
            cell.height(),
            action));
  }

  /** The Set button's cell at the right end of a number cell. */
  private static Rect setCell(Rect cell, int width) {
    return new Rect(cell.right() - width, cell.y(), width, cell.height());
  }

  /** A caption, then a number field up to the Set button. */
  private EditBox number(Rect cell, String key, int setWidth) {
    int caption =
        font.width(Component.translatable("gui.factorycore.buffer." + key)) + PanelLayout.GAP + 2;
    var box =
        settings(
            addRenderableWidget(
                new EditBox(
                    font,
                    leftPos + cell.x() + caption,
                    topPos + cell.y() + 1,
                    cell.width() - caption - setWidth - 2,
                    16,
                    Component.translatable("gui.factorycore.buffer." + key))));
    box.setMaxLength(3);
    return box;
  }

  private static Component modeName(BufferRegistry.Mode mode) {
    return Component.translatable(
        "status.factorycore.buffer_mode." + mode.name().toLowerCase(Locale.ROOT));
  }

  private static Component filterName(ResourceFilter.Mode mode) {
    return Component.translatable(
        "gui.factorycore.buffer.filter." + mode.name().toLowerCase(Locale.ROOT));
  }

  private void number(EditBox box, int action) {
    try {
      int value = Integer.parseInt(box.getValue());
      if (value < 0 || value > BufferPolicy.MAX_TARGET) throw new NumberFormatException();
      send(action, 0, value, "", new byte[32]);
    } catch (NumberFormatException invalid) {
      minecraft.player.displayClientMessage(
          Component.translatable("gui.factorycore.buffer.status.invalid"), true);
    }
  }

  private void send(int action, int selection, int value, String text, byte[] fingerprint) {
    PacketDistributor.sendToServer(
        new BufferSettingsPackets.Command(
            menu.containerId,
            menu.session(),
            menu.view().revision(),
            action,
            selection,
            value,
            text,
            fingerprint));
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    sync();
  }

  private void sync() {
    var view = menu.view();
    boolean inventory = menu.inventoryTab();
    boolean editing = view.editable() && !view.busy();
    settingsView.setSelected(!inventory);
    settingsView.active = inventory;
    inventoryView.setSelected(inventory);
    inventoryView.active = !inventory;
    RedstoneButton.refresh(redstone, menu.redstoneState());
    for (var widget : settingsWidgets) {
      widget.visible = !inventory;
      widget.active = editing;
    }
    for (var widget : inventoryWidgets) widget.visible = inventory;
    for (int i = 0; i < MODES.length; i++) {
      boolean current = MODES[i].name().equals(view.mode());
      modes.get(i).setSelected(current);
      modes.get(i).active = editing && !current;
    }
    for (int i = 0; i < FILTERS.length; i++) {
      boolean current = FILTERS[i].name().equals(view.filter());
      filters.get(i).setSelected(current);
      filters.get(i).active = editing && !current;
    }
    apply.active = editing && view.dirty();
    reset.active = apply.active;
    id.visible = addId.visible = addTag.visible = !inventory && typing;
    selectedRule = Math.min(selectedRule, Math.max(0, view.rules().size() - 1));
    filterSlots.show(view.rules(), ResourceKind.ITEM, selectedRule);
    clear.active = editing && !view.rules().isEmpty();
    boolean selected =
        selectedSlot < view.slots().size() && view.slots().get(selectedSlot).count() > 0;
    takeOne.active = inventory && !view.busy() && selected;
    takeStack.active = takeOne.active;
    returning.active = inventory && !view.busy() && view.staged() > 0 && !view.uncertain();
    if (shown != view.revision()) {
      if (!rate.isFocused()) rate.setValue(Integer.toString(view.rate()));
      if (!target.isFocused()) target.setValue(Integer.toString(view.target()));
      shown = view.revision();
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    Panels.panel(graphics, leftPos, topPos, layout);
    if (!menu.inventoryTab()) return;
    for (var slot : menu.slots) Panels.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
    for (int slot = 0; slot < 9; slot++)
      Panels.slot(graphics, leftPos + contentsX(slot) - 1, topPos + CONTENTS_Y);
    for (var row : menu.view().slots()) {
      int x = leftPos + contentsX(row.slot()), y = topPos + CONTENTS_Y + 1;
      if (row.slot() == selectedSlot) Panels.selected(graphics, new Rect(x, y, 16, 16));
      if (row.count() > 0) {
        var id = ResourceLocation.tryParse(row.icon());
        var item =
            id == null || !BuiltInRegistries.ITEM.containsKey(id)
                ? Items.BARRIER
                : BuiltInRegistries.ITEM.get(id);
        graphics.renderItem(item.getDefaultInstance(), x, y);
        graphics.renderItemDecorations(
            font, item.getDefaultInstance(), x, y, Integer.toString(row.count()));
      }
    }
  }

  /** The item x of buffer slot {@code slot}, panel-relative; it lines up with the inventory. */
  private static int contentsX(int slot) {
    return BufferSettingsMenu.INVENTORY_X + slot * PanelLayout.SLOT;
  }

  @Override
  public net.minecraft.client.renderer.Rect2i filterArea() {
    return filterSlots == null || !filterSlots.visible
        ? null
        : new net.minecraft.client.renderer.Rect2i(
            filterSlots.getX(),
            filterSlots.getY(),
            filterSlots.getWidth(),
            filterSlots.getHeight());
  }

  @Override
  public void addFilterId(ResourceKind kind, String id) {
    if (kind == ResourceKind.ITEM) send(BufferSettingsPackets.REGISTRY, 0, 0, id, new byte[32]);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    Panels.title(graphics, font, title, 0, 0, Panels.titleRoom(imageWidth));
    var view = menu.view();
    if (menu.inventoryTab()) {
      graphics.drawString(
          font,
          Component.translatable(
              sample
                  ? "gui.factorycore.buffer.sample.help"
                  : "gui.factorycore.buffer.deposit.help"),
          PanelLayout.EDGE + 1,
          HELP_Y,
          sample ? Panels.WARN : Panels.MUTED,
          false);
      InterfaceModeRow.caption(graphics, font, modeRow);
    } else {
      var halves = PanelLayout.columns(numbersRow, 2, PanelLayout.GAP);
      for (int i = 0; i < 2; i++)
        graphics.drawString(
            font,
            Component.translatable("gui.factorycore.buffer." + (i == 0 ? "rate" : "target")),
            halves.get(i).x(),
            halves.get(i).y() + 5,
            Panels.TEXT,
            false);
      // B1: the selected slot's rule, under the ghost slots.
      if (!typing && selectedRule < view.rules().size())
        FilterSlots.describe(
            graphics,
            font,
            view.rules().get(selectedRule),
            describeRow.x(),
            describeRow.y(),
            describeRow.width());
    }
    Component message;
    int color = Panels.WARN;
    if (view.requested() != 0) {
      if (view.status() == BufferSettingsPackets.Status.OK) color = Panels.GOOD;
      message =
          Component.translatable(
              "gui.factorycore.buffer.result",
              Component.translatable("gui.factorycore.buffer.status." + view.status().key()),
              view.moved(),
              view.requested());
    } else if (view.dirty()) message = Component.translatable("gui.factorycore.buffer.draft");
    else if (view.status() != BufferSettingsPackets.Status.READY) {
      message = Component.translatable("gui.factorycore.buffer.status." + view.status().key());
      if (view.status() == BufferSettingsPackets.Status.APPLIED
          || view.status() == BufferSettingsPackets.Status.OK) color = Panels.GOOD;
    } else message = Component.empty();
    graphics.drawString(
        font,
        font.plainSubstrByWidth(message.getString(), layout.innerWidth()),
        PanelLayout.EDGE + 1,
        MESSAGE_Y,
        color,
        false);
    StatusStrip.drawOnPanel(
        graphics, font, menu.deviceStatus(), footer.x(), footer.y() + 5, statusWidth);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
    StatusStrip.tooltip(
        graphics,
        font,
        menu.deviceStatus(),
        leftPos + footer.x(),
        topPos + footer.y() + 5,
        statusWidth,
        mouseX,
        mouseY);
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (button == 0
        && menu.inventoryTab()
        && x >= leftPos + contentsX(0)
        && x < leftPos + contentsX(9)
        && y >= topPos + CONTENTS_Y
        && y < topPos + CONTENTS_Y + PanelLayout.SLOT) {
      selectedSlot = ((int) x - leftPos - contentsX(0)) / PanelLayout.SLOT;
      return true;
    }
    var slot = getSlotUnderMouse();
    if (menu.inventoryTab()
        && button == 0
        && slot != null
        && slot.container == minecraft.player.getInventory()
        && slot.hasItem()
        && (sample || hasShiftDown())
        && x >= leftPos + slot.x
        && x < leftPos + slot.x + 16
        && y >= topPos + slot.y
        && y < topPos + slot.y + 16) {
      try {
        var key =
            new PlatformResourceCodec(minecraft.level.registryAccess()).itemKey(slot.getItem());
        send(
            sample ? BufferSettingsPackets.EXACT : BufferSettingsPackets.DEPOSIT,
            slot.getContainerSlot(),
            sample ? 0 : Math.min(64, slot.getItem().getCount()),
            "",
            TerminalPackets.fingerprint(key));
        if (sample) {
          sample = false;
          menu.inventoryTab(false);
          sync();
        }
      } catch (IllegalArgumentException invalid) {
        minecraft.player.displayClientMessage(
            Component.translatable("gui.factorycore.buffer.status.unsupported"), true);
      }
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
      if (rate.isFocused()) {
        number(rate, BufferSettingsPackets.RATE);
        return true;
      }
      if (target.isFocused()) {
        number(target, BufferSettingsPackets.TARGET);
        return true;
      }
      if (id.isFocused()) {
        send(BufferSettingsPackets.REGISTRY, 0, 0, id.getValue(), new byte[32]);
        return true;
      }
    }
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
