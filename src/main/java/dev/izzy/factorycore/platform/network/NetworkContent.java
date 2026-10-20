package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
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
  public static final DeferredBlock<NetworkCableBlock> CABLE =
      BLOCKS.registerBlock("network_cable", NetworkCableBlock::new, properties().noOcclusion());
  public static final DeferredItem<LeasedBlockItem> CONTROLLER_ITEM =
      ITEMS.register(
          "network_controller",
          () -> new LeasedBlockItem(CONTROLLER.get(), LeasedBlockItem.Tooltip.lines()));
  public static final DeferredItem<LeasedBlockItem> GATEWAY_ITEM =
      ITEMS.register(
          "gateway", () -> new LeasedBlockItem(GATEWAY.get(), LeasedBlockItem.Tooltip.lines()));
  public static final DeferredItem<?> CABLE_ITEM = ITEMS.registerSimpleBlockItem(CABLE);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkNodeEntity>>
      NODE_ENTITY =
          ENTITIES.register(
              "network_node",
              () ->
                  BlockEntityType.Builder.of(
                          NetworkNodeEntity::new, CONTROLLER.get(), GATEWAY.get())
                      .build(null));

  private static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, FactoryCoreMod.MOD_ID);
  public static final DeferredHolder<MenuType<?>, MenuType<GatewayPolicyMenu>> POLICY_MENU =
      MENUS.register("gateway_policy", () -> IMenuTypeExtension.create(GatewayPolicyMenu::new));
  public static final DeferredHolder<MenuType<?>, MenuType<NetworkAccessMenu>> ACCESS_MENU =
      MENUS.register("network_access", () -> IMenuTypeExtension.create(NetworkAccessMenu::new));

  private NetworkContent() {}

  private static BlockBehaviour.Properties properties() {
    return DeviceProperties.machine();
  }

  public static void register(IEventBus bus) {
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    MENUS.register(bus);
    bus.addListener(AccessPackets::register);
  }
}
