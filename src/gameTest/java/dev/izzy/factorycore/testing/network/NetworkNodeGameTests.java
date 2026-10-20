package dev.izzy.factorycore.testing.network;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.NodeHandleData;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class NetworkNodeGameTests {
  private static final BlockPos FIRST = new BlockPos(1, 1, 1);
  private static final BlockPos SECOND = new BlockPos(3, 1, 1);

  private NetworkNodeGameTests() {}

  private static NetworkNodeEntity node(GameTestHelper helper, BlockPos relative) {
    return (NetworkNodeEntity) helper.getLevel().getBlockEntity(helper.absolutePos(relative));
  }

  private static DeviceSavedData authority(GameTestHelper helper) {
    return DeviceSavedData.get(helper.getLevel().getServer());
  }

  private static void place(
      GameTestHelper helper, BlockPos relative, Player player, ItemStack stack) {
    helper.setBlock(relative.below(), Blocks.STONE);
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    var floor = helper.absolutePos(relative.below());
    var hit = new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false);
    var result = stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    helper.assertTrue(result.consumesAction(), "Actual item placement succeeded");
    helper.assertTrue(stack.isEmpty(), "Physical reference consumed, including creative");
  }

  private static ItemStack breakOne(GameTestHelper helper, BlockPos relative) {
    var position = helper.absolutePos(relative);
    helper.getLevel().destroyBlock(position, true);
    var drops =
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(0.5));
    helper.assertTrue(
        drops.size() == 1 && drops.getFirst().getItem().getCount() == 1,
        "Break emits exactly one lease casing");
    var stack = drops.getFirst().getItem().copy();
    drops.getFirst().discard();
    return stack;
  }

  @GameTest(template = "empty")
  public static void creativePlacementAndMovePreserveOwnerAndGrants(GameTestHelper helper) {
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    place(helper, FIRST, player, new ItemStack(NetworkContent.CONTROLLER_ITEM.get()));
    var first = node(helper, FIRST);
    var handle = first.handle();
    var saved = authority(helper);
    helper.assertTrue(
        saved.nodes().validate(handle) == NodeRegistry.Failure.OK, "Placed root canonical lease");
    helper.assertTrue(
        saved.network(handle.id()).snapshot().owner().equals(player.getUUID()),
        "Server placer owns private network");
    for (var face : Direction.values()) {
      helper.assertTrue(
          NetworkedBlock.wired(first.getBlockState(), face)
              && NetworkedBlock.wired(NetworkContent.CABLE.get().defaultBlockState(), face),
          "Controller and cable accept every ordinary face");
    }
    helper.assertTrue(
        !NetworkedBlock.wired(Blocks.STONE.defaultBlockState(), Direction.UP),
        "Ordinary vanilla blocks are not wired ports");
    var collaborator = UUID.randomUUID();
    saved
        .network(handle.id())
        .setGrants(
            new NetworkPermissions.Actor(player.getUUID(), false),
            collaborator,
            Set.of(NetworkPermissions.Permission.VIEW));
    var portable = breakOne(helper, FIRST);
    helper.assertTrue(
        saved.nodes().validate(handle) == NodeRegistry.Failure.STALE,
        "Break consumes placed generation");
    place(helper, SECOND, player, portable);
    var moved = node(helper, SECOND).handle();
    helper.assertTrue(
        moved.id().equals(handle.id()) && moved.generation() == handle.generation() + 2,
        "Move retains network with two consumed generations");
    helper.assertTrue(
        saved
            .network(moved.id())
            .allows(
                new NetworkPermissions.Actor(collaborator, false),
                NetworkPermissions.Permission.VIEW),
        "Move retains explicit grant");
    helper.assertTrue(
        saved.network(moved.id()).snapshot().owner().equals(player.getUUID()),
        "Move retains owner");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void gatewayPortsReloadAndClonedBlocksQuarantine(GameTestHelper helper) {
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    place(helper, FIRST, player, new ItemStack(NetworkContent.GATEWAY_ITEM.get()));
    var first = node(helper, FIRST);
    var handle = first.handle();
    var state = first.getBlockState();
    Direction downstream = state.getValue(BlockStateProperties.FACING);
    for (var direction : Direction.values())
      helper.assertTrue(
          NetworkContent.GATEWAY.get().wiredFace(state, direction)
              == (direction == downstream || direction == downstream.getOpposite()),
          "Only opposing gateway ports connect");
    var data = first.saveWithFullMetadata(helper.getLevel().registryAccess());
    first.setRemoved();
    first.clearRemoved();
    first.loadWithComponents(data, helper.getLevel().registryAccess());
    first.onLoad();
    helper.assertTrue(
        first.handle().equals(handle) && first.diagnostic().isEmpty(),
        "Same-position reload is idempotent");
    helper.setBlock(SECOND, state);
    var clone = node(helper, SECOND);
    clone.loadWithComponents(data, helper.getLevel().registryAccess());
    clone.onLoad();
    helper.assertTrue(
        authority(helper).nodes().validate(handle) == NodeRegistry.Failure.CONFLICT
            && !clone.diagnostic().isEmpty(),
        "Placed clone quarantines canonical gateway");
    helper.assertTrue(
        authority(helper).nodes().snapshot().stream()
                .filter(n -> n.id().equals(handle.id()))
                .count()
            == 1,
        "Clone creates no second structural owner");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void futureAndPortableChunkReferencesRemainUnavailable(GameTestHelper helper) {
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    var original = new CompoundTag();
    original.putInt("Schema", 91);
    original.putString("FutureOwnedData", "retain exactly");
    var recovery = new ItemStack(NetworkContent.CONTROLLER_ITEM.get());
    CustomData.update(
        DataComponents.CUSTOM_DATA,
        recovery,
        custom -> custom.put(NodeHandleData.FIELD, original.copy()));
    int networks =
        authority(helper)
            .save(new CompoundTag(), helper.getLevel().registryAccess())
            .getList("Networks", Tag.TAG_COMPOUND)
            .size();
    place(helper, FIRST, player, recovery);
    helper.assertTrue(
        !node(helper, FIRST).diagnostic().isEmpty() && node(helper, FIRST).handle() == null,
        "Future data is not commissioned");
    var dropped = breakOne(helper, FIRST);
    helper.assertTrue(
        original.equals(
            dropped.get(DataComponents.CUSTOM_DATA).copyTag().get(NodeHandleData.FIELD)),
        "Future item data retained exactly");
    place(helper, SECOND, player, dropped);
    helper.assertTrue(
        !node(helper, SECOND).diagnostic().isEmpty(), "Replacement remains unavailable");
    helper.assertTrue(
        networks
            == authority(helper)
                .save(new CompoundTag(), helper.getLevel().registryAccess())
                .getList("Networks", Tag.TAG_COMPOUND)
                .size(),
        "No future reference manufactures grants");
    var handle =
        authority(helper).createGateway(Positions.of(helper.getLevel(), helper.absolutePos(FIRST)));
    var portable =
        authority(helper)
            .nodes()
            .detach(handle, Positions.of(helper.getLevel(), helper.absolutePos(FIRST)))
            .handle();
    helper.setBlock(FIRST, NetworkContent.GATEWAY.get());
    var tag = node(helper, FIRST).saveWithFullMetadata(helper.getLevel().registryAccess());
    tag.put(NodeHandleData.FIELD, NodeHandleData.write(portable));
    node(helper, FIRST).loadWithComponents(tag, helper.getLevel().registryAccess());
    node(helper, FIRST).onLoad();
    helper.assertTrue(
        !node(helper, FIRST).diagnostic().isEmpty()
            && authority(helper).nodes().definition(portable).position() == null,
        "Chunk reload cannot consume a portable reference");
    var wrongType = new ItemStack(NetworkContent.CONTROLLER_ITEM.get());
    CustomData.update(
        DataComponents.CUSTOM_DATA,
        wrongType,
        custom -> custom.putString(NodeHandleData.FIELD, "preserve wrong-type envelope"));
    var third = new BlockPos(4, 1, 3);
    place(helper, third, player, wrongType);
    helper.assertTrue(
        !node(helper, third).diagnostic().isEmpty(), "Wrong-type envelope unavailable");
    var retained = breakOne(helper, third);
    helper.assertTrue(
        retained
            .get(DataComponents.CUSTOM_DATA)
            .copyTag()
            .getString(NodeHandleData.FIELD)
            .equals("preserve wrong-type envelope"),
        "Wrong-type original survives break");
    helper.succeed();
  }

  @GameTest(template = "empty")
  public static void vanillaNetworkRecipesAndCommissioning(GameTestHelper helper) {
    Map<String, Map<String, Integer>> expected =
        Map.of(
            "network_controller", Map.of("iron_ingot", 4, "redstone", 4, "glass", 1),
            "gateway", Map.of("iron_ingot", 4, "redstone", 2, "quartz", 2, "ender_pearl", 1),
            "network_cable", Map.of("iron_ingot", 6, "redstone", 3));
    for (var entry : expected.entrySet()) {
      var id = ResourceLocation.fromNamespaceAndPath("factorycore", entry.getKey());
      var recipe = helper.getLevel().getRecipeManager().byKey(id).orElseThrow();
      var ingredients = new HashMap<String, Integer>();
      for (var ingredient : recipe.value().getIngredients()) {
        if (ingredient.isEmpty()) continue;
        helper.assertTrue(ingredient.getItems().length == 1, "Recipe has exact vanilla ingredient");
        var key = BuiltInRegistries.ITEM.getKey(ingredient.getItems()[0].getItem());
        helper.assertTrue(key.getNamespace().equals("minecraft"), "No optional ingredient");
        ingredients.merge(key.getPath(), 1, Integer::sum);
      }
      helper.assertTrue(entry.getValue().equals(ingredients), "Exact selected recipe cost");
      helper.assertTrue(
          recipe.value().getResultItem(helper.getLevel().registryAccess()).getCount()
              == (entry.getKey().equals("network_cable") ? 8 : 1),
          "Bounded expected output");
      helper.assertTrue(
          helper.getLevel().getServer().getAdvancements().getAllAdvancements().stream()
              .anyMatch(advancement -> advancement.value().rewards().recipes().contains(id)),
          "An advancement unlocks " + id);
    }
    helper.setBlock(FIRST, NetworkContent.CONTROLLER.get());
    helper.assertTrue(
        node(helper, FIRST).handle() == null, "Command-created controller has no invented owner");
    var player = helper.makeMockPlayer(GameType.CREATIVE);
    helper.useBlock(FIRST, player);
    helper.assertTrue(
        authority(helper)
            .network(node(helper, FIRST).handle().id())
            .snapshot()
            .owner()
            .equals(player.getUUID()),
        "Explicit use commissions actual server player");
    helper.succeed();
  }

  // Own batch: the audited repair/adoption throttle is shared per server.
  @GameTest(template = "empty", batch = "node_adoption", timeoutTicks = 300)
  public static void operatorAdoptsAStaleControllerAfterAnIndependentSaveMismatch(
      GameTestHelper helper) {
    var player = TestPlayers.mock(helper);
    var drivePos = new BlockPos(2, 1, 1);
    var far = new BlockPos(1, 1, 4);
    var access = helper.getLevel().registryAccess();
    place(helper, FIRST, player, new ItemStack(NetworkContent.CONTROLLER_ITEM.get()));
    var original = node(helper, FIRST).handle();
    helper.setBlock(drivePos, StorageContent.DRIVE.get());
    var membership =
        (Supplier<NetworkTopology.Status>)
            () ->
                WorldNetworks.get(helper.getLevel().getServer())
                    .membership(Positions.of(helper.getLevel(), helper.absolutePos(drivePos)))
                    .status();
    long[] canonical = {0};
    helper
        .startSequence()
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    membership.get() == NetworkTopology.Status.VALID,
                    "Drive joins the controller's network"))
        .thenIdle(21)
        .thenExecute(
            () -> {
              var before = node(helper, FIRST).saveWithFullMetadata(access);
              // The canonical save records the controller broken at FIRST and placed at `far`...
              place(helper, far, player, breakOne(helper, FIRST));
              var moved = node(helper, far).handle();
              canonical[0] = moved.generation();
              helper.assertTrue(
                  moved.generation() == original.generation() + 2, "Break and place rotate");
              // ...but the chunk was saved before: FIRST still holds the old controller.
              node(helper, far).loadWithComponents(new CompoundTag(), access);
              helper.setBlock(FIRST, NetworkContent.CONTROLLER.get());
              node(helper, FIRST).loadWithComponents(before, access);
              node(helper, FIRST).onLoad();
              helper.assertTrue(
                  !node(helper, FIRST).diagnostic().isEmpty()
                      && authority(helper)
                          .network(original.id())
                          .snapshot()
                          .owner()
                          .equals(player.getUUID()),
                  "The mismatch is inert; grants are untouched");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    membership.get() != NetworkTopology.Status.VALID,
                    "Without its canonical controller the network is not valid"))
        .thenExecute(
            () -> {
              var console = helper.getLevel().getServer().createCommandSourceStack();
              var pos = helper.absolutePos(FIRST);
              var adopt =
                  "factorycore recovery node adopt %s %d %d %d %d"
                      .formatted(original.id(), canonical[0], pos.getX(), pos.getY(), pos.getZ());
              helper.assertTrue(
                  command(console.withPermission(0), adopt) < 1, "Ordinary sources cannot adopt");
              helper.assertTrue(command(console, adopt) == 1, "Operator adopts the old controller");
              var adopted = node(helper, FIRST).handle();
              helper.assertTrue(
                  adopted.generation() == canonical[0] + 1
                      && node(helper, FIRST).diagnostic().isEmpty()
                      && authority(helper).nodes().validate(original) == NodeRegistry.Failure.STALE,
                  "The canonical lease moved back to the adopted controller");
            })
        .thenWaitUntil(
            () ->
                helper.assertTrue(
                    membership.get() == NetworkTopology.Status.VALID
                        && authority(helper)
                            .network(original.id())
                            .snapshot()
                            .owner()
                            .equals(player.getUUID()),
                    "The network and its owner are restored"))
        .thenExecute(() -> player.connection.disconnect(Component.literal("done")))
        .thenSucceed();
  }

  private static int command(CommandSourceStack source, String command) {
    try {
      return source.getServer().getCommands().getDispatcher().execute(command, source);
    } catch (CommandSyntaxException denied) {
      return -1;
    }
  }
}
