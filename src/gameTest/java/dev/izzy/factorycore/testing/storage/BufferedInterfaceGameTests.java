package dev.izzy.factorycore.testing.storage;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.OperationResult;
import dev.izzy.factorycore.platform.network.*;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.*;
import dev.izzy.factorycore.testing.ConnectorFixture;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class BufferedInterfaceGameTests {
  private static final BlockPos BUFFER = new BlockPos(3, 1, 1);

  private BufferedInterfaceGameTests() {}

  private static BufferedInterfaceEntity setup(GameTestHelper helper) {
    var actor = UUID.randomUUID();
    helper.setBlock(new BlockPos(1, 1, 1), NetworkContent.CONTROLLER.get());
    NetworkNodeEntity root = helper.getBlockEntity(new BlockPos(1, 1, 1));
    root.commission(actor);
    helper.setBlock(new BlockPos(2, 1, 1), NetworkContent.CABLE.get());
    helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(BUFFER);
    buffer.place(new ItemStack(BufferedInterfaceContent.ITEM.get()), actor);
    return buffer;
  }

  private static ItemStack named(int count) {
    var stack = new ItemStack(Items.IRON_INGOT, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Exact hopper buffer identity"));
    return stack;
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void realHopperFeedsComponentAwareOwnedBuffer(GameTestHelper helper) {
    var buffer = setup(helper);
    helper.setBlock(
        BUFFER.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = helper.getBlockEntity(BUFFER.above());
    hopper.setItem(0, named(16));
    var key = new PlatformResourceCodec(helper.getLevel().registryAccess()).itemKey(named(1));
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    buffer.inventory().total(key) == 16, "Hopper delivered all named items"))
        .thenExecute(
            () -> {
              helper.assertTrue(
                  hopper.getItem(0).isEmpty(), "Physical hopper is empty after actual insertion");
              for (var side : Direction.values()) {
                var handler =
                    helper
                        .getLevel()
                        .getCapability(
                            Capabilities.ItemHandler.BLOCK, helper.absolutePos(BUFFER), side);
                helper.assertTrue(
                    handler != null
                        && handler.getSlots() == 9
                        && handler.getStackInSlot(0).getCount() == 16,
                    "All faces share one nine-slot inventory");
              }
              helper.assertTrue(
                  WorldNetworks.get(helper.getLevel().getServer())
                          .membership(buffer.position())
                          .status()
                      == NetworkTopology.Status.VALID,
                  "Buffered device participates in live wired topology");
              var nbt = buffer.saveWithFullMetadata(helper.getLevel().registryAccess());
              helper.assertTrue(
                  !nbt.contains("Items")
                      && nbt.getCompound(BufferData.FIELD).getAllKeys().size() == 6,
                  "Chunk contains only reference/configuration, never a copied inventory");
            })
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void fullBufferBacksUpHopperAndResumesWithoutVoiding(GameTestHelper helper) {
    var buffer = setup(helper);
    var handler = buffer.capability();
    for (int slot = 0; slot < 9; slot++)
      handler.insertItem(slot, new ItemStack(Items.GOLD_INGOT, 64), false);
    helper.setBlock(
        BUFFER.above(),
        Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
    HopperBlockEntity hopper = helper.getBlockEntity(BUFFER.above());
    hopper.setItem(0, named(7));
    var codec = new PlatformResourceCodec(helper.getLevel().registryAccess());
    var iron = codec.itemKey(named(1));
    var gold = codec.itemKey(new ItemStack(Items.GOLD_INGOT));
    helper
        .startSequence()
        .thenIdle(24)
        .thenExecute(
            () -> {
              helper.assertTrue(
                  hopper.getItem(0).getCount() == 7 && buffer.inventory().total(gold) == 576,
                  "Full physical slots retain hopper input and existing contents");
              var removed = handler.extractItem(0, 64, false);
              helper.assertTrue(
                  removed.getCount() == 64, "Local extraction returns actual owned stack");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    buffer.inventory().total(iron) == 7, "Hopper resumes after capacity returns"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    hopper.getItem(0).isEmpty() && buffer.inventory().total(gold) == 512,
                    "Only the caller-owned removed stack left the buffer; input was not voided"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void creativePlacementConsumesAndBreakMovesOnePopulatedReference(
      GameTestHelper helper) {
    helper.setBlock(BUFFER.below(), Blocks.STONE);
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    var stack = new ItemStack(BufferedInterfaceContent.ITEM.get());
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    var hit =
        new net.minecraft.world.phys.BlockHitResult(
            net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(BUFFER.below())),
            Direction.UP,
            helper.absolutePos(BUFFER.below()),
            false);
    var context =
        new net.minecraft.world.item.context.BlockPlaceContext(
            helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit);
    var result = ((net.minecraft.world.item.BlockItem) stack.getItem()).place(context);
    helper.assertTrue(
        result.consumesAction() && stack.isEmpty(),
        "Creative placement consumes the ownership reference");
    BufferedInterfaceEntity original = helper.getBlockEntity(BUFFER);
    var oldHandle = original.handle();
    var oldPort = original.capability();
    oldPort.insertItem(0, named(23), false);
    helper.getLevel().destroyBlock(helper.absolutePos(BUFFER), true);
    var drops =
        helper
            .getLevel()
            .getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(helper.absolutePos(BUFFER)).inflate(.8),
                e -> e.getItem().is(BufferedInterfaceContent.ITEM.get()));
    helper.assertTrue(
        drops.size() == 1 && drops.getFirst().getItem().getCount() == 1,
        "Exactly one portable buffer drop");
    helper.assertTrue(
        oldPort.extractItem(0, 23, false).isEmpty(),
        "Retained capability cannot spend detached contents");
    var portable = drops.getFirst().getItem().copy();
    var reference =
        BufferData.read(portable.get(DataComponents.CUSTOM_DATA).copyTag().get(BufferData.FIELD));
    helper.assertTrue(
        reference.lease().id().equals(oldHandle.id())
            && DeviceSavedData.get(helper.getLevel().getServer()).nodes().validate(oldHandle)
                == NodeRegistry.Failure.STALE,
        "Break advances generation while retaining canonical identity");
    var moved = new BlockPos(2, 1, 2);
    helper.setBlock(moved, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity replacement = helper.getBlockEntity(moved);
    replacement.place(portable, player.getUUID());
    helper.assertTrue(
        replacement.capability().getStackInSlot(0).getCount() == 23
            && oldPort.extractItem(0, 23, false).isEmpty(),
        "Moved inventory serves only the replacement owner");
    helper.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void lifecycleReloadAndCloneKeepOneInventoryWithoutDuplicatingStock(
      GameTestHelper helper) {
    var buffer = setup(helper);
    var oldPort = buffer.capability();
    oldPort.insertItem(8, named(19), false);
    var nbt = buffer.saveWithFullMetadata(helper.getLevel().registryAccess());
    buffer.setRemoved();
    helper.assertTrue(
        oldPort.getStackInSlot(8).isEmpty(), "Unloaded capability cannot observe owned slots");
    buffer.clearRemoved();
    buffer.loadWithComponents(nbt, helper.getLevel().registryAccess());
    buffer.onLoad();
    helper.assertTrue(
        buffer.capability().getStackInSlot(8).getCount() == 19,
        "Lifecycle reload rebinds exact canonical slots");
    var copyPos = new BlockPos(3, 1, 3);
    helper.setBlock(copyPos, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity copy = helper.getBlockEntity(copyPos);
    copy.loadWithComponents(nbt, helper.getLevel().registryAccess());
    copy.onLoad();
    helper.assertTrue(
        !copy.diagnostic().isEmpty() && copy.capability() == null && buffer.capability() == null,
        "Copied placed reference quarantines both capabilities");
    var preserved =
        DeviceSavedData.get(helper.getLevel().getServer()).buffers().snapshot().stream()
            .filter(state -> state.backing().equals(buffer.handle().id()))
            .findFirst()
            .orElseThrow();
    helper.assertTrue(
        preserved.slots().get(8).count() == 19,
        "Stock is retained exactly once in canonical recovery data");
    helper.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void futureReferenceSurvivesBreakWithoutAllocatingEmptyInventory(
      GameTestHelper helper) {
    helper.setBlock(BUFFER, BufferedInterfaceContent.BLOCK.get());
    BufferedInterfaceEntity buffer = helper.getBlockEntity(BUFFER);
    var future = new CompoundTag();
    future.putInt("Schema", 77);
    future.putString("Future", "retained raw");
    var envelope = new CompoundTag();
    envelope.put(BufferData.FIELD, future.copy());
    buffer.loadWithComponents(envelope, helper.getLevel().registryAccess());
    buffer.onLoad();
    int before = DeviceSavedData.get(helper.getLevel().getServer()).buffers().snapshot().size();
    var portable = buffer.portableForBreak();
    helper.assertTrue(
        portable.get(DataComponents.CUSTOM_DATA).copyTag().get(BufferData.FIELD).equals(future),
        "Future raw reference retained");
    buffer.place(portable, UUID.randomUUID());
    helper.assertTrue(
        buffer.capability() == null
            && DeviceSavedData.get(helper.getLevel().getServer()).buffers().snapshot().size()
                == before,
        "Unsupported data does not become a new empty inventory");
    helper.succeed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void declaredForeignGroupCannotAliasOwnedBuffer(GameTestHelper helper) {
    var buffer = setup(helper);
    var connectorPos = new BlockPos(2, 1, 2);
    helper.setBlock(
        connectorPos,
        ConnectorContent.BLOCK
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.EAST));
    ConnectorEntity connector = helper.getBlockEntity(connectorPos);
    connector.place(new ItemStack(ConnectorContent.ITEM.get()));
    helper.setBlock(new BlockPos(3, 1, 2), ConnectorFixture.BLOCK.get());
    ConnectorFixture.ProviderEntity provider = helper.getBlockEntity(new BlockPos(3, 1, 2));
    provider.extendedBacking = true;
    var runtime = WorldNetworks.get(helper.getLevel().getServer());
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    runtime.membership(connector.position()).status()
                        == NetworkTopology.Status.VALID,
                    "Connector scope ready"))
        .thenExecute(
            () ->
                helper.assertTrue(
                    runtime.connectors().bind(connector).reason() == OperationResult.Reason.DENIED
                        && buffer.inventory().snapshot().slots().stream()
                            .allMatch(slot -> slot.count() == 0),
                    "Foreign group cannot acquire a second ownership path over canonical buffered slots"))
        .thenSucceed();
  }

  @GameTest(template = "empty", timeoutTicks = 200)
  public static void recipeUsesStarterMaterialsAndUnlocksWithoutExistingBufferReference(
      GameTestHelper helper) {
    var id =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            "factorycore", "buffered_interface");
    var recipe = helper.getLevel().getRecipeManager().byKey(id).orElseThrow().value();
    var costs = new java.util.HashMap<String, Integer>();
    for (var ingredient : recipe.getIngredients())
      for (var stack : ingredient.getItems()) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        helper.assertTrue(item.getNamespace().equals("minecraft"), "Only vanilla ingredients");
        costs.merge(item.getPath(), 1, Integer::sum);
      }
    helper.assertTrue(
        costs.equals(java.util.Map.of("iron_ingot", 4, "glass", 2, "redstone", 2, "hopper", 1)),
        "Exact starter buffer recipe");
    var result = recipe.getResultItem(helper.getLevel().registryAccess());
    helper.assertTrue(
        result.is(BufferedInterfaceContent.ITEM.get())
            && result.getCount() == 1
            && result.get(DataComponents.CUSTOM_DATA) == null,
        "Recipe creates one fresh unbound device, never consumes populated ownership");
    helper.assertTrue(
        helper
                .getLevel()
                .getServer()
                .getAdvancements()
                .get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "factorycore", "recipes/misc/buffered_interface"))
            != null,
        "Recipe book unlock loaded");
    helper.succeed();
  }
}
