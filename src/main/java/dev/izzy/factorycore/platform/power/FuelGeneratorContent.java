package dev.izzy.factorycore.platform.power;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.izzy.factorycore.core.storage.FuelGenerator;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
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

public final class FuelGeneratorContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.DataComponents COMPONENTS =
      DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, FactoryCoreMod.MOD_ID);

  private record RawState(long energy, long burnRemaining, long burnTotal) {}

  /** Invalid saved values fail decoding instead of being clamped or reset. */
  static final Codec<FuelGenerator.Snapshot> SNAPSHOT_CODEC =
      RecordCodecBuilder.<RawState>create(
              instance ->
                  instance
                      .group(
                          Codec.LONG.fieldOf("energy").forGetter(RawState::energy),
                          Codec.LONG.fieldOf("burn_remaining").forGetter(RawState::burnRemaining),
                          Codec.LONG.fieldOf("burn_total").forGetter(RawState::burnTotal))
                      .apply(instance, RawState::new))
          .comapFlatMap(
              raw -> {
                try {
                  return DataResult.success(
                      new FuelGenerator.Snapshot(
                          raw.energy(), raw.burnRemaining(), raw.burnTotal()));
                } catch (IllegalArgumentException invalid) {
                  return DataResult.error(invalid::getMessage);
                }
              },
              value -> new RawState(value.energy(), value.burnRemaining(), value.burnTotal()));

  /** Stored FE and the partially burned fuel item, carried by a broken generator's drop. */
  public static final DeferredHolder<
          DataComponentType<?>, DataComponentType<FuelGenerator.Snapshot>>
      STATE =
          COMPONENTS.registerComponentType(
              "generator_state",
              builder ->
                  builder
                      .persistent(SNAPSHOT_CODEC)
                      .networkSynchronized(
                          StreamCodec.composite(
                              ByteBufCodecs.VAR_LONG,
                              FuelGenerator.Snapshot::energy,
                              ByteBufCodecs.VAR_LONG,
                              FuelGenerator.Snapshot::burnRemaining,
                              ByteBufCodecs.VAR_LONG,
                              FuelGenerator.Snapshot::burnTotal,
                              FuelGenerator.Snapshot::new)));

  /** Exact unsupported-schema block-entity data, carried unchanged by a broken generator. */
  public static final DeferredHolder<
          DataComponentType<?>, DataComponentType<net.minecraft.world.item.component.CustomData>>
      RETAINED =
          COMPONENTS.registerComponentType(
              "generator_retained",
              builder -> builder.persistent(net.minecraft.world.item.component.CustomData.CODEC));

  public static final DeferredBlock<FuelGeneratorBlock> BLOCK =
      BLOCKS.registerBlock(
          "fuel_generator",
          FuelGeneratorBlock::new,
          BlockBehaviour.Properties.of()
              .strength(3.5f)
              .requiresCorrectToolForDrops()
              .sound(SoundType.METAL)
              .lightLevel(state -> state.getValue(FuelGeneratorBlock.LIT) ? 13 : 0));

  public static final DeferredItem<FuelGeneratorItem> ITEM =
      ITEMS.register("fuel_generator", () -> new FuelGeneratorItem(BLOCK.get()));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FuelGeneratorEntity>>
      ENTITY =
          ENTITIES.register(
              "fuel_generator",
              () -> BlockEntityType.Builder.of(FuelGeneratorEntity::new, BLOCK.get()).build(null));

  private FuelGeneratorContent() {}

  private static void capabilities(RegisterCapabilitiesEvent event) {
    event.registerBlockEntity(
        Capabilities.EnergyStorage.BLOCK, ENTITY.get(), (generator, face) -> generator.energy());
    event.registerBlockEntity(
        Capabilities.ItemHandler.BLOCK,
        ENTITY.get(),
        (generator, face) -> generator.items(face == null ? Direction.UP : face));
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    COMPONENTS.register(bus);
    bus.addListener(FuelGeneratorContent::capabilities);
  }
}
