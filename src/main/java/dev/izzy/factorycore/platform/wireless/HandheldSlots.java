package dev.izzy.factorycore.platform.wireless;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Q110 (QD1): places beyond the inventory where a player may carry a Wireless Terminal, such as
 * Curios accessory slots. An optional integration installs a {@link Source} when its mod is loaded;
 * without one the open key searches the inventory only. Server thread only.
 */
public final class HandheldSlots {
  private HandheldSlots() {}

  /** Finds the stacks {@code wanted} accepts, each as a reader of that same slot later. */
  @FunctionalInterface
  public interface Source {
    List<Function<ServerPlayer, ItemStack>> find(ServerPlayer player, Predicate<ItemStack> wanted);
  }

  private static volatile Source source;

  public static void install(Source installed) {
    source = installed;
  }

  static List<Function<ServerPlayer, ItemStack>> find(
      ServerPlayer player, Predicate<ItemStack> wanted) {
    var current = source;
    return current == null ? List.of() : current.find(player, wanted);
  }
}
