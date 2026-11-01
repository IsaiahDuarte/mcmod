package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Q143 (playthrough note 20): keeps other mods' buttons off our controls. Inventory sort mods
 * (Inventory Tweaks puts a 14×16 button right of the right-most slot of each group of nine) place
 * buttons by slot positions when a screen opens, including slots a page of ours hides, so the
 * button lands on our settings.
 *
 * <p>On our container screens, a widget another mod adds while the screen initialises is "foreign".
 * Before each frame a foreign widget is shown only while some slot is active and it overlaps none
 * of our visible widgets; otherwise it is hidden, which also stops its clicks.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class ForeignWidgets {
  /** Per open screen: our widgets when its init finished, then the foreign ones found after. */
  private static final Map<Screen, Set<GuiEventListener>> OURS =
      Collections.synchronizedMap(new WeakHashMap<>());

  private static final Map<Screen, List<AbstractWidget>> FOREIGN =
      Collections.synchronizedMap(new WeakHashMap<>());

  private ForeignWidgets() {}

  private static boolean ours(Screen screen) {
    return screen instanceof AbstractContainerScreen<?>
        && screen.getClass().getName().startsWith("dev.izzy.factorycore.");
  }

  private static boolean ourClass(Object widget) {
    return widget.getClass().getName().startsWith("dev.izzy.factorycore.");
  }

  /** Before other mods add theirs: what the screen has now is ours. */
  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void before(ScreenEvent.Init.Post event) {
    if (!ours(event.getScreen())) return;
    var mine = Collections.newSetFromMap(new IdentityHashMap<GuiEventListener, Boolean>());
    mine.addAll(event.getScreen().children());
    OURS.put(event.getScreen(), mine);
  }

  /** After every other mod: anything new that is not one of our classes is foreign. */
  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void after(ScreenEvent.Init.Post event) {
    var mine = OURS.get(event.getScreen());
    if (mine == null) return;
    var foreign = new ArrayList<AbstractWidget>();
    for (var child : event.getScreen().children())
      if (!mine.contains(child) && !ourClass(child) && child instanceof AbstractWidget widget)
        foreign.add(widget);
    FOREIGN.put(event.getScreen(), foreign);
  }

  @SubscribeEvent
  public static void render(ScreenEvent.Render.Pre event) {
    var foreign = FOREIGN.get(event.getScreen());
    if (foreign == null || foreign.isEmpty()) return;
    var screen = (AbstractContainerScreen<?>) event.getScreen();
    boolean slots = false;
    for (var slot : screen.getMenu().slots)
      if (slot.isActive()) {
        slots = true;
        break;
      }
    for (var widget : foreign) widget.visible = slots && !covers(screen, widget, foreign);
  }

  /** True when {@code widget} overlaps one of our visible widgets. */
  private static boolean covers(
      Screen screen, AbstractWidget widget, List<AbstractWidget> foreign) {
    for (var child : screen.children())
      if (child != widget
          && !foreign.contains(child)
          && child instanceof AbstractWidget other
          && other.visible
          && widget.getX() < other.getX() + other.getWidth()
          && other.getX() < widget.getX() + widget.getWidth()
          && widget.getY() < other.getY() + other.getHeight()
          && other.getY() < widget.getY() + widget.getHeight()) return true;
    return false;
  }

  /** The foreign widgets on {@code screen}, for verification. */
  public static List<AbstractWidget> on(Screen screen) {
    return FOREIGN.getOrDefault(screen, List.of());
  }
}
