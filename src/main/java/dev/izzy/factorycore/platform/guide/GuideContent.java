package dev.izzy.factorycore.platform.guide;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.FactoryRegistries;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Factory Guide: a localized, read-only book that walks from a first network to wireless links.
 * Each player receives one copy on first login; more can be crafted from a book and redstone.
 */
public final class GuideContent {
  /** Page count; page {@code n} text is the {@code guide.factorycore.page.n} lang key. */
  public static final int PAGES = 16;

  private static final String GIVEN = FactoryCoreMod.MOD_ID + ":guide_given";
  public static final DeferredItem<GuideItem> GUIDE =
      FactoryRegistries.ITEMS.register("factory_guide", GuideItem::new);

  private GuideContent() {}

  public static String pageKey(int page) {
    return "guide.factorycore.page." + page;
  }

  public static List<Component> pages() {
    var pages = new ArrayList<Component>(PAGES);
    for (int page = 0; page < PAGES; page++) pages.add(Component.translatable(pageKey(page)));
    return pages;
  }

  /** Gives the guide once per player; the marker survives death. Returns true when given. */
  public static boolean welcome(ServerPlayer player) {
    var persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
    if (persisted.getBoolean(GIVEN)) return false;
    persisted.putBoolean(GIVEN, true);
    player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    var guide = new ItemStack(GUIDE.get());
    if (!player.getInventory().add(guide)) player.drop(guide, false);
    return true;
  }

  private static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
    // Fixture servers set this so test players start with empty inventories.
    if (Boolean.getBoolean("factorycore.noWelcomeGuide")) return;
    if (event.getEntity() instanceof ServerPlayer player) welcome(player);
  }

  public static void register(IEventBus bus) {
    NeoForge.EVENT_BUS.addListener(GuideContent::loggedIn);
  }
}
