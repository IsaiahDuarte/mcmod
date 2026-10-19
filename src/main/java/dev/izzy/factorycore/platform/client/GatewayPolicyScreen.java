package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.network.GatewayPolicyMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Three columns of toggles: branch grants, inherited permissions and inherited kinds. */
public final class GatewayPolicyScreen extends AbstractContainerScreen<GatewayPolicyMenu> {
  private final List<Button> toggles = new ArrayList<>();

  public GatewayPolicyScreen(GatewayPolicyMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 204;
  }

  @Override
  protected void init() {
    super.init();
    toggles.clear();
    for (int bit = 0; bit < GatewayPolicy.BITS; bit++) {
      int column = bit < 7 ? 0 : bit < 14 ? 1 : 2;
      int row = bit < 14 ? bit % 7 : bit - 14;
      int index = bit;
      toggles.add(
          addRenderableWidget(
              Button.builder(
                      Component.empty(),
                      button ->
                          minecraft.gameMode.handleInventoryButtonClick(menu.containerId, index))
                  .bounds(leftPos + 8 + column * 98, topPos + 34 + row * 20, 92, 18)
                  .build()));
    }
    // Free space under the three resource-kind toggles: open this gateway's access screen.
    addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.gateway_policy.access"),
                button ->
                    minecraft.gameMode.handleInventoryButtonClick(
                        menu.containerId, GatewayPolicyMenu.ACCESS_BUTTON))
            .bounds(leftPos + 8 + 2 * 98, topPos + 34 + 6 * 20, 92, 18)
            .tooltip(
                net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("gui.factorycore.gateway_policy.access.tooltip")))
            .build());
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
    int mask = menu.policy().mask();
    for (int bit = 0; bit < toggles.size(); bit++) {
      var toggle = toggles.get(bit);
      toggle.active = menu.editable();
      toggle.setMessage(name(bit).copy().append((mask & 1 << bit) != 0 ? ": on" : ": off"));
    }
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partial, int mouseX, int mouseY) {
    graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff282c30);
    graphics.fill(
        leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff44484d);
  }

  @Override
  protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.drawString(font, title, 8, 6, 0xffffff, false);
    String[] headers = {"branch", "inherited", "kinds"};
    for (int column = 0; column < 3; column++)
      graphics.drawString(
          font,
          font.plainSubstrByWidth(
              Component.translatable("gui.factorycore.gateway_policy." + headers[column])
                  .getString(),
              94),
          8 + column * 98,
          20,
          0xe4bc83,
          false);
    var footer =
        Component.translatable(
            menu.editable()
                ? "gui.factorycore.gateway_policy.help"
                : "gui.factorycore.gateway_policy.readonly");
    int y = 176;
    for (var line : font.split(footer, 284)) {
      graphics.drawString(font, line, 8, y, 0xd8dee9, false);
      y += 10;
    }
  }
}
