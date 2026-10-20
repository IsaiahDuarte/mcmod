package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.crafting.CraftingContent;
import dev.izzy.factorycore.platform.guide.GuideContent;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.power.FuelGeneratorContent;
import dev.izzy.factorycore.platform.program.ProgramContent;
import dev.izzy.factorycore.platform.upgrade.ThroughputContent;
import dev.izzy.factorycore.platform.wireless.WirelessContent;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class StorageContent {
  public static final DeferredBlock<StorageDriveBlock> DRIVE =
      FactoryRegistries.BLOCKS.registerBlock(
          "storage_drive", StorageDriveBlock::new, DeviceProperties.machine());
  public static final DeferredItem<?> DRIVE_ITEM =
      FactoryRegistries.ITEMS.registerSimpleBlockItem(DRIVE);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StorageDriveEntity>>
      DRIVE_ENTITY = FactoryRegistries.entity("storage_drive", StorageDriveEntity::new, DRIVE);
  private static final EnumMap<CellTier, DeferredItem<CellItem>> CELLS =
      new EnumMap<>(CellTier.class);
  public static final DeferredItem<CellUpgradeItem> EXPANSION =
      FactoryRegistries.ITEMS.register("expansion_module", () -> new CellUpgradeItem(1));
  public static final DeferredItem<CellUpgradeItem> ADVANCED =
      FactoryRegistries.ITEMS.register("advanced_module", () -> new CellUpgradeItem(2));
  public static final DeferredItem<CellUpgradeItem> INFINITE =
      FactoryRegistries.ITEMS.register("infinite_module", () -> new CellUpgradeItem(3));

  /**
   * Items left out of the creative tab: encoded patterns are meaningless without their recipe data.
   * Every other Factory Core item is shown, curated ones first.
   */
  public static final Set<String> TAB_HIDDEN = Set.of("crafting_pattern", "processing_pattern");

  public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB =
      FactoryRegistries.TABS.register(
          "factorycore",
          () ->
              CreativeModeTab.builder()
                  .title(Component.translatable("itemGroup.factorycore"))
                  .icon(() -> DRIVE_ITEM.get().getDefaultInstance())
                  .displayItems(
                      (parameters, output) -> {
                        var shown = new LinkedHashSet<Item>();
                        shown.add(DRIVE_ITEM.get());
                        shown.add(TerminalContent.ITEM.get());
                        shown.add(BufferedInterfaceContent.ITEM.get());
                        shown.add(ConnectorContent.ITEM.get());
                        shown.add(MachineInterfaceContent.ITEM.get());
                        shown.add(NetworkContent.CONTROLLER_ITEM.get());
                        shown.add(NetworkContent.GATEWAY_ITEM.get());
                        shown.add(NetworkContent.CABLE_ITEM.get());
                        for (var tier : EnergyBankTier.values())
                          shown.add(EnergyBankContent.item(tier));
                        shown.add(FuelGeneratorContent.ITEM.get());
                        shown.add(CraftingContent.UNIT_ITEM.get());
                        shown.add(CraftingContent.INTERFACE_ITEM.get());
                        shown.add(ProgramContent.ITEM.get());
                        shown.add(WirelessContent.ACCESS_POINT_ITEM.get());
                        shown.add(WirelessContent.HANDHELD.get());
                        shown.add(WirelessContent.LINK_ITEM.get());
                        shown.add(GuideContent.GUIDE.get());
                        shown.add(CraftingContent.BLANK_PATTERN.get());
                        for (var tier : CellTier.values()) shown.add(cell(tier));
                        shown.add(EXPANSION.get());
                        shown.add(ADVANCED.get());
                        shown.add(INFINITE.get());
                        shown.add(ThroughputContent.THROUGHPUT.get());
                        shown.add(ThroughputContent.OVERDRIVE.get());
                        // Anything registered later still appears, after the curated order.
                        for (var id : BuiltInRegistries.ITEM.keySet())
                          if (id.getNamespace().equals(FactoryCoreMod.MOD_ID)
                              && !TAB_HIDDEN.contains(id.getPath()))
                            shown.add(BuiltInRegistries.ITEM.get(id));
                        shown.forEach(output::accept);
                      })
                  .build());

  static {
    for (var tier : CellTier.values()) {
      CELLS.put(
          tier,
          FactoryRegistries.ITEMS.register(
              tier.name().toLowerCase(Locale.ROOT) + "_cell", () -> new CellItem(tier)));
    }
  }

  private StorageContent() {}

  public static Item cell(CellTier tier) {
    return CELLS.get(tier).get();
  }

  public static void register(IEventBus bus) {
    // Loading this class adds its entries to FactoryRegistries before registration.
  }
}
