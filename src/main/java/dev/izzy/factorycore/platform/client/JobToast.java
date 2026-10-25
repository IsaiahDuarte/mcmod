package dev.izzy.factorycore.platform.client;

import dev.izzy.factorycore.platform.crafting.JobNotice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A5: "Crafting job finished" as a toast while a screen is open, so it does not cover the chat of a
 * player at a terminal; with no screen open the same text goes to chat as before. The token is the
 * message text, so a repeat of the same notice refreshes rather than stacks.
 */
public final class JobToast implements Toast {
  private static final ResourceLocation BACKGROUND =
      ResourceLocation.withDefaultNamespace("toast/recipe");
  private static final long SHOWN_MILLIS = 5000;

  private final ItemStack icon;
  private final Component title, line;

  private JobToast(ItemStack icon, Component title, Component line) {
    this.icon = icon;
    this.title = title;
    this.line = line;
  }

  /** Main-thread handler for {@link JobNotice}. */
  static void receive(JobNotice notice) {
    var minecraft = Minecraft.getInstance();
    if (minecraft.player == null) return;
    var location = ResourceLocation.tryParse(notice.icon());
    var item =
        location != null && BuiltInRegistries.ITEM.containsKey(location)
            ? BuiltInRegistries.ITEM.get(location)
            : Items.AIR;
    var stack = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    Component name = stack.isEmpty() ? Component.literal(notice.icon()) : stack.getHoverName();
    String key =
        notice.cancelled()
            ? "message.factorycore.job.cancelled"
            : "message.factorycore.job.finished";
    var message = Component.translatable(key, notice.amount(), name);
    if (minecraft.screen == null) {
      minecraft.player.displayClientMessage(message, false);
      return;
    }
    minecraft
        .getToasts()
        .addToast(
            new JobToast(
                stack,
                Component.translatable(key + ".title"),
                Component.translatable("message.factorycore.job.toast", notice.amount(), name)));
  }

  /** The toast's text, for verification and narration. */
  public Component line() {
    return line;
  }

  @Override
  public Object getToken() {
    return title.getString() + " " + line.getString();
  }

  @Override
  public Visibility render(GuiGraphics graphics, ToastComponent toasts, long shownFor) {
    graphics.blitSprite(BACKGROUND, 0, 0, width(), height());
    var font = toasts.getMinecraft().font;
    graphics.drawString(font, title, 30, 7, 0xff500050, false);
    graphics.drawString(
        font, font.plainSubstrByWidth(line.getString(), 124), 30, 18, 0xff000000, false);
    if (!icon.isEmpty()) graphics.renderFakeItem(icon, 8, 8);
    return shownFor >= SHOWN_MILLIS * toasts.getNotificationDisplayTimeMultiplier()
        ? Visibility.HIDE
        : Visibility.SHOW;
  }
}
