package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.signal.SignalMenu;
import dev.izzy.factorycore.platform.signal.SignalPackets;
import dev.izzy.factorycore.platform.storage.InterfaceKind;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * C1 Signal Interface settings: what to watch, when to turn on and how strong. The rule is a local
 * draft until Apply; the watched resource is set at once (held item, JEI drop or Add by ID).
 */
public final class SignalScreen extends AbstractContainerScreen<SignalMenu>
    implements FilterDropTarget {
  private static final int SLOT_X = 60, SLOT_Y = 24;

  private SignalRule.Condition condition;
  private SignalRule.Compare compare;
  private SignalRule.Output output;
  private EditBox threshold, id;
  private Button conditionButton, compareButton, outputButton, apply, addById, setItem, setFluid;
  private boolean typing, invalid;
  private long shown = -1;

  public SignalScreen(SignalMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 196;
  }

  @Override
  protected void init() {
    String typed = threshold == null ? null : threshold.getValue();
    super.init();
    addById =
        button(
            "add_by_id",
            200,
            24,
            90,
            () -> {
              typing = true;
              sync();
            });
    id =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + 10,
                topPos + 47,
                170,
                16,
                Component.translatable("gui.factorycore.buffer.id")));
    id.setMaxLength(256);
    setItem = button("set_item", 184, 46, 52, () -> resource(false));
    setFluid = button("set_fluid", 238, 46, 52, () -> resource(true));
    conditionButton =
        button(
            "condition",
            10,
            70,
            280,
            () -> {
              condition = SignalRule.Condition.values()[(condition.ordinal() + 1) % 2];
              sync();
            });
    compareButton =
        button(
            "compare",
            10,
            94,
            60,
            () -> {
              compare = SignalRule.Compare.values()[(compare.ordinal() + 1) % 2];
              sync();
            });
    threshold =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + 74,
                topPos + 95,
                80,
                16,
                Component.translatable("gui.factorycore.signal.threshold")));
    threshold.setMaxLength(19);
    threshold.setTooltip(
        Tooltip.create(Component.translatable("gui.factorycore.signal.threshold.help")));
    outputButton =
        button(
            "output",
            160,
            94,
            130,
            () -> {
              output = SignalRule.Output.values()[(output.ordinal() + 1) % 2];
              sync();
            });
    apply = button("apply", 10, 146, 88, this::apply);
    button("reset", 106, 146, 88, () -> load(menu.view().rule()));
    button("cancel", 202, 146, 88, this::onClose);
    InterfaceModeRow.add(
        this::addRenderableWidget,
        menu.containerId,
        InterfaceKind.SIGNAL,
        leftPos + 60,
        topPos + 170,
        58,
        0,
        56,
        18);
    if (condition == null) load(menu.view().rule());
    if (typed != null) threshold.setValue(typed);
    sync();
  }

  private Button button(String key, int x, int y, int width, Runnable action) {
    return addRenderableWidget(
        ScreenButtons.labeled(
            "gui.factorycore.signal.", key, leftPos + x, topPos + y, width, 18, action));
  }

  private void load(SignalRule rule) {
    condition = rule.condition();
    compare = rule.compare();
    output = rule.output();
    if (threshold != null) threshold.setValue(Long.toString(rule.threshold()));
    invalid = false;
  }

  private void send(int action, boolean fluid, String text, SignalRule rule) {
    PacketDistributor.sendToServer(
        new SignalPackets.Command(menu.containerId, menu.session(), action, fluid, text, rule));
  }

  private void resource(boolean fluid) {
    send(SignalPackets.Command.RESOURCE, fluid, id.getValue(), menu.view().rule());
  }

  private void apply() {
    if (!apply.active) return;
    try {
      long value = Long.parseLong(threshold.getValue().strip());
      send(
          SignalPackets.Command.APPLY,
          false,
          "",
          new SignalRule(condition, compare, value, output));
      invalid = false;
    } catch (IllegalArgumentException badInput) {
      invalid = true;
    }
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    sync();
  }

  private void sync() {
    var view = menu.view();
    if (shown != view.revision() && view.status().equals("applied")) load(view.rule());
    if (shown != view.revision()) shown = view.revision();
    boolean editable = view.editable();
    conditionButton.setMessage(
        Component.translatable(
            "gui.factorycore.signal.condition." + condition.name().toLowerCase(Locale.ROOT)));
    compareButton.setMessage(
        Component.translatable(
            "gui.factorycore.signal.compare." + compare.name().toLowerCase(Locale.ROOT)));
    outputButton.setMessage(
        Component.translatable(
            "gui.factorycore.signal.output." + output.name().toLowerCase(Locale.ROOT)));
    boolean stock = condition == SignalRule.Condition.STOCK;
    compareButton.visible = threshold.visible = outputButton.visible = stock;
    for (var widget :
        java.util.List.of(
            conditionButton, compareButton, outputButton, apply, addById, setItem, setFluid))
      widget.active = editable;
    threshold.setEditable(editable);
    addById.visible = !typing;
    id.visible = setItem.visible = setFluid.visible = typing;
  }

  private boolean overSlot(double mouseX, double mouseY) {
    double x = mouseX - leftPos - SLOT_X, y = mouseY - topPos - SLOT_Y;
    return x >= 0 && x < 18 && y >= 0 && y < 18;
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    // Clicking the watch slot while holding an item watches that item (or the fluid inside it).
    if (button == 0 && overSlot(mouseX, mouseY) && !menu.getCarried().isEmpty()) {
      send(SignalPackets.Command.CARRIED, false, "", menu.view().rule());
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && threshold.isFocused()) {
      apply();
      return true;
    }
    if (getFocused() instanceof EditBox box && box.isFocused() && key != GLFW.GLFW_KEY_ESCAPE)
      return box.keyPressed(key, scan, modifiers) || box.canConsumeInput();
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  public Rect2i filterArea() {
    return new Rect2i(leftPos + SLOT_X, topPos + SLOT_Y, 18, 18);
  }

  @Override
  public void addFilterId(ResourceKind kind, String dropped) {
    send(SignalPackets.Command.RESOURCE, kind == ResourceKind.FLUID, dropped, menu.view().rule());
  }

  @Override
  protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
    g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    g.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff44484d);
    int x = leftPos + SLOT_X, y = topPos + SLOT_Y;
    g.fill(x, y, x + 18, y + 18, 0xff151b20);
    g.fill(x + 1, y + 1, x + 17, y + 17, 0xff30373e);
    var view = menu.view();
    if (view.resource().isEmpty()) return;
    if (view.fluid()) {
      if (!ResourceIcons.renderFluid(g, view.resource(), x + 1, y + 1))
        g.renderFakeItem(new ItemStack(net.minecraft.world.item.Items.BUCKET), x + 1, y + 1);
    } else {
      var location = ResourceLocation.tryParse(view.resource());
      if (location != null && BuiltInRegistries.ITEM.containsKey(location))
        g.renderFakeItem(new ItemStack(BuiltInRegistries.ITEM.get(location)), x + 1, y + 1);
    }
  }

  @Override
  protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
    g.drawString(font, title, 10, 8, 0xffffff, false);
    int strip = 10 + font.width(title) + 10;
    StatusStrip.draw(g, font, menu.deviceStatus(), strip, 8, imageWidth - 10 - strip);
    var view = menu.view();
    g.drawString(
        font, Component.translatable("gui.factorycore.signal.watch"), 10, 29, 0xffffff, false);
    g.drawString(
        font,
        font.plainSubstrByWidth(
            view.resource().isEmpty()
                ? Component.translatable("gui.factorycore.signal.watch.none").getString()
                : view.name(),
            110),
        84,
        29,
        view.resource().isEmpty() ? 0xa5b5c5 : 0xd3e5ed,
        false);
    if (!typing)
      g.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.signal.watch.help").getString(), 280),
          10,
          50,
          0xa5b5c5,
          false);
    var state =
        Component.translatable(
            "gui.factorycore.signal.power",
            view.power(),
            view.reason().isEmpty()
                ? Component.translatable("gui.factorycore.signal.reason.ok")
                : Component.translatable("gui.factorycore.signal.reason." + view.reason()));
    g.drawString(
        font,
        font.plainSubstrByWidth(state.getString(), 280),
        10,
        119,
        view.power() > 0 ? 0xff6b6b : 0xd3e5ed,
        false);
    String key = invalid ? "invalid" : view.editable() ? view.status() : "denied";
    g.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable("gui.factorycore.signal.status." + key).getString(), 280),
        10,
        131,
        0xe4bc83,
        false);
    g.drawString(
        font, Component.translatable("gui.factorycore.interface.mode"), 10, 175, 0xffffff, false);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
    super.render(g, mouseX, mouseY, partial);
    renderTooltip(g, mouseX, mouseY);
    if (overSlot(mouseX, mouseY))
      g.renderTooltip(
          font, Component.translatable("gui.factorycore.signal.slot.help"), mouseX, mouseY);
  }
}
