package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.EnumMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class StorageContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<CreativeModeTab> TABS =
      DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FactoryCoreMod.MOD_ID);
  public static final DeferredBlock<StorageDriveBlock> DRIVE =
      BLOCKS.registerBlock(
          "storage_drive",
          StorageDriveBlock::new,
          BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops());
  public static final DeferredItem<?> DRIVE_ITEM = ITEMS.registerSimpleBlockItem(DRIVE);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StorageDriveEntity>>
      DRIVE_ENTITY =
          ENTITIES.register(
              "storage_drive",
              () -> BlockEntityType.Builder.of(StorageDriveEntity::new, DRIVE.get()).build(null));
  private static final EnumMap<CellTier, DeferredItem<CellItem>> CELLS =
      new EnumMap<>(CellTier.class);
  public static final DeferredItem<CellUpgradeItem> EXPANSION =
      ITEMS.register("expansion_module", () -> new CellUpgradeItem(1));
  public static final DeferredItem<CellUpgradeItem> ADVANCED =
      ITEMS.register("advanced_module", () -> new CellUpgradeItem(2));
  public static final DeferredItem<CellUpgradeItem> INFINITE =
      ITEMS.register("infinite_module", () -> new CellUpgradeItem(3));
  public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
      TABS.register(
          "factorycore",
          () ->
              CreativeModeTab.builder()
                  .title(Component.translatable("itemGroup.factorycore"))
                  .icon(() -> DRIVE_ITEM.get().getDefaultInstance())
                  .displayItems(
                      (parameters, output) -> {
                        output.accept(DRIVE_ITEM.get());
                        for (var tier : CellTier.values()) output.accept(cell(tier));
                        output.accept(EXPANSION.get());
                        output.accept(ADVANCED.get());
                        output.accept(INFINITE.get());
                      })
                  .build());

  static {
    for (var tier : CellTier.values()) {
      CELLS.put(
          tier,
          ITEMS.register(
              tier.name().toLowerCase(java.util.Locale.ROOT) + "_cell", () -> new CellItem(tier)));
    }
  }

  private StorageContent() {}

  public static Item cell(CellTier tier) {
    return CELLS.get(tier).get();
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    TABS.register(bus);
  }
}
