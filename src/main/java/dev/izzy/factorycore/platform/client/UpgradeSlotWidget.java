package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.client.ui.Panels;
import dev.izzy.factorycore.platform.client.ui.Rect;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.platform.upgrade.UpgradeSlots;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Q19: a device's upgrade slot, on a small kit panel beside the device's screen (top right, like
 * AE2's upgrade panel), so no screen layout has to move. It shows the top installed upgrade and
 * what the slot accepts; a click is menu button {@link UpgradeSlots#BUTTON} and the server decides
 * (install the carried upgrade, or take the top one out onto the cursor). Q113: with an Upgrade
 * Toolbelt holding the next upgrade, the slot offers it (a faint icon with a "+"), a click installs
 * it from the toolbelt, and a right-click ({@link UpgradeSlots#REMOVE}) only removes.
 */
public final class UpgradeSlotWidget extends AbstractWidget {
  /** Panel size: an 18-pixel slot with a 4-pixel border. */
  public static final int SIZE = 26;

  private final IntSupplier state;
  private final int menu;
  private int shownState = Integer.MIN_VALUE;
  private ThroughputTier shownOffer;

  public UpgradeSlotWidget(int x, int y, int menu, IntSupplier state) {
    super(x, y, SIZE, SIZE, Component.translatable("gui.factorycore.upgrade_slot"));
    this.menu = menu;
    this.state = state;
  }

  /** Placed beside a container screen's panel. */
  public static UpgradeSlotWidget beside(
      int left, int top, int width, int menu, IntSupplier state) {
    // Below the help button (GuideLinks), which sits beside the panel's top-right corner.
    return new UpgradeSlotWidget(
        left + width + 2, top + GuideLinks.INSET + GuideLinks.SIZE + 3, menu, state);
  }

  @Override
  protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
    int now = state.getAsInt();
    visible = UpgradeSlots.shown(now);
    if (!visible) return;
    var player = Minecraft.getInstance().player;
    var offer = player == null ? null : UpgradeSlots.offer(now, player.getInventory());
    if (now != shownState || offer != shownOffer) {
      shownState = now;
      shownOffer = offer;
      var text = describe(now).copy();
      if (offer != null)
        text.append("\n")
            .append(
                Component.translatable(
                    "gui.factorycore.upgrade_slot.offer", ThroughputContent.name(offer)));
      setTooltip(Tooltip.create(text));
    }
    Panels.panel(graphics, new Rect(getX(), getY(), SIZE, SIZE));
    Panels.slot(graphics, getX() + 4, getY() + 4);
    var tier = UpgradeSlots.tier(now);
    if (tier != ThroughputTier.BASE)
      graphics.renderItem(new ItemStack(ThroughputContent.item(tier)), getX() + 5, getY() + 5);
    else if (offer != null) {
      graphics.renderFakeItem(new ItemStack(ThroughputContent.item(offer)), getX() + 5, getY() + 5);
      // Faded: offered from the toolbelt, not installed.
      graphics.pose().pushPose();
      graphics.pose().translate(0, 0, 200);
      graphics.fill(getX() + 5, getY() + 5, getX() + 21, getY() + 21, 0x88c6c6c6);
      graphics.pose().popPose();
    }
    if (offer != null) {
      graphics.pose().pushPose();
      graphics.pose().translate(0, 0, 250);
      Panels.badge(
          graphics, Minecraft.getInstance().font, "+", new Rect(getX() + 15, getY() + 5, 6, 7));
      graphics.pose().popPose();
    }
    if (isHovered()) Panels.hovered(graphics, new Rect(getX() + 5, getY() + 5, 16, 16));
  }

  /** Tooltip: what is installed, what the slot accepts, and how to use it (M88). */
  public static Component describe(int state) {
    var tier = UpgradeSlots.tier(state);
    Component installed =
        tier == ThroughputTier.BASE
            ? ThroughputContent.name(tier)
            : UpgradeSlots.link(state) || tier == ThroughputTier.ADVANCED
                ? ThroughputContent.name(tier)
                : Component.translatable(
                    "gui.factorycore.upgrade_slot.both",
                    ThroughputContent.name(ThroughputTier.ADVANCED),
                    ThroughputContent.name(ThroughputTier.ENDGAME));
    return Component.translatable("gui.factorycore.upgrade_slot")
        .append("\n")
        .append(Component.translatable("gui.factorycore.upgrade_slot.installed", installed))
        .append("\n")
        .append(
            Component.translatable(
                UpgradeSlots.link(state)
                    ? "gui.factorycore.upgrade_slot.accepts.link"
                    : "gui.factorycore.upgrade_slot.accepts"))
        .append("\n")
        .append(Component.translatable("gui.factorycore.upgrade_slot.help"));
  }

  @Override
  protected boolean isValidClickButton(int button) {
    return button == 0 || button == 1;
  }

  @Override
  public void onClick(double mouseX, double mouseY, int button) {
    var minecraft = Minecraft.getInstance();
    if (minecraft.gameMode != null)
      minecraft.gameMode.handleInventoryButtonClick(
          menu, button == 1 ? UpgradeSlots.REMOVE : UpgradeSlots.BUTTON);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    defaultButtonNarrationText(output);
  }

  /** The area JEI must keep clear. */
  public Rect area() {
    return new Rect(getX(), getY(), SIZE, SIZE);
  }
}
