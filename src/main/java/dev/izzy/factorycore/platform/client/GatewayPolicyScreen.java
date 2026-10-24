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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Four choices (U8): Shared, Read-only and Isolated apply an exact preset; Custom shows the three
 * columns of toggles (branch grants, inherited permissions and inherited kinds).
 */
public final class GatewayPolicyScreen extends AbstractContainerScreen<GatewayPolicyMenu> {
  private static final int TOGGLES_Y = 62;
  private final List<Button> toggles = new ArrayList<>(), presets = new ArrayList<>();
  private Button custom;

  /** Custom view; starts on when the synchronized policy matches no preset. */
  private boolean customView;

  /** False until the policy data slots arrive; the first view is chosen from real data. */
  private boolean initialized;

  public GatewayPolicyScreen(GatewayPolicyMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    imageWidth = 300;
    imageHeight = 230;
  }

  @Override
  protected void init() {
    super.init();
    toggles.clear();
    presets.clear();
    for (var preset : GatewayPolicy.Preset.values()) {
      int index = GatewayPolicyMenu.PRESET_BUTTON + preset.ordinal();
      String key = preset.name().toLowerCase(Locale.ROOT);
      presets.add(
          addRenderableWidget(
              Button.builder(
                      Component.translatable("gui.factorycore.gateway_policy.preset." + key),
                      button -> {
                        customView = false;
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, index);
                      })
                  .bounds(leftPos + 8 + preset.ordinal() * 72, topPos + 18, 68, 18)
                  .tooltip(
                      Tooltip.create(
                          Component.translatable(
                              "gui.factorycore.gateway_policy.preset." + key + ".help")))
                  .build()));
    }
    custom =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("gui.factorycore.gateway_policy.preset.custom"),
                    button -> customView = true)
                .bounds(leftPos + 8 + 3 * 72, topPos + 18, 68, 18)
                .tooltip(
                    Tooltip.create(
                        Component.translatable(
                            "gui.factorycore.gateway_policy.preset.custom.help")))
                .build());
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
                  .bounds(leftPos + 8 + column * 98, topPos + TOGGLES_Y + row * 20, 92, 18)
                  .build()));
    }
    // Free space under the three resource-kind toggles: open this gateway's access screen.
    addRenderableWidget(
        Button.builder(
                Component.translatable("gui.factorycore.gateway_policy.access"),
                button ->
                    minecraft.gameMode.handleInventoryButtonClick(
                        menu.containerId, GatewayPolicyMenu.ACCESS_BUTTON))
            .bounds(leftPos + 8 + 2 * 98, topPos + TOGGLES_Y + 6 * 20, 92, 18)
            .tooltip(
                Tooltip.create(
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
    var policy = menu.policy();
    var current = GatewayPolicy.Preset.matching(policy);
    if (!initialized && menu.synced()) {
      initialized = true;
      customView = current == null;
    }
    // Someone else's toggle edit leaves no preset, so show the toggles it changed.
    if (initialized && current == null) customView = true;
    for (var preset : GatewayPolicy.Preset.values())
      presets.get(preset.ordinal()).active = menu.editable() && (customView || preset != current);
    custom.active = !customView;
    int mask = policy.mask();
    for (int bit = 0; bit < toggles.size(); bit++) {
      var toggle = toggles.get(bit);
      toggle.visible = customView;
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
    int strip = 8 + font.width(title) + 10;
    StatusStrip.draw(graphics, font, menu.deviceStatus(), strip, 6, imageWidth - 8 - strip);
    if (customView) {
      String[] headers = {"branch", "inherited", "kinds"};
      for (int column = 0; column < 3; column++) {
        int line = 40;
        for (var text :
            font.split(
                Component.translatable("gui.factorycore.gateway_policy." + headers[column]), 94)) {
          if (line > 49) break;
          graphics.drawString(font, text, 8 + column * 98, line, 0xe4bc83, false);
          line += 9;
        }
      }
    } else {
      var preset = GatewayPolicy.Preset.matching(menu.policy());
      if (preset != null) {
        int line = 44;
        for (var text :
            font.split(
                Component.translatable(
                    "gui.factorycore.gateway_policy.preset."
                        + preset.name().toLowerCase(Locale.ROOT)
                        + ".description"),
                284)) {
          graphics.drawString(font, text, 8, line, 0xd8dee9, false);
          line += 10;
        }
      }
    }
    var footer =
        Component.translatable(
            menu.editable()
                ? "gui.factorycore.gateway_policy.help"
                : "gui.factorycore.gateway_policy.readonly");
    int y = 204;
    for (var line : font.split(footer, 284)) {
      graphics.drawString(font, line, 8, y, 0xd8dee9, false);
      y += 10;
    }
  }
}
