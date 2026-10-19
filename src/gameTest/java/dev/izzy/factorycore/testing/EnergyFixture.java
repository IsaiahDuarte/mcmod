package dev.izzy.factorycore.testing;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.ComponentEnergyStorage;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Test-only 5,000 FE battery item using NeoForge's component-backed item energy storage. */
public final class EnergyFixture {
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems("factorycore_tests");
  private static final DeferredRegister.DataComponents COMPONENTS =
      DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "factorycore_tests");
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY =
      COMPONENTS.registerComponentType(
          "battery_energy",
          builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
  public static final DeferredItem<Item> BATTERY =
      ITEMS.register("test_battery", () -> new Item(new Item.Properties().stacksTo(1)));

  public static final DeferredItem<Item> UNRELIABLE_BATTERY =
      ITEMS.register("unreliable_battery", () -> new Item(new Item.Properties().stacksTo(1)));

  private EnergyFixture() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerItem(
        Capabilities.EnergyStorage.ITEM,
        (stack, context) -> new ComponentEnergyStorage(stack, ENERGY.get(), 5_000),
        BATTERY.get());
    event.registerItem(
        Capabilities.EnergyStorage.ITEM,
        (stack, context) ->
            new ComponentEnergyStorage(stack, ENERGY.get(), 5_000) {
              @Override
              public int receiveEnergy(int amount, boolean simulate) {
                return super.receiveEnergy(simulate ? amount : amount / 2, simulate);
              }

              @Override
              public int extractEnergy(int amount, boolean simulate) {
                return super.extractEnergy(simulate ? amount : amount / 2, simulate);
              }
            },
        UNRELIABLE_BATTERY.get());
  }

  public static void register(IEventBus bus) {
    ITEMS.register(bus);
    COMPONENTS.register(bus);
    bus.addListener(EnergyFixture::capabilities);
  }
}
