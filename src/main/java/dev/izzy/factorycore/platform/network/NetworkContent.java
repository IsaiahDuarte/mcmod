package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

public final class NetworkContent {
  public static final DeferredBlock<NetworkNodeBlock> CONTROLLER =
      FactoryRegistries.BLOCKS.registerBlock(
          "network_controller",
          p -> new NetworkNodeBlock(p, NodeRegistry.Kind.CONTROLLER),
          properties());
  public static final DeferredBlock<NetworkNodeBlock> GATEWAY =
      FactoryRegistries.BLOCKS.registerBlock(
          "gateway", p -> new NetworkNodeBlock(p, NodeRegistry.Kind.GATEWAY), properties());
  public static final DeferredBlock<NetworkCableBlock> CABLE =
      FactoryRegistries.BLOCKS.registerBlock(
          "network_cable", NetworkCableBlock::new, properties().noOcclusion());
  public static final DeferredItem<LeasedBlockItem> CONTROLLER_ITEM =
      FactoryRegistries.ITEMS.register(
          "network_controller",
          () -> new LeasedBlockItem(CONTROLLER.get(), LeasedBlockItem.Tooltip.lines()));
  public static final DeferredItem<LeasedBlockItem> GATEWAY_ITEM =
      FactoryRegistries.ITEMS.register(
          "gateway", () -> new LeasedBlockItem(GATEWAY.get(), LeasedBlockItem.Tooltip.lines()));
  public static final DeferredItem<?> CABLE_ITEM =
      FactoryRegistries.ITEMS.registerSimpleBlockItem(CABLE);
  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkNodeEntity>>
      NODE_ENTITY =
          FactoryRegistries.entity("network_node", NetworkNodeEntity::new, CONTROLLER, GATEWAY);

  public static final DeferredHolder<MenuType<?>, MenuType<GatewayPolicyMenu>> POLICY_MENU =
      FactoryRegistries.menu("gateway_policy", GatewayPolicyMenu::new);
  public static final DeferredHolder<MenuType<?>, MenuType<NetworkAccessMenu>> ACCESS_MENU =
      FactoryRegistries.menu("network_access", NetworkAccessMenu::new);

  private NetworkContent() {}

  private static BlockBehaviour.Properties properties() {
    return DeviceProperties.machine();
  }

  public static void register(IEventBus bus) {
    bus.addListener(AccessPackets::register);
  }
}
