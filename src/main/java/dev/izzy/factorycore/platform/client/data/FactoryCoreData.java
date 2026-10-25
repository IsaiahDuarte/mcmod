package dev.izzy.factorycore.platform.client.data;

import dev.izzy.factorycore.core.storage.EnergyBankTier;
import dev.izzy.factorycore.platform.CarriedComponents;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.storage.EnergyBankContent;
import dev.izzy.factorycore.platform.storage.InterfaceContent;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
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
    generator.addProvider(event.includeServer(), new Recipes(output, event.getLookupProvider()));
    generator.addProvider(
        event.includeServer(),
        new LootTableProvider(
            output,
            Set.of(),
            List.of(
                new LootTableProvider.SubProviderEntry(
                    BlockDrops::new, LootContextParamSets.BLOCK)),
            event.getLookupProvider()));
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
              Stream.of(InterfaceContent.BLOCK.get(), TerminalContent.BLOCK.get()))
          .forEach(iron::add);
    }
  }

  /**
   * Every crafting-table recipe, one row each; the provider also writes each recipe's unlock
   * advancement, granted on first holding {@code unlock}. Stages are checked by {@code
   * RecipeProgressionTest}; tiered cells, banks and patterns come from in-world actions instead.
   */
  private static final class Recipes extends RecipeProvider {
    Recipes(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
      super(output, lookup);
    }

    @Override
    protected void buildRecipes(RecipeOutput out) {
      var redstone = RecipeCategory.REDSTONE;
      var misc = RecipeCategory.MISC;
      shaped(
          out,
          redstone,
          "network_controller",
          1,
          Items.IRON_INGOT,
          "IRI/RGR/IRI",
          'I',
          Items.IRON_INGOT,
          'R',
          Items.REDSTONE,
          'G',
          Items.GLASS);
      shaped(
          out,
          redstone,
          "network_cable",
          8,
          Items.IRON_INGOT,
          "III/RRR/III",
          'I',
          Items.IRON_INGOT,
          'R',
          Items.REDSTONE);
      shaped(
          out,
          redstone,
          "gateway",
          1,
          Items.IRON_INGOT,
          "IQI/RER/IQI",
          'I',
          Items.IRON_INGOT,
          'R',
          Items.REDSTONE,
          'Q',
          Items.QUARTZ,
          'E',
          Items.ENDER_PEARL);
      shaped(
          out,
          misc,
          "storage_drive",
          1,
          Items.HOPPER,
          "IRI/RHR/IRI",
          'I',
          Items.IRON_INGOT,
          'R',
          Items.REDSTONE,
          'H',
          Items.HOPPER);
      shaped(
          out,
          misc,
          "starter_item_cell",
          1,
          Items.IRON_INGOT,
          "IRI/RCR/IRI",
          'I',
          Items.IRON_INGOT,
          'R',
          Items.REDSTONE,
          'C',
          Items.CHEST);
      shaped(
          out,
          misc,
          "starter_fluid_cell",
          1,
          Items.GLASS,
          "GGG/GBG/GRG",
          'G',
          Items.GLASS,
          'B',
          Items.BUCKET,
          'R',
          Items.REDSTONE);
      shaped(
          out,
          misc,
          "storage_terminal",
          1,
          Items.GLASS,
          "IGI/RGR/IGI",
          'I',
          Items.IRON_INGOT,
          'G',
          Items.GLASS,
          'R',
          Items.REDSTONE);
      shaped(
          out,
          misc,
          "interface",
          1,
          Items.HOPPER,
          "CRC/RHR/CRC",
          'C',
          Items.COPPER_INGOT,
          'R',
          Items.REDSTONE,
          'H',
          Items.HOPPER);
      shaped(
          out,
          misc,
          "starter_energy_bank",
          1,
          Items.COPPER_INGOT,
          "IRI/RCR/IRI",
          'I',
          Items.IRON_INGOT,
          'R',
          Items.REDSTONE,
          'C',
          Items.COPPER_INGOT);
      shaped(
          out,
          misc,
          "fuel_generator",
          1,
          Items.FURNACE,
          "ICI/CFC/IRI",
          'I',
          Items.IRON_INGOT,
          'C',
          Items.COPPER_INGOT,
          'F',
          Items.FURNACE,
          'R',
          Items.REDSTONE);
      shaped(
          out,
          misc,
          "blank_pattern",
          4,
          Items.PAPER,
          "PRP/RIR/PRP",
          'P',
          Items.PAPER,
          'R',
          Items.REDSTONE,
          'I',
          Items.IRON_INGOT);
      shaped(
          out,
          misc,
          "expansion_module",
          1,
          Items.GOLD_INGOT,
          " G /GQG/RGR",
          'G',
          Items.GOLD_INGOT,
          'R',
          Items.REDSTONE,
          'Q',
          Items.QUARTZ);
      shaped(
          out,
          misc,
          "crafter",
          1,
          Items.GOLD_INGOT,
          "IGI/RTR/IGI",
          'I',
          Items.IRON_INGOT,
          'G',
          Items.GOLD_INGOT,
          'R',
          Items.REDSTONE,
          'T',
          Items.CRAFTING_TABLE);
      shaped(
          out,
          misc,
          "program_controller",
          1,
          Items.REPEATER,
          "IGI/RPR/IRI",
          'I',
          Items.IRON_INGOT,
          'G',
          Items.GOLD_INGOT,
          'R',
          Items.REDSTONE,
          'P',
          Items.REPEATER);
      shaped(
          out,
          misc,
          "wireless_access_point",
          1,
          Items.ENDER_PEARL,
          "IEI/RGR/III",
          'I',
          Items.IRON_INGOT,
          'E',
          Items.ENDER_PEARL,
          'R',
          Items.REDSTONE,
          'G',
          Items.GOLD_INGOT);
      shaped(
          out,
          misc,
          "wireless_terminal",
          1,
          Items.ENDER_PEARL,
          "CTC/RER/CCC",
          'C',
          Items.COPPER_INGOT,
          'T',
          item("storage_terminal"),
          'R',
          Items.REDSTONE,
          'E',
          Items.ENDER_PEARL);
      shaped(
          out,
          misc,
          "advanced_module",
          1,
          Items.DIAMOND,
          " D /DND/RDR",
          'D',
          Items.DIAMOND,
          'N',
          Items.NETHERITE_INGOT,
          'R',
          Items.REDSTONE);
      // D8: the Label Tool (starter stage: vanilla parts only).
      shaped(
          out,
          misc,
          "label_tool",
          1,
          Items.PAPER,
          " C / P / S ",
          'C',
          Items.COPPER_INGOT,
          'P',
          Items.PAPER,
          'S',
          Items.IRON_INGOT);
      shaped(
          out,
          misc,
          "wireless_link",
          1,
          item("wireless_access_point"),
          "DED/GAG/III",
          'D',
          Items.DIAMOND,
          'E',
          Items.ENDER_EYE,
          'G',
          Items.GOLD_INGOT,
          'A',
          item("wireless_access_point"),
          'I',
          Items.IRON_INGOT);
      shaped(
          out,
          misc,
          "throughput_upgrade",
          1,
          Items.DIAMOND,
          "IDI/RHR/IDI",
          'I',
          Items.IRON_INGOT,
          'D',
          Items.DIAMOND,
          'R',
          Items.REDSTONE,
          'H',
          Items.HOPPER);
      shaped(
          out,
          misc,
          "infinite_module",
          1,
          Items.NETHERITE_INGOT,
          "NNN/NSN/NNN",
          'N',
          Items.NETHERITE_INGOT,
          'S',
          Items.NETHER_STAR);
      shaped(
          out,
          misc,
          "overdrive_upgrade",
          4,
          Items.NETHER_STAR,
          "NDN/DSD/NDN",
          'N',
          Items.NETHERITE_INGOT,
          'D',
          Items.DIAMOND,
          'S',
          Items.NETHER_STAR);
      ShapelessRecipeBuilder.shapeless(misc, item("factory_guide"))
          .requires(Items.BOOK)
          .requires(Items.REDSTONE)
          .unlockedBy("has_item", has(Items.REDSTONE))
          .save(out);
    }

    /** Rows are {@code /}-separated; {@code keys} alternate symbol and ingredient. */
    private static void shaped(
        RecipeOutput out,
        RecipeCategory category,
        String result,
        int count,
        ItemLike unlock,
        String rows,
        Object... keys) {
      var recipe = ShapedRecipeBuilder.shaped(category, item(result), count);
      for (var row : rows.split("/")) recipe.pattern(row);
      for (int i = 0; i < keys.length; i += 2)
        recipe.define((Character) keys[i], (ItemLike) keys[i + 1]);
      recipe.unlockedBy("has_item", has(unlock)).save(out);
    }

    private static Item item(String path) {
      var item =
          BuiltInRegistries.ITEM.get(
              ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, path));
      if (item == Items.AIR) throw new IllegalArgumentException("Unregistered item " + path);
      return item;
    }
  }

  /**
   * Block loot from each block's own drop policy: leased devices ({@link
   * NetworkedBlock#dropsLeasedItem}) get no table; {@link CarriedComponents} blocks drop themselves
   * with those components copied from the block entity (always, so explosions never lose state);
   * everything else drops itself when it survives the explosion.
   */
  private static final class BlockDrops extends BlockLootSubProvider {
    BlockDrops(HolderLookup.Provider lookup) {
      super(Set.of(), FeatureFlags.REGISTRY.allFlags(), lookup);
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
      return blocks().stream()
          .filter(
              block -> !(block instanceof NetworkedBlock networked && networked.dropsLeasedItem()))
          .toList();
    }

    @Override
    protected void generate() {
      for (var block : getKnownBlocks()) {
        if (!(block instanceof CarriedComponents carried)) {
          dropSelf(block);
          continue;
        }
        var copy =
            CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY);
        for (var component : carried.carriedComponents()) copy.include(component);
        add(
            block,
            LootTable.lootTable()
                .withPool(
                    LootPool.lootPool()
                        .setRolls(ConstantValue.exactly(1))
                        .add(LootItem.lootTableItem(block).apply(copy))));
      }
    }
  }
}
