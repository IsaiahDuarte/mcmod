package dev.izzy.factorycore.platform.client.data;

import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceContent;
import dev.izzy.factorycore.platform.storage.ConnectorContent;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Resources derived from the registry instead of written by hand, generated into {@code
 * src/generated/resources} by {@code runData} and kept fresh by {@code checkGeneratedResources}.
 * Hand-written art (blockstates, block models, textures, the cable item's geometry) stays in {@code
 * src/main/resources}; a file must never exist in both trees.
 */
@EventBusSubscriber(modid = FactoryCoreMod.MOD_ID, value = Dist.CLIENT)
public final class FactoryCoreData {
  /** Items whose model is hand-authored geometry rather than a block parent or flat icon. */
  private static final Set<String> CUSTOM_ITEM_MODELS = Set.of("network_cable");

  private FactoryCoreData() {}

  @SubscribeEvent
  public static void gather(GatherDataEvent event) {
    var generator = event.getGenerator();
    var output = generator.getPackOutput();
    var files = event.getExistingFileHelper();
    generator.addProvider(event.includeClient(), new ItemModels(output, files));
    generator.addProvider(
        event.includeServer(), new BlockTagSets(output, event.getLookupProvider(), files));
  }

  private static List<Block> blocks() {
    var blocks = new ArrayList<Block>();
    for (var id : BuiltInRegistries.BLOCK.keySet())
      if (id.getNamespace().equals(FactoryCoreMod.MOD_ID))
        blocks.add(BuiltInRegistries.BLOCK.get(id));
    return blocks;
  }

  /** Block items inherit their block model; every other item is a flat {@code item/<id>} icon. */
  private static final class ItemModels extends ItemModelProvider {
    ItemModels(PackOutput output, ExistingFileHelper files) {
      super(output, FactoryCoreMod.MOD_ID, files);
    }

    @Override
    protected void registerModels() {
      for (var id : BuiltInRegistries.ITEM.keySet()) {
        if (!id.getNamespace().equals(FactoryCoreMod.MOD_ID)
            || CUSTOM_ITEM_MODELS.contains(id.getPath())) continue;
        var item = BuiltInRegistries.ITEM.get(id);
        if (item instanceof BlockItem)
          withExistingParent(id.getPath(), modLoc("block/" + id.getPath()));
        else basicItem(item);
      }
    }
  }

  /**
   * Every device needs a pickaxe (all use {@code requiresCorrectToolForDrops}). The iron-tier set
   * is a balance choice kept exactly as shipped; it is the only hand-maintained list here.
   */
  private static final class BlockTagSets extends BlockTagsProvider {
    BlockTagSets(
        PackOutput output,
        CompletableFuture<HolderLookup.Provider> lookup,
        ExistingFileHelper files) {
      super(output, lookup, FactoryCoreMod.MOD_ID, files);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookup) {
      var pickaxe = tag(BlockTags.MINEABLE_WITH_PICKAXE);
      for (var block : blocks())
        if (block.defaultBlockState().requiresCorrectToolForDrops()) pickaxe.add(block);
      var iron = tag(BlockTags.NEEDS_IRON_TOOL);
      Stream.concat(
              Stream.of(EnergyBankTier.values()).map(EnergyBankContent::block),
              Stream.of(
                  ConnectorContent.BLOCK.get(),
                  BufferedInterfaceContent.BLOCK.get(),
                  TerminalContent.BLOCK.get()))
          .forEach(iron::add);
    }
  }
}
