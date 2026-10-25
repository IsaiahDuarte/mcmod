package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.BufferPolicy;
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
 * Two compact native tabs; ghost samples never become container stacks or mutate held inventory.
 */
public final class BufferSettingsScreen extends AbstractContainerScreen<BufferSettingsMenu>
    implements FilterDropTarget {
  private List<Button> modeRow = List.of();

  private EditBox id, rate, target;
  private Button mode,
      filter,
      apply,
      reset,
      addId,
      addTag,
      setRate,
      setTarget,
      remove,
      clear,
      takeOne,
      takeStack,
      returning,
      exact;
  private int selectedRule, selectedSlot;

  /** B1 ghost filter slots; typed IDs are behind "Add by ID". */
  private FilterSlots filterSlots;

  private Button addById;
  private boolean typing;
  private boolean sample;
  private long shown;

  public BufferSettingsScreen(BufferSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 320;
    imageHeight = 232;
    inventoryLabelX = 79;
    inventoryLabelY = 91;
  }

  @Override
  protected void init() {
    String value = id == null ? "" : id.getValue();
    String rateDraft = rate == null ? null : rate.getValue();
    String targetDraft = target == null ? null : target.getValue();
    super.init();
    button(
        "settings",
        174,
        5,
        65,
        () -> {
          menu.inventoryTab(false);
          sample = false;
          sync();
        });
    button(
        "inventory",
        243,
        5,
        67,
        () -> {
          menu.inventoryTab(true);
          sync();
        });
    mode =
        button("mode", 10, 23, 78, () -> send(BufferSettingsPackets.MODE, 0, 0, "", new byte[32]));
    filter =
        button(
            "filter",
            92,
            23,
            110,
            () -> send(BufferSettingsPackets.FILTER, 0, 0, "", new byte[32]));
    apply =
        button(
            "apply", 206, 23, 48, () -> send(BufferSettingsPackets.APPLY, 0, 0, "", new byte[32]));
    reset =
        button(
            "reset", 258, 23, 52, () -> send(BufferSettingsPackets.RESET, 0, 0, "", new byte[32]));
    rate = box(36, 48, 35, "rate", 3);
    target = box(155, 48, 45, "target", 3);
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
    setRate = button("set", 75, 47, 35, () -> number(rate, BufferSettingsPackets.RATE));
    setTarget = button("set", 204, 47, 36, () -> number(target, BufferSettingsPackets.TARGET));
    id = box(10, 73, 196, "id", 256);
    id.setValue(value);
    addId =
        button(
            "id",
            210,
            72,
            48,
            () -> send(BufferSettingsPackets.REGISTRY, 0, 0, id.getValue(), new byte[32]));
    addTag =
        button(
            "tag",
            262,
            72,
            48,
            () -> send(BufferSettingsPackets.TAG, 0, 0, id.getValue(), new byte[32]));
    filterSlots =
        addRenderableWidget(
            new FilterSlots(
                leftPos + 10,
                topPos + 97,
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
                index -> selectedRule = index));
    addById =
        button(
            "add_by_id",
            10,
            72,
            96,
            () -> {
              typing = true;
              sync();
            });
    remove =
        button(
            "remove",
            202,
            170,
            52,
            () -> send(BufferSettingsPackets.REMOVE, selectedRule, 0, "", new byte[32]));
    clear =
        button(
            "clear", 258, 170, 52, () -> send(BufferSettingsPackets.CLEAR, 0, 0, "", new byte[32]));
    exact =
        button(
            "sample",
            245,
            47,
            65,
            () -> {
              menu.inventoryTab(true);
              sample = true;
              sync();
            });
    takeOne =
        button(
            "take_one",
            10,
            186,
            72,
            () -> send(BufferSettingsPackets.TAKE, selectedSlot, 1, "", new byte[32]));
    takeStack =
        button(
            "take_stack",
            86,
            186,
            72,
            () -> send(BufferSettingsPackets.TAKE, selectedSlot, 64, "", new byte[32]));
    returning =
        button(
            "return",
            162,
            186,
            72,
            () -> send(BufferSettingsPackets.RETURN, 0, 0, "", new byte[32]));
    modeRow =
        InterfaceModeRow.add(
            this::addRenderableWidget,
            menu.containerId,
            InterfaceKind.BUFFER,
            leftPos + 236,
            topPos + 57,
            0,
            16,
            74,
            14);
    shown = -1;
    sync();
    if (rateDraft != null) rate.setValue(rateDraft);
    if (targetDraft != null) target.setValue(targetDraft);
  }

  private EditBox box(int x, int y, int width, String label, int limit) {
    var box =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + x,
                topPos + y,
                width,
                16,
                Component.translatable("gui.factorycore.buffer." + label)));
    box.setMaxLength(limit);
    return box;
  }

  private Button button(String key, int x, int y, int width, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.buffer.", key, leftPos + x, topPos + y, width, 18, action));
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
    mode.setMessage(
        Component.translatable(
            "status.factorycore.buffer_mode." + view.mode().toLowerCase(Locale.ROOT)));
    filter.setMessage(
        Component.translatable(
            "status.factorycore.buffer_filter." + view.filter().toLowerCase(Locale.ROOT)));
    mode.active = view.editable() && !view.busy();
    filter.active = mode.active;
    apply.active = mode.active && view.dirty();
    reset.active = mode.active && view.dirty();
    for (var widget :
        List.of(
            rate,
            target,
            id,
            setRate,
            setTarget,
            addId,
            addTag,
            remove,
            clear,
            addById,
            filterSlots)) {
      widget.visible = !inventory;
      widget.active = view.editable() && !view.busy();
    }
    id.visible = addId.visible = addTag.visible = !inventory && typing;
    addById.visible = !inventory && !typing;
    selectedRule = Math.min(selectedRule, Math.max(0, view.rules().size() - 1));
    filterSlots.show(
        view.rules(), dev.izzy.factorycore.core.resource.ResourceKind.ITEM, selectedRule);
    remove.active =
        !inventory && view.editable() && !view.busy() && selectedRule < view.rules().size();
    for (var button : modeRow) button.visible = inventory;
    takeOne.visible = inventory;
    takeStack.visible = inventory;
    returning.visible = inventory;
    exact.visible = true;
    boolean selected =
        selectedSlot < view.slots().size() && view.slots().get(selectedSlot).count() > 0;
    takeOne.active = inventory && !view.busy() && selected;
    takeStack.active = takeOne.active;
    returning.active =
        inventory && !view.busy() && view.staged() > 0 && !view.status().equals("uncertain");
    exact.active = view.editable() && !view.busy();
    if (shown != view.revision()) {
      if (!rate.isFocused()) rate.setValue(Integer.toString(view.rate()));
      if (!target.isFocused()) target.setValue(Integer.toString(view.target()));
      shown = view.revision();
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + 320, topPos + 232, 0xff282c30);
    graphics.fill(leftPos + 4, topPos + 4, leftPos + 316, topPos + 228, 0xff44484d);
    if (menu.inventoryTab()) {
      for (var slot : menu.slots) {
        graphics.fill(
            leftPos + slot.x - 1,
            topPos + slot.y - 1,
            leftPos + slot.x + 17,
            topPos + slot.y + 17,
            0xff151b20);
        graphics.fill(
            leftPos + slot.x,
            topPos + slot.y,
            leftPos + slot.x + 16,
            topPos + slot.y + 16,
            0xff59626b);
        graphics.fill(
            leftPos + slot.x + 1,
            topPos + slot.y + 1,
            leftPos + slot.x + 15,
            topPos + slot.y + 15,
            0xff30373e);
      }
      for (var row : menu.view().slots()) {
        int x = leftPos + 79 + row.slot() * 18;
        graphics.fill(
            x - 1,
            topPos + 52,
            x + 17,
            topPos + 71,
            row.slot() == selectedSlot ? 0xffbb8659 : 0xff20262c);
        if (row.count() > 0) {
          var id = ResourceLocation.tryParse(row.icon());
          var item =
              id == null || !BuiltInRegistries.ITEM.containsKey(id)
                  ? Items.BARRIER
                  : BuiltInRegistries.ITEM.get(id);
          graphics.renderItem(item.getDefaultInstance(), x, topPos + 53);
          graphics.renderItemDecorations(
              font, item.getDefaultInstance(), x, topPos + 53, Integer.toString(row.count()));
        }
      }
    }
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
  public void addFilterId(dev.izzy.factorycore.core.resource.ResourceKind kind, String id) {
    if (kind == dev.izzy.factorycore.core.resource.ResourceKind.ITEM)
      send(BufferSettingsPackets.REGISTRY, 0, 0, id, new byte[32]);
  }

  /** The strip shares the title row with the tabs (from x=174); hover shows it in full. */
  private int stripX() {
    return 8 + font.width(title) + 8;
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 8, 8, 0xffffff, false);
    StatusStrip.draw(graphics, font, menu.deviceStatus(), stripX(), 8, 170 - stripX());
    var view = menu.view();
    if (menu.inventoryTab()) {
      graphics.drawString(
          font, Component.translatable("gui.factorycore.buffer.contents"), 10, 47, 0xffffff, false);
      graphics.drawString(
          font, Component.translatable("gui.factorycore.interface.mode"), 236, 47, 0xffffff, false);
      graphics.drawString(font, playerInventoryTitle, 79, 91, 0xffffff, false);
      graphics.drawString(
          font,
          Component.translatable(
              sample
                  ? "gui.factorycore.buffer.sample.help"
                  : "gui.factorycore.buffer.deposit.help"),
          10,
          207,
          0xe4bc83,
          false);
    } else {
      graphics.drawString(
          font, Component.translatable("gui.factorycore.buffer.rate"), 10, 52, 0xffffff, false);
      graphics.drawString(
          font, Component.translatable("gui.factorycore.buffer.target"), 118, 52, 0xffffff, false);
      // B1: the selected slot's rule, beside the ghost slots.
      if (selectedRule < view.rules().size())
        FilterSlots.describe(graphics, font, view.rules().get(selectedRule), 162, 99, 148);
      graphics.drawString(
          font,
          Component.translatable(
              view.dirty() ? "gui.factorycore.buffer.draft" : "gui.factorycore.buffer.saved"),
          10,
          205,
          0xe4bc83,
          false);
    }
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            (view.requested() == 0
                    ? Component.translatable("gui.factorycore.buffer.status." + view.status())
                    : Component.translatable(
                        "gui.factorycore.buffer.result",
                        Component.translatable("gui.factorycore.buffer.status." + view.status()),
                        view.moved(),
                        view.requested()))
                .getString(),
            300),
        10,
        219,
        0xc9dbed,
        false);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
    StatusStrip.tooltip(
        graphics,
        font,
        menu.deviceStatus(),
        leftPos + stripX(),
        topPos + 8,
        170 - stripX(),
        mouseX,
        mouseY);
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (button == 0
        && menu.inventoryTab()
        && x >= leftPos + 79
        && x < leftPos + 241
        && y >= topPos + 52
        && y < topPos + 72) {
      selectedSlot = ((int) x - leftPos - 79) / 18;
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
}
