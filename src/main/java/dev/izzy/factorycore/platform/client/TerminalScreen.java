package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import dev.izzy.factorycore.platform.storage.TerminalPackets;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Native GUI; projected icons never act as container stacks or carry hidden item components. */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
  private EditBox search;
  private int selected;
  private long shownRevision;
  private Button one, stack, next, returning;

  public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 280;
    imageHeight = 232;
    inventoryLabelX = 59;
    inventoryLabelY = 136;
  }

  @Override
  protected void init() {
    String previousSearch = search == null ? "" : search.getValue();
    super.init();
    search =
        addRenderableWidget(
            new EditBox(
                font,
                leftPos + 10,
                topPos + 20,
                204,
                18,
                Component.translatable("gui.factorycore.terminal.search")));
    search.setMaxLength(128);
    search.setValue(previousSearch);
    search.setHint(Component.translatable("gui.factorycore.terminal.search"));
    addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.terminal.search"),
                button -> send(TerminalPackets.SEARCH, 0, 1, new byte[32]))
            .bounds(leftPos + 218, topPos + 20, 52, 18)
            .build());
    one =
        button(
            "withdraw_one",
            10,
            48,
            () -> send(TerminalPackets.WITHDRAW, selected, 1, new byte[32]));
    stack =
        button(
            "withdraw_stack",
            62,
            48,
            () -> send(TerminalPackets.WITHDRAW, selected, 64, new byte[32]));
    button("home", 114, 48, () -> send(TerminalPackets.HOME, 0, 1, new byte[32]));
    next = button("next", 166, 48, () -> send(TerminalPackets.NEXT, 0, 1, new byte[32]));
    returning = button("return", 218, 52, () -> send(TerminalPackets.RETURN, 0, 1, new byte[32]));
  }

  private Button button(String key, int x, int width, Runnable action) {
    return addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.terminal." + key), button -> action.run())
            .bounds(leftPos + x, topPos + 118, width, 18)
            .tooltip(
                Tooltip.create(Component.translatable("gui.factorycore.terminal." + key + ".help")))
            .build());
  }

  private void send(int action, int selection, int amount, byte[] fingerprint) {
    var view = menu.view();
    PacketDistributor.sendToServer(
        new TerminalPackets.Command(
            menu.containerId,
            menu.session(),
            view.revision(),
            action,
            selection,
            amount,
            search.getValue(),
            fingerprint));
  }

  @Override
  protected void containerTick() {
    super.containerTick();
    var view = menu.view();
    if (shownRevision != view.revision()) {
      selected = Math.min(selected, Math.max(0, view.rows().size() - 1));
      shownRevision = view.revision();
    }
    one.active = !view.busy() && !view.rows().isEmpty();
    stack.active = one.active;
    next.active = !view.busy() && view.next();
    returning.active =
        !view.busy() && !view.staged().equals("0") && !view.status().equals("uncertain");
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    graphics.fill(leftPos + 4, topPos + 4, leftPos + 276, topPos + 228, 0xff44484d);
    graphics.fill(leftPos + 8, topPos + 42, leftPos + 272, topPos + 115, 0xff171d22);
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
    var rows = menu.view().rows();
    for (int index = 0; index < rows.size(); index++) {
      int x = leftPos + 12 + 32 * index;
      graphics.fill(
          x, topPos + 45, x + 28, topPos + 74, index == selected ? 0xffbb8659 : 0xff59626b);
      var id = ResourceLocation.tryParse(rows.get(index).icon());
      var item =
          id == null || !BuiltInRegistries.ITEM.containsKey(id)
              ? Items.BARRIER
              : BuiltInRegistries.ITEM.get(id);
      graphics.renderItem(item.getDefaultInstance(), x + 6, topPos + 47);
      graphics.drawCenteredString(
          font, compact(rows.get(index).available()), x + 14, topPos + 65, 0xffffff);
    }
  }

  private static String compact(String decimal) {
    BigInteger number = new BigInteger(decimal);
    String[] suffix = {"", "k", "M", "G", "T", "P", "E", "Z", "Y"};
    int step = 0;
    while (number.compareTo(BigInteger.valueOf(1000)) >= 0 && step < suffix.length - 1) {
      number = number.divide(BigInteger.valueOf(1000));
      step++;
    }
    return number + suffix[step];
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 8, 7, 0xffffff, false);
    graphics.drawString(font, playerInventoryTitle, 59, 136, 0xeeeeee, false);
    var view = menu.view();
    if (selected < view.rows().size()) {
      var row = view.rows().get(selected);
      graphics.drawString(font, font.plainSubstrByWidth(row.name(), 254), 12, 79, 0xffffff, false);
      graphics.drawString(
          font,
          Component.translatable(
              "gui.factorycore.terminal.counts",
              compact(row.total()),
              compact(row.reserved()),
              compact(row.available())),
          12,
          91,
          0xc9dbed,
          false);
    }
    graphics.drawString(
        font,
        font.plainSubstrByWidth(
            Component.translatable("gui.factorycore.terminal.status." + view.status()).getString(),
            254),
        12,
        103,
        0xe4bc83,
        false);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    super.render(graphics, mouseX, mouseY, partial);
    renderTooltip(graphics, mouseX, mouseY);
    int row = (mouseX - leftPos - 12) / 32;
    boolean over =
        mouseX >= leftPos + 12
            && mouseX < leftPos + 268
            && mouseY >= topPos + 45
            && mouseY < topPos + 75;
    if (over && row < menu.view().rows().size()) {
      var value = menu.view().rows().get(row);
      graphics.renderTooltip(
          font,
          List.of(
              Component.literal(value.name()),
              Component.literal(value.icon()),
              Component.translatable(
                  "gui.factorycore.terminal.counts",
                  value.total(),
                  value.reserved(),
                  value.available()),
              Component.translatable("gui.factorycore.terminal.select.help")),
          Optional.empty(),
          mouseX,
          mouseY);
    } else if (mouseY >= topPos + 136 && mouseY < topPos + 228 && getSlotUnderMouse() != null)
      graphics.renderTooltip(
          font, Component.translatable("gui.factorycore.terminal.deposit.help"), mouseX, mouseY);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button == 0
        && mouseY >= topPos + 45
        && mouseY < topPos + 75
        && mouseX >= leftPos + 12
        && mouseX < leftPos + 268) {
      int index = ((int) mouseX - leftPos - 12) / 32;
      if (index < menu.view().rows().size()) {
        selected = index;
        return true;
      }
    }
    var slot = getSlotUnderMouse();
    if (button == 0
        && hasShiftDown()
        && slot != null
        && slot.container == minecraft.player.getInventory()
        && slot.hasItem()
        && mouseX >= leftPos + slot.x
        && mouseX < leftPos + slot.x + 16
        && mouseY >= topPos + slot.y
        && mouseY < topPos + slot.y + 16) {
      try {
        var key =
            new PlatformResourceCodec(minecraft.level.registryAccess()).itemKey(slot.getItem());
        send(
            TerminalPackets.DEPOSIT,
            slot.getContainerSlot(),
            Math.min(64, slot.getItem().getCount()),
            TerminalPackets.fingerprint(key));
      } catch (IllegalArgumentException unsupported) {
        minecraft.player.displayClientMessage(
            Component.translatable("gui.factorycore.terminal.status.unsupported"), true);
      }
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
      send(TerminalPackets.SEARCH, 0, 1, new byte[32]);
      return true;
    }
    if (!search.isFocused() && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_8) {
      int index = key - GLFW.GLFW_KEY_1;
      if (index < menu.view().rows().size()) selected = index;
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }
}
