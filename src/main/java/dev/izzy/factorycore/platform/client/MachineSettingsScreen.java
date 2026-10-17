package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.storage.*;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Local draft form; only explicit Apply sends one complete validated configuration. */
public final class MachineSettingsScreen extends AbstractContainerScreen<MachineSettingsMenu> {
  private EditBox label, priority, firstSlot, count, rate;
  private Button kindButton, accessButton, apply;
  private ResourceKind kind = ResourceKind.ITEM;
  private ConnectorSettings.Access access = ConnectorSettings.Access.READ_WRITE;
  private long shown = -1;
  private boolean invalid;

  public MachineSettingsScreen(MachineSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 218;
  }

  @Override
  protected void init() {
    String[] draft =
        label == null
            ? null
            : new String[] {
              label.getValue(),
              priority.getValue(),
              firstSlot.getValue(),
              count.getValue(),
              rate.getValue()
            };
    super.init();
    label = box(10, 43, 280, 64, "label");
    priority = box(80, 96, 65, 5, "priority");
    firstSlot = box(225, 96, 65, 10, "first");
    count = box(80, 123, 65, 2, "count");
    rate = box(225, 123, 65, 4, "rate");
    kindButton =
        button(
            "kind",
            10,
            69,
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
            69,
            136,
            () -> {
              access =
                  ConnectorSettings.Access.values()[
                      (access.ordinal() + 1) % ConnectorSettings.Access.values().length];
              sync();
            });
    apply = button("apply", 10, 174, 88, this::apply);
    button("reset", 106, 174, 88, () -> load(menu.view().form()));
    button("cancel", 202, 174, 88, this::onClose);
    if (draft == null) load(menu.view().form());
    else {
      label.setValue(draft[0]);
      priority.setValue(draft[1]);
      firstSlot.setValue(draft[2]);
      count.setValue(draft[3]);
      rate.setValue(draft[4]);
    }
    sync();
  }

  private EditBox box(int x, int y, int width, int length, String key) {
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
        Tooltip.create(Component.translatable("gui.factorycore.machine." + key + ".help")));
    return field;
  }

  private Button button(String key, int x, int y, int width, Runnable action) {
    return addRenderableWidget(
        Button.builder(Component.translatable("gui.factorycore.machine." + key), b -> action.run())
            .bounds(leftPos + x, topPos + y, width, 18)
            .tooltip(
                Tooltip.create(Component.translatable("gui.factorycore.machine." + key + ".help")))
            .build());
  }

  private void load(MachineSettingsPackets.Form form) {
    label.setValue(form.label());
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
      load(view.form());
      shown = view.generation();
    }
    kindButton.setMessage(
        Component.translatable("resource.factorycore." + kind.name().toLowerCase(Locale.ROOT)));
    accessButton.setMessage(
        Component.translatable("access.factorycore." + access.name().toLowerCase(Locale.ROOT)));
    apply.active = view.editable() && view.generation() > 0;
    label.setEditable(view.editable());
    priority.setEditable(view.editable());
    rate.setEditable(view.editable());
    firstSlot.setEditable(view.editable() && kind == ResourceKind.ITEM);
    count.setEditable(view.editable() && kind == ResourceKind.ITEM);
    kindButton.active = view.editable();
    accessButton.active = view.editable();
  }

  private void apply() {
    if (!apply.active) return;
    try {
      var form =
          new MachineSettingsPackets.Form(
              label.getValue(),
              kind,
              access,
              Integer.parseInt(priority.getValue()),
              Integer.parseInt(firstSlot.getValue()),
              Integer.parseInt(count.getValue()),
              Integer.parseInt(rate.getValue()));
      PacketDistributor.sendToServer(
          new MachineSettingsPackets.Apply(
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
    g.fill(leftPos, topPos, leftPos + 300, topPos + 218, 0xff282c30);
    g.fill(leftPos + 4, topPos + 4, leftPos + 296, topPos + 214, 0xff44484d);
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    g.drawString(font, title, 10, 10, 0xffffff, false);
    text(g, "label", 10, 30);
    text(g, "priority", 10, 100);
    text(g, "first", 160, 100);
    text(g, "count", 10, 127);
    text(g, "rate", 160, 127);
    var view = menu.view();
    g.drawString(
        font,
        Component.translatable(
            "gui.factorycore.machine.side",
            Component.translatable("direction.factorycore." + view.side()),
            Component.translatable(
                "gui.factorycore.machine.units." + kind.name().toLowerCase(Locale.ROOT))),
        10,
        153,
        0xd3e5ed,
        false);
    String state = invalid ? "invalid" : view.editable() ? view.status() : "denied";
    g.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable("gui.factorycore.machine.status." + state).getString(), 280),
        10,
        199,
        0xe4bc83,
        false);
  }

  private void text(GuiGraphics g, String key, int x, int y) {
    g.drawString(
        font, Component.translatable("gui.factorycore.machine." + key), x, y, 0xffffff, false);
  }

  @Override
  public void render(GuiGraphics g, int x, int y, float partial) {
    super.render(g, x, y, partial);
    renderTooltip(g, x, y);
  }
}
