package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;

/** C1: Signal-mode entity and screen types of the unified Interface (ADR 0059). */
public final class SignalContent {
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SignalInterfaceEntity>>
      ENTITY =
          FactoryRegistries.entity(
              "signal_interface", SignalInterfaceEntity::new, InterfaceContent.BLOCK);

  public static final DeferredHolder<MenuType<?>, MenuType<SignalMenu>> MENU =
      FactoryRegistries.menu("signal_settings", SignalMenu::new);

  private SignalContent() {}

  public static void register(IEventBus bus) {
    bus.addListener(SignalPackets::register);
    NeoForge.EVENT_BUS.addListener(WorldSignals::postTick);
  }
}
