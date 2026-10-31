package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Q79: "Items stored" and "Items taken" player statistics. They count items the network's screens
 * move out of and into a player's inventory or cursor ({@link WorldPlayerInventories}), whatever
 * the other end (storage or a Buffer), including a move's return when it could not finish.
 */
public final class StorageStats {
  private static final DeferredRegister<ResourceLocation> STATS =
      DeferredRegister.create(Registries.CUSTOM_STAT, FactoryCoreMod.MOD_ID);

  public static final DeferredHolder<ResourceLocation, ResourceLocation> ITEMS_STORED =
      stat("items_stored");
  public static final DeferredHolder<ResourceLocation, ResourceLocation> ITEMS_TAKEN =
      stat("items_taken");

  private StorageStats() {}

  private static DeferredHolder<ResourceLocation, ResourceLocation> stat(String name) {
    var id = ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, name);
    return STATS.register(name, () -> id);
  }

  public static void register(IEventBus bus) {
    STATS.register(bus);
  }

  /** Adds {@code amount} (capped to an int) to the player's {@code stat}. */
  static void award(
      ServerPlayer player, DeferredHolder<ResourceLocation, ResourceLocation> stat, long amount) {
    if (amount > 0)
      player.awardStat(Stats.CUSTOM.get(stat.get()), (int) Math.min(amount, Integer.MAX_VALUE));
  }
}
