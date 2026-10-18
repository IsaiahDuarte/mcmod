package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.guide.GuideContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;

/** Shows the Factory Guide in the vanilla read-only book screen. */
public final class GuideScreen {
  private GuideScreen() {}

  public static void open() {
    Minecraft.getInstance()
        .setScreen(new BookViewScreen(new BookViewScreen.BookAccess(GuideContent.pages())));
  }
}
