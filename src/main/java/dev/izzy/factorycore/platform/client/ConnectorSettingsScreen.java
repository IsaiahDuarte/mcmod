package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.client.ui.ChoiceButton;
import dev.izzy.factorycore.platform.client.ui.PanelLayout;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsMenu;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsPackets;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Storage-mode Interface (playtest H3a, on the panel kit). A local, unsaved form: only Apply sends
 * one complete configuration. Settings and the B2 insert filter are two views of one panel, chosen
 * at the top; the Interface's mode is chosen at the bottom.
 */
public final class ConnectorSettingsScreen extends AbstractContainerScreen<ConnectorSettingsMenu>
    implements FilterDropTarget {
  private static final ResourceKind[] KINDS = ResourceKind.values();
  private static final ConnectorSettings.Access[] ACCESS = ConnectorSettings.Access.values();

  private static final int BODY =
      6 * (PanelLayout.ROW + PanelLayout.GAP)
          + PanelLayout.LINE
          + PanelLayout.GAP
          + PanelLayout.LINE;

  private final PanelLayout layout = new PanelLayout(PanelLayout.WIDE, PanelLayout.heightFor(BODY));

  private EditBox priority, firstSlot, count, rate;

  /** B2: the insert filter, shown in place of the settings while {@code filterTab}. */
  private boolean filterTab, typing;

  private int selectedRule;
  private FilterSlots filterSlots;
  private EditBox filterId;
  private Button filterMode, addById, addId, addTag, clearRules, apply;
  private ChoiceButton settingsView, filterView;
  private final List<ChoiceButton> kinds = new ArrayList<>(), accesses = new ArrayList<>();
  private final List<AbstractWidget> settingsWidgets = new ArrayList<>();
  private SlotPicker picker;
  private boolean picking;
  private ResourceKind kind = ResourceKind.ITEM;
  private ConnectorSettings.Access access = ConnectorSettings.Access.READ_WRITE;
  private long shown = -1;
  private boolean invalid;
  private MachineSettingsPackets.Form lastForm;

  /** Panel-relative rows that carry text. */
  private Rect fieldsRow, slotsRow, infoRow, messageRow, modeRow, footer, describeRow;

  private int statusWidth;

  public ConnectorSettingsScreen(ConnectorSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = layout.width();
    imageHeight = layout.height();
  }

  @Override
  protected void init() {
    String[] draft =
        priority == null
            ? null
            : new String[] {
              priority.getValue(), firstSlot.getValue(), count.getValue(), rate.getValue()
            };
    super.init();
    kinds.clear();
    accesses.clear();
    settingsWidgets.clear();
    var rows = new PanelLayout(layout.width(), layout.height());

    // Settings | Filter.
    var views = PanelLayout.columns(rows.row(), 2, PanelLayout.GAP);
    settingsView =
        choice(
            views.get(0),
            Component.translatable("gui.factorycore.machine.settings_tab"),
            "gui.factorycore.machine.settings_tab.help",
            () -> {
              filterTab = false;
              sync();
            });
    filterView =
        choice(
            views.get(1),
            Component.translatable("gui.factorycore.connector.filter"),
            "gui.factorycore.machine.filter_tab.help",
            () -> {
              filterTab = true;
              sync();
            });

    // Settings view: resource, access, priority and rate, slots, side and speed.
    var kindRow = rows.row();
    var kindWidths = new int[KINDS.length];
    for (int i = 0; i < KINDS.length; i++) kindWidths[i] = font.width(kindName(KINDS[i])) + 10;
    var kindCells = PanelLayout.fit(kindRow, kindWidths, PanelLayout.GAP);
    for (int i = 0; i < KINDS.length; i++) {
      var choice = KINDS[i];
      var button =
          choice(
              kindCells.get(i),
              kindName(choice),
              "gui.factorycore.machine.kind.help",
              () -> {
                kind = choice;
                firstSlot.setValue("0");
                count.setValue("64");
                rate.setValue(Integer.toString(ConnectorSettings.defaults(choice).rate()));
                sync();
              });
      kinds.add(button);
      settingsWidgets.add(button);
    }
    var accessRow = rows.row();
    var accessWidths = new int[ACCESS.length];
    for (int i = 0; i < ACCESS.length; i++)
      accessWidths[i] = font.width(accessName(ACCESS[i])) + 10;
    var accessCells = PanelLayout.fit(accessRow, accessWidths, PanelLayout.GAP);
    for (int i = 0; i < ACCESS.length; i++) {
      var choice = ACCESS[i];
      var button =
          choice(
              accessCells.get(i),
              accessName(choice),
              "gui.factorycore.machine.access.help",
              () -> {
                access = choice;
                sync();
              });
      accesses.add(button);
      settingsWidgets.add(button);
    }
    fieldsRow = rows.row();
    var halves = PanelLayout.columns(fieldsRow, 2, PanelLayout.GAP);
    priority = box(halves.get(0), "priority", 5, "gui.factorycore.connector.priority.help");
    rate = box(halves.get(1), "rate", 5, null);
    rate.setTooltip(
        Tooltip.create(
            ThroughputContent.limits(
                "gui.factorycore.machine.rate.help",
                ThroughputTier.BASE.portRate(ResourceKind.ITEM),
                ThroughputTier.BASE.portRate(ResourceKind.FLUID),
                ThroughputTier.BASE.portRate(ResourceKind.ENERGY))));
    slotsRow = rows.row();
    var slotHalves = PanelLayout.columns(slotsRow, 2, PanelLayout.GAP);
    firstSlot = box(slotHalves.get(0), "window_first", 10, null);
    count = box(slotHalves.get(1), "window_count", 2, null);
    int pickerX =
        slotsRow.x() + font.width(Component.translatable("gui.factorycore.slot_picker")) + 6;
    picker =
        addRenderableWidget(
            new SlotPicker(
                leftPos + pickerX,
                topPos + slotsRow.y(),
                slotsRow.right() - pickerX,
                18,
                (first, slots) -> {
                  firstSlot.setValue(Integer.toString(first));
                  count.setValue(Integer.toString(slots));
                }));
    infoRow = rows.row(PanelLayout.LINE);

    // Filter view, in the same rows: the insert mode; the slots with Add by ID and Clear beside
    // them (a right-click removes one slot); then the selected slot's rule, or the typed-ID row.
    filterMode =
        addRenderableWidget(
            Button.builder(
                    Component.empty(),
                    b -> filter(ConnectorSettingsPackets.FilterCommand.MODE, 0, ""))
                .bounds(
                    leftPos + kindRow.x(), topPos + kindRow.y(), kindRow.width(), kindRow.height())
                .tooltip(
                    Tooltip.create(Component.translatable("gui.factorycore.connector.insert.help")))
                .build());
    filterSlots =
        addRenderableWidget(
            new FilterSlots(
                leftPos + accessRow.x(),
                topPos + accessRow.y(),
                new FilterSlots.Actions() {
                  @Override
                  public void addCarried() {
                    filter(ConnectorSettingsPackets.FilterCommand.CARRIED, 0, "");
                  }

                  @Override
                  public void cycle(int index, String shown) {
                    filter(ConnectorSettingsPackets.FilterCommand.CYCLE, index, shown);
                  }

                  @Override
                  public void remove(int index) {
                    filter(ConnectorSettingsPackets.FilterCommand.REMOVE, index, "");
                  }
                },
                index -> selectedRule = index));
    int side = accessRow.x() + FilterSlots.COLUMNS * 18 + PanelLayout.GAP;
    int sideWidth = accessRow.right() - side;
    addById =
        filterButton(
            new Rect(side, accessRow.y(), sideWidth, 17),
            "add_by_id",
            () -> {
              typing = !typing;
              sync();
            });
    clearRules =
        filterButton(
            new Rect(side, accessRow.y() + 19, sideWidth, 17),
            "clear_rules",
            () -> filter(ConnectorSettingsPackets.FilterCommand.CLEAR, 0, ""));
    describeRow =
        new Rect(slotsRow.x(), slotsRow.y(), slotsRow.width(), infoRow.bottom() - slotsRow.y());
    var idCells = PanelLayout.fit(slotsRow, new int[] {100, 40, 40}, PanelLayout.GAP);
    filterId =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + idCells.get(0).x() + 1,
                topPos + idCells.get(0).y() + 1,
                idCells.get(0).width() - 2,
                16,
                Component.translatable("gui.factorycore.buffer.id")));
    filterId.setMaxLength(256);
    addId =
        filterButton(
            idCells.get(1),
            "add_id",
            () -> filter(ConnectorSettingsPackets.FilterCommand.REGISTRY, 0, filterId.getValue()));
    addTag =
        filterButton(
            idCells.get(2),
            "add_tag",
            () -> filter(ConnectorSettingsPackets.FilterCommand.TAG, 0, filterId.getValue()));

    // The Interface's mode, then the result of the last Apply.
    modeRow = rows.row();
    InterfaceModeRow.add(
        this::addRenderableWidget,
        font,
        menu.containerId,
        InterfaceKind.STORAGE,
        modeRow.offset(leftPos, topPos));
    messageRow = rows.row(PanelLayout.LINE);

    footer = rows.footer();
    var applyLabel = Component.translatable("gui.factorycore.port.apply");
    var resetLabel = Component.translatable("gui.factorycore.machine.reset");
    var cancelLabel = Component.translatable("gui.factorycore.machine.cancel");
    var actions =
        PanelLayout.alignRight(
            footer,
            new int[] {
              font.width(resetLabel) + 10, font.width(cancelLabel) + 10, font.width(applyLabel) + 12
            },
            PanelLayout.GAP);
    statusWidth = actions.get(0).x() - footer.x() - PanelLayout.GAP;
    footerButton(
        actions.get(0),
        resetLabel,
        "gui.factorycore.machine.reset.help",
        () -> {
          load(menu.view().form());
          sync();
        });
    footerButton(actions.get(1), cancelLabel, "gui.factorycore.machine.cancel.help", this::onClose);
    apply =
        footerButton(actions.get(2), applyLabel, "gui.factorycore.machine.apply.help", this::apply);

    if (draft == null) load(menu.view().form());
    else {
      priority.setValue(draft[0]);
      firstSlot.setValue(draft[1]);
      count.setValue(draft[2]);
      rate.setValue(draft[3]);
    }
    sync();
  }

  private ChoiceButton choice(Rect cell, Component label, String help, Runnable action) {
    var button = new ChoiceButton(cell.offset(leftPos, topPos), label, b -> action.run());
    button.setTooltip(Tooltip.create(Component.translatable(help)));
    return addRenderableWidget(button);
  }

  private Button filterButton(Rect cell, String key, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.machine.",
            key,
            leftPos + cell.x(),
            topPos + cell.y(),
            cell.width(),
            cell.height(),
            action));
  }

  private Button footerButton(Rect cell, Component label, String help, Runnable action) {
    return addRenderableWidget(
        Button.builder(label, b -> action.run())
            .bounds(leftPos + cell.x(), topPos + cell.y(), cell.width(), cell.height())
            .tooltip(Tooltip.create(Component.translatable(help)))
            .build());
  }

  /** The caption width before a field: the label plus a gap. */
  private int labelWidth(String key) {
    return font.width(Component.translatable("gui.factorycore.machine." + key)) + 6;
  }

  /** A caption and a number field filling the rest of {@code cell}. */
  private EditBox box(Rect cell, String key, int length, String help) {
    int caption = labelWidth(key);
    var field =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + cell.x() + caption,
                topPos + cell.y() + 1,
                cell.width() - caption,
                16,
                Component.translatable("gui.factorycore.machine." + key)));
    field.setMaxLength(length);
    field.setTooltip(
        Tooltip.create(
            Component.translatable(
                help == null ? "gui.factorycore.machine." + key + ".help" : help)));
    settingsWidgets.add(field);
    return field;
  }

  private static Component kindName(ResourceKind kind) {
    return Component.translatable("kind.factorycore." + kind.name().toLowerCase(Locale.ROOT));
  }

  private static Component accessName(ConnectorSettings.Access access) {
    return Component.translatable(
        "gui.factorycore.connector.access." + access.name().toLowerCase(Locale.ROOT));
  }

  private void load(MachineSettingsPackets.Form form) {
    priority.setValue(Integer.toString(form.priority()));
    firstSlot.setValue(Integer.toString(form.firstSlot()));
    count.setValue(Integer.toString(form.slotCount()));
    rate.setValue(Integer.toString(form.rate()));
    kind = form.kind();
    access = form.access();
    invalid = false;
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    sync();
  }

  private void sync() {
    var view = menu.view();
    if (shown != view.generation()) {
      if (!view.form().equals(lastForm)) load(view.form());
      lastForm = view.form();
      shown = view.generation();
    }
    boolean editable = view.editable();
    boolean energy = view.form().kind() == ResourceKind.ENERGY;
    if (energy) filterTab = false;
    settingsView.setSelected(!filterTab);
    settingsView.active = filterTab;
    filterView.setSelected(filterTab);
    filterView.active = !filterTab && !energy;
    for (int i = 0; i < KINDS.length; i++) {
      kinds.get(i).setSelected(KINDS[i] == kind);
      kinds.get(i).active = editable && KINDS[i] != kind;
    }
    for (int i = 0; i < ACCESS.length; i++) {
      accesses.get(i).setSelected(ACCESS[i] == access);
      accesses.get(i).active = editable && ACCESS[i] != access;
    }
    apply.active = editable && view.generation() > 0;
    priority.setEditable(editable);
    rate.setEditable(editable);
    firstSlot.setEditable(editable && kind != ResourceKind.ENERGY);
    count.setEditable(editable && kind != ResourceKind.ENERGY);
    int slots =
        switch (kind) {
          case ITEM -> view.itemSlots();
          case FLUID -> view.tanks();
          case ENERGY -> 0;
        };
    // P5: a small container shows a clickable slot strip in place of the two number fields.
    picking = SlotSelection.pickable(slots);
    picker.active = editable;
    picker.show(
        slots,
        kind == ResourceKind.FLUID,
        SlotPicker.number(firstSlot, 0),
        SlotPicker.number(count, 64));
    for (var widget : settingsWidgets) widget.visible = !filterTab;
    picker.visible = !filterTab && picking;
    firstSlot.visible = !filterTab && !picking;
    count.visible = !filterTab && !picking;
    // B2: the filter view replaces the settings controls in the same rows.
    filterMode.visible = filterTab;
    filterMode.active = editable;
    filterMode.setMessage(
        Component.translatable(
            "gui.factorycore.connector.insert." + view.insertMode().toLowerCase(Locale.ROOT)));
    filterSlots.visible = filterTab;
    filterSlots.active = editable;
    selectedRule = Math.max(0, Math.min(selectedRule, view.insertRules().size() - 1));
    filterSlots.show(view.insertRules(), view.form().kind(), selectedRule);
    addById.visible = clearRules.visible = filterTab;
    addById.active = editable;
    filterId.visible = addId.visible = addTag.visible = filterTab && typing;
    clearRules.active = editable && !view.insertRules().isEmpty();
  }

  private void filter(int action, int selection, String text) {
    PacketDistributor.sendToServer(
        new ConnectorSettingsPackets.FilterCommand(
            menu.containerId, menu.session(), action, Math.max(0, selection), text));
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
  public void addFilterId(ResourceKind dropped, String id) {
    if (dropped == menu.view().form().kind())
      filter(ConnectorSettingsPackets.FilterCommand.REGISTRY, 0, id);
  }

  private void apply() {
    if (!apply.active) return;
    try {
      var form =
          new MachineSettingsPackets.Form(
              "",
              kind,
              access,
              Integer.parseInt(priority.getValue()),
              Integer.parseInt(firstSlot.getValue()),
              Integer.parseInt(count.getValue()),
              Integer.parseInt(rate.getValue()));
      PacketDistributor.sendToServer(
          new ConnectorSettingsPackets.Apply(
              menu.containerId, menu.session(), menu.view().generation(), form));
      invalid = false;
    } catch (IllegalArgumentException badInput) {
      invalid = true;
    }
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
        && getFocused() instanceof EditBox) {
      apply();
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
    Panels.panel(g, leftPos, topPos, layout);
  }

  private void caption(GuiGraphics g, String key, Rect cell) {
    g.drawString(
        font,
        Component.translatable("gui.factorycore.machine." + key),
        cell.x(),
        cell.y() + 5,
        Panels.TEXT,
        false);
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    Panels.title(g, font, title, 0, 0);
    var view = menu.view();
    InterfaceModeRow.caption(g, font, modeRow);
    String state = invalid ? "invalid" : view.editable() ? view.status() : "denied";
    if (!state.equals("ready"))
      Panels.wrapped(
          g,
          font,
          Component.translatable("gui.factorycore.machine.status." + state),
          messageRow,
          Panels.WARN);
    StatusStrip.drawOnPanel(g, font, menu.deviceStatus(), footer.x(), footer.y() + 5, statusWidth);
    if (filterTab) {
      if (!typing && selectedRule < view.insertRules().size())
        FilterSlots.describe(
            g,
            font,
            view.insertRules().get(selectedRule),
            describeRow.x(),
            describeRow.y(),
            describeRow.width());
      return;
    }
    var halves = PanelLayout.columns(fieldsRow, 2, PanelLayout.GAP);
    caption(g, "priority", halves.get(0));
    caption(g, "rate", halves.get(1));
    if (picking)
      g.drawString(
          font,
          Component.translatable("gui.factorycore.slot_picker"),
          slotsRow.x(),
          slotsRow.y() + 5,
          Panels.TEXT,
          false);
    else {
      var slotHalves = PanelLayout.columns(slotsRow, 2, PanelLayout.GAP);
      caption(g, "window_first", slotHalves.get(0));
      caption(g, "window_count", slotHalves.get(1));
    }
    // The side it reaches and how fast, on one muted line (details in the rate tooltip).
    var info =
        Component.translatable("direction.factorycore." + view.side())
            .append(" · ")
            .append(
                Component.translatable(
                    "gui.factorycore.connector.speed",
                    view.maxRate(),
                    Component.translatable(
                        "gui.factorycore.machine.units."
                            + view.form().kind().name().toLowerCase(Locale.ROOT))));
    if (view.throttled())
      info.append(" · ").append(Component.translatable("throughput.factorycore.server.throttled"));
    g.drawString(
        font,
        font.plainSubstrByWidth(info.getString(), infoRow.width()),
        infoRow.x(),
        infoRow.y(),
        view.throttled() ? Panels.WARN : Panels.MUTED,
        false);
  }
}
