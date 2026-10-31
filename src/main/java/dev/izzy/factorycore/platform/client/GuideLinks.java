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
    int right = left + screen.getXSize(), bottom = top + screen.getYSize();
    // The first spot on screen that no control already uses: beside the panel's top-right corner,
    // inside it, then beside the bottom-right corner (tabs and side slots differ per screen).
    int[][] spots = {
      {right + INSET, top + INSET},
      {right - SIZE - INSET, top + INSET},
      {right + INSET, bottom - SIZE - INSET}
    };
    for (var spot : spots) {
      if (spot[0] < 0
          || spot[1] < 0
          || spot[0] + SIZE > screen.width
          || spot[1] + SIZE > screen.height
          || occupied(event.getListenersList(), spot[0], spot[1])) continue;
      event.addListener(
          Button.builder(Component.literal("?"), pressed -> GuideScreen.open(page))
              .bounds(spot[0], spot[1], SIZE, SIZE)
              .tooltip(Tooltip.create(Component.translatable("gui.factorycore.guide_button")))
              .build());
      return;
    }
  }

  private static boolean occupied(
      java.util.List<net.minecraft.client.gui.components.events.GuiEventListener> listeners,
      int x,
      int y) {
    for (var listener : listeners)
      // Hidden widgets count too: some (the upgrade slot) show once their data arrives.
      if (listener instanceof net.minecraft.client.gui.components.AbstractWidget widget
          && x < widget.getX() + widget.getWidth()
          && widget.getX() < x + SIZE
          && y < widget.getY() + widget.getHeight()
          && widget.getY() < y + SIZE) return true;
    return false;
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
