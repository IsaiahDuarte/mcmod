package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import java.util.*;
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
  private boolean invalid, presets;
  private final List<AbstractWidget> bindingWidgets = new ArrayList<>(),
      presetWidgets = new ArrayList<>();
  private EditBox goal, rule;
  private Button mode,
      filter,
      sample,
      copy,
      deploy,
      resetPreset,
      returnStaged,
      remove,
      clear,
      presetCancel;
  private long presetShown = -1;
  private int selectedRule, selectedSlot;
  private MachineSettingsPackets.Form lastForm;

  public MachineSettingsScreen(MachineSettingsMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 236;
  }

  @Override
  protected void init() {
    String goalDraft = goal == null ? null : goal.getValue();
    String ruleDraft = rule == null ? "" : rule.getValue();
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
    bindingWidgets.clear();
    presetWidgets.clear();
    label = box(10, 43, 280, 64, "label");
    priority = box(80, 96, 65, 5, "priority");
    firstSlot = box(225, 96, 65, 10, "window_first");
    count = box(80, 123, 65, 2, "window_count");
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
    for (var child : children())
      if (child instanceof AbstractWidget widget) bindingWidgets.add(widget);
    presetButton(
        "binding",
        158,
        6,
        63,
        () -> {
          presets = false;
          sync();
        });
    presetButton(
        "presets",
        225,
        6,
        65,
        () -> {
          presets = true;
          sync();
        });
    mode = presetButton("mode", 10, 30, 130, () -> sendPreset(MachinePresetPackets.MODE));
    filter = presetButton("filter", 150, 30, 140, () -> sendPreset(MachinePresetPackets.FILTER));
    goal = presetBox(64, 54, 120, 19, "goal");
    presetButton("set", 190, 53, 100, () -> sendPreset(MachinePresetPackets.GOAL));
    rule = presetBox(10, 78, 170, 256, "rule");
    presetButton("registry", 184, 77, 50, () -> sendPreset(MachinePresetPackets.REGISTRY));
    presetButton("tag", 238, 77, 52, () -> sendPreset(MachinePresetPackets.TAG));
    presetButton(
        "previous",
        10,
        147,
        48,
        () -> {
          selectedRule = Math.max(0, selectedRule - 1);
          sync();
        });
    presetButton(
        "next",
        62,
        147,
        48,
        () -> {
          selectedRule = Math.min(menu.presetView().rules().size() - 1, selectedRule + 1);
          sync();
        });
    remove = presetButton("remove", 177, 147, 55, () -> sendPreset(MachinePresetPackets.REMOVE));
    clear = presetButton("clear", 236, 147, 54, () -> sendPreset(MachinePresetPackets.CLEAR));
    sample =
        presetButton(
            "sample",
            10,
            168,
            175,
            () -> {
              selectedSlot = (selectedSlot + 1) % 36;
              sync();
            });
    copy = presetButton("copy", 190, 168, 100, () -> sendPreset(MachinePresetPackets.SAMPLE));
    returnStaged =
        presetButton("return", 220, 214, 70, () -> sendPreset(MachinePresetPackets.RETURN));
    presetCancel = presetButton("cancel", 10, 214, 65, this::onClose);
    resetPreset = presetButton("reset", 80, 214, 65, () -> sendPreset(MachinePresetPackets.RESET));
    deploy = presetButton("deploy", 150, 214, 65, () -> sendPreset(MachinePresetPackets.DEPLOY));
    presetShown = -1;
    if (draft == null) load(menu.view().form());
    else {
      label.setValue(draft[0]);
      priority.setValue(draft[1]);
      firstSlot.setValue(draft[2]);
      count.setValue(draft[3]);
      rate.setValue(draft[4]);
    }
    sync();
    if (goalDraft != null) goal.setValue(goalDraft);
    rule.setValue(ruleDraft);
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
      if (!view.form().equals(lastForm)) load(view.form());
      lastForm = view.form();
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
    firstSlot.setEditable(view.editable() && kind != ResourceKind.ENERGY);
    count.setEditable(view.editable() && kind != ResourceKind.ENERGY);
    kindButton.active = view.editable();
    accessButton.active = view.editable();
    for (var widget : bindingWidgets) widget.visible = !presets;
    var preset = menu.presetView();
    for (var widget : presetWidgets) {
      widget.visible = presets;
      widget.active = preset.editable() && !preset.busy();
    }
    if (presetShown != preset.revision()) {
      goal.setValue(Long.toString(preset.goal()));
      presetShown = preset.revision();
    }
    mode.setMessage(
        Component.translatable(
            "status.factorycore.buffer_mode." + preset.mode().name().toLowerCase(Locale.ROOT)));
    filter.setMessage(
        Component.translatable(
            "gui.factorycore.preset.filter." + preset.filter().name().toLowerCase(Locale.ROOT)));
    if (minecraft != null && minecraft.player != null) {
      var inventory = minecraft.player.getInventory();
      for (int i = 0; i < 36 && inventory.getItem(selectedSlot).isEmpty(); i++)
        selectedSlot = (selectedSlot + 1) % 36;
      var stack = inventory.getItem(selectedSlot);
      String name = stack.isEmpty() ? "—" : stack.getHoverName().getString();
      sample.setMessage(
          Component.literal(font.plainSubstrByWidth((selectedSlot + 1) + ": " + name, 163)));
      sample.setTooltip(Tooltip.create(Component.literal(name)));
      copy.active &= !stack.isEmpty() || preset.kind() == ResourceKind.ENERGY;
    }
    goal.setTooltip(
        Tooltip.create(
            Component.translatable("gui.factorycore.preset.goal.help")
                .append(" ")
                .append(
                    Component.translatable(
                        "gui.factorycore.preset.units."
                            + preset.kind().name().toLowerCase(Locale.ROOT)))));
    returnStaged.setTooltip(
        Tooltip.create(
            Component.translatable("status.factorycore.buffer_reason." + preset.reason())));
    selectedRule = Math.max(0, Math.min(selectedRule, preset.rules().size() - 1));
    remove.active &= !preset.rules().isEmpty();
    clear.active &= !preset.rules().isEmpty();
    presetCancel.active = true;
    returnStaged.active = preset.cleanup() && !preset.busy();
  }

  private EditBox presetBox(int x, int y, int width, int length, String key) {
    var field = box(x, y, width, length, key);
    field.setTooltip(
        Tooltip.create(Component.translatable("gui.factorycore.preset." + key + ".help")));
    presetWidgets.add(field);
    return field;
  }

  private Button presetButton(String key, int x, int y, int width, Runnable action) {
    var widget =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.preset." + key), b -> action.run())
                .bounds(leftPos + x, topPos + y, width, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable("gui.factorycore.preset." + key + ".help")))
                .build());
    if (!key.equals("binding") && !key.equals("presets")) presetWidgets.add(widget);
    return widget;
  }

  private void sendPreset(int action) {
    String text =
        switch (action) {
          case MachinePresetPackets.GOAL, MachinePresetPackets.DEPLOY -> goal.getValue();
          case MachinePresetPackets.REGISTRY, MachinePresetPackets.TAG -> rule.getValue();
          default -> "";
        };
    byte[] fingerprint = new byte[32];
    if (action == MachinePresetPackets.SAMPLE && minecraft != null && minecraft.player != null) {
      var stack = minecraft.player.getInventory().getItem(selectedSlot);
      if (!stack.isEmpty())
        fingerprint =
            TerminalPackets.fingerprint(
                new PlatformResourceCodec(minecraft.player.registryAccess()).itemKey(stack));
    }
    PacketDistributor.sendToServer(
        new MachinePresetPackets.Command(
            menu.containerId,
            menu.session(),
            menu.presetView().revision(),
            action,
            action == MachinePresetPackets.REMOVE ? selectedRule : selectedSlot,
            text,
            fingerprint));
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
      if (presets)
        sendPreset(
            getFocused() == rule ? MachinePresetPackets.REGISTRY : MachinePresetPackets.GOAL);
      else apply();
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
    g.fill(leftPos, topPos, leftPos + 300, topPos + imageHeight, 0xff282c30);
    g.fill(leftPos + 4, topPos + 4, leftPos + 296, topPos + imageHeight - 4, 0xff44484d);
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    g.drawString(font, title, 10, 10, 0xffffff, false);
    if (presets) {
      renderPresets(g);
      return;
    }
    text(g, "label", 10, 30);
    text(g, "priority", 10, 100);
    text(g, "window_first", 160, 100);
    text(g, "window_count", 10, 127);
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

  private void renderPresets(GuiGraphics g) {
    var view = menu.presetView();
    g.drawString(
        font, Component.translatable("gui.factorycore.preset.goal"), 10, 58, 0xffffff, false);
    int start = selectedRule / 4 * 4;
    g.fill(10, 99, 290, 144, 0xff282c30);
    for (int i = start; i < Math.min(start + 4, view.rules().size()); i++) {
      int y = 101 + (i - start) * 11;
      if (i == selectedRule) g.fill(11, y - 1, 289, y + 9, 0xff535e68);
      g.drawString(font, font.plainSubstrByWidth(view.rules().get(i), 274), 13, y, 0xffffff, false);
    }
    g.drawString(
        font,
        (view.rules().isEmpty() ? 0 : selectedRule + 1) + "/" + view.rules().size(),
        120,
        152,
        0xffffff,
        false);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable(
                        "status.factorycore.buffer_mode."
                            + view.running().name().toLowerCase(Locale.ROOT))
                    .getString()
                + " · "
                + Component.translatable("gui.factorycore.preset.staged", view.staged())
                    .getString(),
            173),
        10,
        190,
        0xd3e5ed,
        false);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable("gui.factorycore.preset.status." + view.status()).getString(),
            280),
        10,
        201,
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
    if (presets
        && x >= leftPos + 10
        && x < leftPos + 290
        && y >= topPos + 101
        && y < topPos + 144) {
      int index = selectedRule / 4 * 4 + (y - topPos - 101) / 11;
      if (index < menu.presetView().rules().size())
        g.renderTooltip(
            font, font.split(Component.literal(menu.presetView().rules().get(index)), 280), x, y);
    }
  }
}
