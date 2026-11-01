package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Q135 (n22): while one of our custom text fields has focus, every key goes to the screen first and
 * no other mod's screen-key handler sees it. JEI, for one, only knows a vanilla {@code EditBox} is
 * being typed in, so without this R and U over an ingredient opened its recipe screen mid-sentence.
 * Escape still takes the normal path.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class TextKeys {
  private TextKeys() {}

  /** A screen whose focused widget is a text field JEI and others cannot recognise. */
  public interface Capture {
    boolean capturingText();
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void key(ScreenEvent.KeyPressed.Pre event) {
    if (!(event.getScreen() instanceof Capture capture)
        || !capture.capturingText()
        || event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) return;
    Screen screen = event.getScreen();
    // Cancelling Pre skips the screen's own handler too, so deliver the key here.
    screen.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
    event.setCanceled(true);
  }
}
