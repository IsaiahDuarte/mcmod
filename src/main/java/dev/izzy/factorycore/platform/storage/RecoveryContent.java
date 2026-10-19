package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Transfer Recovery screen registration (ADR 0055). */
public final class RecoveryContent {
  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  public static final DeferredHolder<MenuType<?>, MenuType<RecoveryMenu>> MENU =
      MENUS.register("transfer_recovery", () -> IMenuTypeExtension.create(RecoveryMenu::new));

  private RecoveryContent() {}

  public static void register(IEventBus bus) {
    MENUS.register(bus);
    bus.addListener(RecoveryPackets::register);
  }
}
