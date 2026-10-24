package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsMenu;
import dev.izzy.factorycore.platform.storage.ConnectorSettingsPackets;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import dev.izzy.factorycore.platform.storage.MachineSettingsPackets;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
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

/** Local unsaved form for a Storage-mode Interface; only Apply sends one complete configuration. */
public final class ConnectorSettingsScreen extends AbstractContainerScreen<ConnectorSettingsMenu> {
  private EditBox priority, firstSlot, count, rate;
  private Button kindButton, accessButton, apply;
  private ResourceKind kind = ResourceKind.ITEM;
  private ConnectorSettings.Access access = ConnectorSettings.Access.READ_WRITE;
  private long shown = -1;
  private boolean invalid;
  private MachineSettingsPackets.Form lastForm;

  public ConnectorSettingsScreen(ConnectorSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 212;
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
    kindButton =
        button(
            "kind",
            10,
            28,
            136,
            () -> {
              kind = ResourceKind.values()[(kind.ordinal() + 1) % ResourceKind.values().length];
              firstSlot.setValue("0");
              count.setValue("64");
              rate.setValue(kind == ResourceKind.ITEM ? "64" : "1000");
              sync();
            });
    accessButton =
        button(
            "access",
            154,
            28,
            136,
            () -> {
              access =
                  ConnectorSettings.Access.values()[
                      (access.ordinal() + 1) % ConnectorSettings.Access.values().length];
              sync();
            });
    priority = box(80, 54, 65, 5, "priority", "gui.factorycore.connector.priority.help");
    rate = box(225, 54, 65, 5, "rate", null);
    firstSlot = box(80, 78, 65, 10, "window_first", null);
    count = box(225, 78, 65, 2, "window_count", null);
    rate.setTooltip(
        Tooltip.create(
            ThroughputContent.limits(
                "gui.factorycore.machine.rate.help",
                ThroughputTier.BASE.portRate(ResourceKind.ITEM),
                ThroughputTier.BASE.portRate(ResourceKind.FLUID),
                ThroughputTier.BASE.portRate(ResourceKind.ENERGY))));
    apply = button("apply", 10, 164, 88, this::apply);
    button("reset", 106, 164, 88, () -> load(menu.view().form()));
    button("cancel", 202, 164, 88, this::onClose);
    InterfaceModeRow.add(
        this::addRenderableWidget,
        menu.containerId,
        InterfaceKind.STORAGE,
        leftPos + 60,
        topPos + 188,
        78,
        0,
        74,
        18);
    if (draft == null) load(menu.view().form());
    else {
      priority.setValue(draft[0]);
      firstSlot.setValue(draft[1]);
      count.setValue(draft[2]);
      rate.setValue(draft[3]);
    }
    sync();
  }

  private EditBox box(int x, int y, int width, int length, String key, String help) {
    var field =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + x,
                topPos + y,
                width,
                16,
                Component.translatable("gui.factorycore.machine." + key)));
    field.setMaxLength(length);
    field.setTooltip(
        Tooltip.create(
            Component.translatable(
                help == null ? "gui.factorycore.machine." + key + ".help" : help)));
    return field;
  }

  private Button button(String key, int x, int y, int width, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.machine.", key, leftPos + x, topPos + y, width, 18, action));
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
    kindButton.setMessage(
        Component.translatable("resource.factorycore." + kind.name().toLowerCase(Locale.ROOT)));
    accessButton.setMessage(
        Component.translatable("access.factorycore." + access.name().toLowerCase(Locale.ROOT)));
    apply.active = view.editable() && view.generation() > 0;
    priority.setEditable(view.editable());
    rate.setEditable(view.editable());
    firstSlot.setEditable(view.editable() && kind != ResourceKind.ENERGY);
    count.setEditable(view.editable() && kind != ResourceKind.ENERGY);
    kindButton.active = view.editable();
    accessButton.active = view.editable();
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
    g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    g.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff44484d);
  }

  private void text(GuiGraphics g, String key, int x, int y) {
    g.drawString(
        font, Component.translatable("gui.factorycore.machine." + key), x, y, 0xffffff, false);
  }

  private void line(GuiGraphics g, Component text, int y, int color) {
    g.drawString(font, font.plainSubstrByWidth(text.getString(), 280), 10, y, color, false);
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    g.drawString(font, title, 10, 10, 0xffffff, false);
    g.drawString(
        font, Component.translatable("gui.factorycore.interface.mode"), 10, 193, 0xffffff, false);
    int strip = 10 + font.width(title) + 10;
    StatusStrip.draw(g, font, menu.deviceStatus(), strip, 10, imageWidth - 10 - strip);
    text(g, "priority", 10, 58);
    text(g, "rate", 160, 58);
    text(g, "window_first", 10, 82);
    text(g, "window_count", 160, 82);
    var view = menu.view();
    line(
        g,
        Component.translatable(
            "gui.factorycore.machine.side",
            Component.translatable("direction.factorycore." + view.side()),
            Component.translatable(
                "gui.factorycore.machine.units." + kind.name().toLowerCase(Locale.ROOT))),
        104,
        0xd3e5ed);
    line(
        g,
        Component.translatable(
            "gui.factorycore.machine.throughput",
            Component.translatable("throughput.factorycore.tier." + view.tier()),
            view.maxRate(),
            Component.translatable(
                "gui.factorycore.machine.units."
                    + view.form().kind().name().toLowerCase(Locale.ROOT)),
            Component.translatable(
                "throughput.factorycore.server." + (view.throttled() ? "throttled" : "ok"))),
        116,
        view.throttled() ? 0xe4bc83 : 0xd3e5ed);
    String state = invalid ? "invalid" : view.editable() ? view.status() : "denied";
    line(g, Component.translatable("gui.factorycore.machine.status." + state), 146, 0xe4bc83);
  }
}
