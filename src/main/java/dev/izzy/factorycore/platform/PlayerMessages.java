package dev.izzy.factorycore.platform;

import dev.izzy.factorycore.core.Refusal;
import net.minecraft.network.chat.Component;

/**
 * Q84: what a player sees for a refused action. A {@link Refusal.Translated} failure shows its own
 * translated text; any other failure is wrapped as "Refused: …" so the frame is still translated.
 */
public final class PlayerMessages {
  private PlayerMessages() {}

  public static Component refusal(RuntimeException failure) {
    if (failure instanceof Refusal.Translated translated)
      return Component.translatable(translated.key(), translated.args().toArray());
    return Component.translatable(
        "message.factorycore.refused", String.valueOf(failure.getMessage()));
  }
}
