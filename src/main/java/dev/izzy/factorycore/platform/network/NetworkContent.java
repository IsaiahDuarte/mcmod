package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.DeviceProperties;
import dev.izzy.factorycore.platform.FactoryRegistries;
import dev.izzy.factorycore.platform.LeasedBlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
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

  /** Q31: connects like a cable only while it has a redstone signal. */
  public static final DeferredBlock<RelayBlock> RELAY =
      FactoryRegistries.BLOCKS.registerBlock("relay", RelayBlock::new, properties());

  public static final DeferredItem<?> RELAY_ITEM =
      FactoryRegistries.ITEMS.registerSimpleBlockItem(RELAY);

  public static final DeferredItem<?> CABLE_ITEM =
      FactoryRegistries.ITEMS.register(
          "network_cable", () -> new CableItem(CABLE.get(), new Item.Properties()));

  /** Q30: the block a facade shows. */
  public static final DeferredHolder<
          net.minecraft.core.component.DataComponentType<?>,
          net.minecraft.core.component.DataComponentType<
              net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>>
      FACADE_BLOCK =
          FactoryRegistries.COMPONENTS.registerComponentType(
              "facade_block",
              builder ->
                  builder
                      .persistent(
                          net.minecraft.core.registries.BuiltInRegistries.BLOCK.holderByNameCodec())
                      .networkSynchronized(
                          net.minecraft.network.codec.ByteBufCodecs.holderRegistry(
                              net.minecraft.core.registries.Registries.BLOCK)));

  public static final DeferredItem<FacadeItem> FACADE_ITEM =
      FactoryRegistries.ITEMS.register("facade", () -> new FacadeItem(new Item.Properties()));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FacadeEntity>>
      FACADE_ENTITY = FactoryRegistries.entity("cable_facade", FacadeEntity::new, CABLE);

  public static final DeferredHolder<
          RecipeSerializer<?>, SimpleCraftingRecipeSerializer<FacadeRecipe>>
      FACADE_RECIPE =
          FactoryRegistries.RECIPE_SERIALIZERS.register(
              "facade", () -> new SimpleCraftingRecipeSerializer<>(FacadeRecipe::new));

  /** Q29: recolouring cables in a crafting grid. */
  public static final DeferredHolder<
          RecipeSerializer<?>, SimpleCraftingRecipeSerializer<CableDyeRecipe>>
      CABLE_DYE =
          FactoryRegistries.RECIPE_SERIALIZERS.register(
              "cable_dye", () -> new SimpleCraftingRecipeSerializer<>(CableDyeRecipe::new));

  public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NetworkNodeEntity>>
      NODE_ENTITY =
          FactoryRegistries.entity("network_node", NetworkNodeEntity::new, CONTROLLER, GATEWAY);

  public static final DeferredHolder<MenuType<?>, MenuType<GatewayPolicyMenu>> POLICY_MENU =
      FactoryRegistries.menu("gateway_policy", GatewayPolicyMenu::new);
  public static final DeferredHolder<MenuType<?>, MenuType<NetworkAccessMenu>> ACCESS_MENU =
      FactoryRegistries.menu("network_access", NetworkAccessMenu::new);

  /** H10: the network overview a Controller opens. */
  public static final DeferredHolder<MenuType<?>, MenuType<OverviewMenu>> OVERVIEW_MENU =
      FactoryRegistries.menu("network_overview", OverviewMenu::new);

  private NetworkContent() {}

  private static BlockBehaviour.Properties properties() {
    return DeviceProperties.machine();
  }

  public static void register(IEventBus bus) {
    bus.addListener(AccessPackets::register);
    bus.addListener(OverviewPackets::register);
  }
}
