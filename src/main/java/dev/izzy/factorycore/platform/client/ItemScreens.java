package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.ClientScreens;
import net.minecraft.world.InteractionHand;

/** The client's {@link ClientScreens.Opener}: the Guide and Label Tool screens. */
final class ItemScreens implements ClientScreens.Opener {
  @Override
  public void guide() {
    GuideScreen.open();
  }

  @Override
  public void labelTool(InteractionHand hand, String label, boolean copied) {
    LabelToolScreen.open(hand, label, copied);
  }
}
