package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.storage.StorageDriveBlock;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class NetworkContent {
  private static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(FactoryCoreMod.MOD_ID);
  private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FactoryCoreMod.MOD_ID);
  public static final DeferredBlock<NetworkNodeBlock> CONTROLLER =
      BLOCKS.registerBlock(
          "network_controller",
          p -> new NetworkNodeBlock(p, NodeRegistry.Kind.CONTROLLER),
          properties());
  public static final DeferredBlock<NetworkNodeBlock> GATEWAY =
      BLOCKS.registerBlock(
          "gateway", p -> new NetworkNodeBlock(p, NodeRegistry.Kind.GATEWAY), properties());
  public static final DeferredBlock<Block> CABLE =
      BLOCKS.registerBlock("network_cable", Block::new, properties());
  public static final DeferredItem<NetworkNodeItem> CONTROLLER_ITEM =
      ITEMS.register("network_controller", () -> new NetworkNodeItem(CONTROLLER.get()));
  public static final DeferredItem<NetworkNodeItem> GATEWAY_ITEM =
      ITEMS.register("gateway", () -> new NetworkNodeItem(GATEWAY.get()));
  public static final DeferredItem<?> CABLE_ITEM = ITEMS.registerSimpleBlockItem(CABLE);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkNodeEntity>>
      NODE_ENTITY =
          ENTITIES.register(
              "network_node",
              () ->
                  BlockEntityType.Builder.of(
                          NetworkNodeEntity::new, CONTROLLER.get(), GATEWAY.get())
                      .build(null));

  /** Local port eligibility only; callers must separately validate load, identity and topology. */
  public static boolean connects(BlockState state, Direction face) {
    java.util.Objects.requireNonNull(face);
    if (state.getBlock() instanceof NetworkNodeBlock node) return node.connects(state, face);
    return state.is(CABLE.get()) || state.getBlock() instanceof StorageDriveBlock;
  }

  private NetworkContent() {}

  private static BlockBehaviour.Properties properties() {
    return BlockBehaviour.Properties.of().strength(3.5f).requiresCorrectToolForDrops();
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
  }
}
