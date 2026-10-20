package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Transfer Recovery screen registration (ADR 0055). */
public final class RecoveryContent {
  public static final DeferredHolder<MenuType<?>, MenuType<RecoveryMenu>> MENU =
      FactoryRegistries.menu("transfer_recovery", RecoveryMenu::new);

  private RecoveryContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(RecoveryPackets::register);
  }
}
