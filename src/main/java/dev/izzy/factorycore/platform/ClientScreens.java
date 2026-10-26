package dev.izzy.factorycore.platform;

import java.util.Objects;
import net.minecraft.world.InteractionHand;

/**
 * Screens that items open on the client without a menu. Common item code calls {@link #opener()};
 * the client installs the implementation at setup, so item classes never name a client class and a
 * dedicated server never loads one. Until installed (a dedicated server) the opener does nothing.
 */
public final class ClientScreens {
  /** Opens one item screen; called on the client thread only. */
  public interface Opener {
    void guide();

    void labelTool(InteractionHand hand, String label, boolean copied);
  }

  private static final Opener NONE =
      new Opener() {
        @Override
        public void guide() {}

        @Override
        public void labelTool(InteractionHand hand, String label, boolean copied) {}
      };

  private static volatile Opener opener = NONE;

  private ClientScreens() {}

  public static Opener opener() {
    return opener;
  }

  /** Called once by the client during setup. */
  public static void install(Opener installed) {
    opener = Objects.requireNonNull(installed);
  }
}
