package dev.izzy.factorycore.platform.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.guide.GuideContent;
import java.util.Map;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Q50: two ways into the Factory Guide. The guide key ({@link FactoryCoreKeys#GUIDE}, G) over a
 * Factory Core item in any inventory screen opens the page that pictures it. Every device screen
 * has a <b>?</b> button at its panel's top-right corner that opens its device's page. Closing the
 * guide returns to the screen it came from.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class GuideLinks {
  /** Device screens and the item whose guide page each opens. */
  static final Map<Class<?>, String> SCREENS =
      Map.ofEntries(
          Map.entry(DriveScreen.class, "factorycore:storage_drive"),
          Map.entry(TerminalScreen.class, "factorycore:storage_terminal"),
          Map.entry(BufferSettingsScreen.class, "factorycore:interface"),
          Map.entry(ConnectorSettingsScreen.class, "factorycore:interface"),
          Map.entry(SignalScreen.class, "factorycore:interface"),
          Map.entry(PortScreen.class, "factorycore:importer"),
          Map.entry(CablePortScreen.class, "factorycore:program_controller"),
          Map.entry(CrafterScreen.class, "factorycore:crafter"),
          Map.entry(ProgramScreen.class, "factorycore:program_controller"),
          Map.entry(EnergyBankScreen.class, "factorycore:starter_energy_bank"),
          Map.entry(OverviewScreen.class, "factorycore:network_controller"),
          Map.entry(NetworkAccessScreen.class, "factorycore:network_controller"),
          Map.entry(GatewayPolicyScreen.class, "factorycore:gateway"));

  /** Size of the <b>?</b> button and its inset from the panel's top-right corner. */
  static final int SIZE = 11, INSET = 3;

  private GuideLinks() {}

  /** The guide page for {@code stack}, or -1 when it is not a Factory Core item. */
  static int page(ItemStack stack) {
    if (stack.isEmpty()) return -1;
    return GuideContent.pageFor(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
  }

  @SubscribeEvent
  public static void init(ScreenEvent.Init.Post event) {
    if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
    var item = SCREENS.get(screen.getClass());
    if (item == null) return;
    int page = GuideContent.pageFor(item);
    int left = screen.getGuiLeft(), top = screen.getGuiTop();
    int right = left + screen.getXSize();
    // Q137 (n6): always the same spot, just outside the panel's top-right corner; side widgets
    // (the upgrade slot) start below it. Only a panel touching the screen edge puts it inside.
    int x = right + INSET + SIZE <= screen.width ? right + INSET : right - SIZE - INSET;
    var button = new HelpButton(x, top + INSET, pressed -> GuideScreen.open(page));
    button.setTooltip(Tooltip.create(Component.translatable("gui.factorycore.guide_button")));
    event.addListener(button);
  }

  /** The panel-relative x and y of the help button, for verification. */
  public static int[] spot(AbstractContainerScreen<?> screen) {
    int right = screen.getGuiLeft() + screen.getXSize();
    int x = right + INSET + SIZE <= screen.width ? right + INSET : right - SIZE - INSET;
    return new int[] {x, screen.getGuiTop() + INSET};
  }

  /** A square <b>?</b> button whose glyph is centred to the pixel. */
  static final class HelpButton extends Button {
    HelpButton(int x, int y, OnPress press) {
      super(x, y, SIZE, SIZE, Component.literal("?"), press, DEFAULT_NARRATION);
    }

    @Override
    public void renderString(
        net.minecraft.client.gui.GuiGraphics graphics,
        net.minecraft.client.gui.Font font,
        int color) {
      // The glyph is 5x7 px; Button centres the 6 px advance and an 8 px line, one px off each.
      graphics.drawString(
          font, getMessage(), getX() + (SIZE - 5) / 2, getY() + (SIZE - 7) / 2, color);
    }
  }

  @SubscribeEvent
  public static void key(ScreenEvent.KeyPressed.Pre event) {
    if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
        || !FactoryCoreKeys.GUIDE.isActiveAndMatches(
            InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) return;
    // Typing in a search or number box is never a guide request.
    if (screen.getFocused() instanceof EditBox box && box.isFocused()) return;
    var slot = screen.getSlotUnderMouse();
    int page = slot == null ? -1 : page(slot.getItem());
    if (page < 0) return;
    GuideScreen.open(page);
    event.setCanceled(true);
  }
}
