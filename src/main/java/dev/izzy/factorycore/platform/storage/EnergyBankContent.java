package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.EnumMap;
import java.util.Locale;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class EnergyBankContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  private static final EnumMap<EnergyBankTier, DeferredBlock<EnergyBankBlock>> BANKS =
      new EnumMap<>(EnergyBankTier.class);
  private static final EnumMap<EnergyBankTier, DeferredItem<EnergyBankItem>> BANK_ITEMS =
      new EnumMap<>(EnergyBankTier.class);

  static {
    for (var tier : EnergyBankTier.values()) {
      var name = tier.name().toLowerCase(Locale.ROOT) + "_energy_bank";
      var block =
          BLOCKS.registerBlock(
              name,
              p -> new EnergyBankBlock(p, tier),
              BlockBehaviour.Properties.of()
                  .strength(3.5f)
                  .requiresCorrectToolForDrops()
                  .sound(SoundType.METAL));
      BANKS.put(tier, block);
      BANK_ITEMS.put(tier, ITEMS.register(name, () -> new EnergyBankItem(block.get())));
    }
  }

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyBankEntity>> ENTITY =
      ENTITIES.register(
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
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    bus.addListener(EnergyBankContent::capabilities);
  }
}
