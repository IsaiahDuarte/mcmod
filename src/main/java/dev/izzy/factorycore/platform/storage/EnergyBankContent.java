package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import java.util.EnumMap;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class EnergyBankContent {
  private static final EnumMap<EnergyBankTier, DeferredBlock<EnergyBankBlock>> BANKS =
      new EnumMap<>(EnergyBankTier.class);
  private static final EnumMap<EnergyBankTier, DeferredItem<LeasedBlockItem>> BANK_ITEMS =
      new EnumMap<>(EnergyBankTier.class);

  static {
    for (var tier : EnergyBankTier.values()) {
      var name = tier.name().toLowerCase(Locale.ROOT) + "_energy_bank";
      var block =
          FactoryRegistries.BLOCKS.registerBlock(
              name, p -> new EnergyBankBlock(p, tier), DeviceProperties.machine());
      BANKS.put(tier, block);
      BANK_ITEMS.put(
          tier,
          FactoryRegistries.ITEMS.register(
              name,
              () ->
                  new LeasedBlockItem(
                      block.get(),
                      ((LeasedBlockItem.Tooltip)
                              (stack, lines) ->
                                  lines.add(
                                      Component.translatable(
                                          "tooltip.factorycore.bank",
                                          tier.capacity(),
                                          tier.rate())))
                          .then(
                              LeasedBlockItem.Tooltip.lines("tooltip.factorycore.bank_upgrade")))));
    }
  }

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyBankEntity>> ENTITY =
      FactoryRegistries.ENTITIES.register(
          "energy_bank",
          () ->
              BlockEntityType.Builder.of(
                      EnergyBankEntity::new,
                      block(EnergyBankTier.STARTER),
                      block(EnergyBankTier.EXPANDED),
                      block(EnergyBankTier.ADVANCED))
                  .build(null));

  private EnergyBankContent() {}

  public static EnergyBankBlock block(EnergyBankTier tier) {
    return BANKS.get(tier).get();
  }

  public static Item item(EnergyBankTier tier) {
    return BANK_ITEMS.get(tier).get();
  }

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ENTITY.get(), (bank, face) -> bank);
  }

  public static void register(IEventBus bus) {
    bus.addListener(EnergyBankContent::capabilities);
  }
}
