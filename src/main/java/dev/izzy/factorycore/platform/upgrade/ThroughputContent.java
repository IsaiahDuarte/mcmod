package dev.izzy.factorycore.platform.upgrade;

import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Paid throughput upgrades (ADR 0054). The Throughput Upgrade (Advanced) and Overdrive Upgrade
 * (Endgame) are installed in place on a Storage Connector, Machine Interface, Buffered Interface or
 * (Overdrive only) a Wireless Link Endpoint.
 */
public final class ThroughputContent {
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  public static final DeferredItem<ThroughputUpgradeItem> THROUGHPUT =
      ITEMS.register(
          "throughput_upgrade", () -> new ThroughputUpgradeItem(ThroughputTier.ADVANCED));
  public static final DeferredItem<ThroughputUpgradeItem> OVERDRIVE =
      ITEMS.register("overdrive_upgrade", () -> new ThroughputUpgradeItem(ThroughputTier.ENDGAME));

  private ThroughputContent() {}

  public static Item item(ThroughputTier tier) {
    return switch (tier) {
      case BASE -> throw new IllegalArgumentException("The base tier has no upgrade item");
      case ADVANCED -> THROUGHPUT.get();
      case ENDGAME -> OVERDRIVE.get();
    };
  }

  /**
   * The paid items an installed tier represents, for devices that return upgrades separately when
   * broken. A link reaches ENDGAME with one Overdrive; a port or buffer needs both items in order.
   */
  public static List<ItemStack> installed(ThroughputTier tier, boolean link) {
    var items = new ArrayList<ItemStack>();
    if (link) {
      if (tier == ThroughputTier.ENDGAME) items.add(new ItemStack(OVERDRIVE.get()));
      return items;
    }
    for (var step : ThroughputTier.values())
      if (step != ThroughputTier.BASE && step.ordinal() <= tier.ordinal())
        items.add(new ItemStack(item(step)));
    return items;
  }

  /** Translatable tier name: no upgrade, Throughput Upgrade or Overdrive Upgrade. */
  public static Component name(ThroughputTier tier) {
    return Component.translatable(
        "throughput.factorycore.tier." + tier.name().toLowerCase(Locale.ROOT));
  }

  public static void register(IEventBus bus) {
    ITEMS.register(bus);
  }
}
