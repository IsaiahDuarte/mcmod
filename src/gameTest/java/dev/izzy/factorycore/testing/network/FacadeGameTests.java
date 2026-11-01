package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.CableColor;
import dev.izzy.factorycore.platform.network.FacadeEntity;
import dev.izzy.factorycore.platform.network.FacadeItem;
import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q30 (QD6): a facade hides a cable without changing its links, comes off on its own when the cable
 * is broken, drops with the cable when the block is removed outright, and is crafted from a cable
 * and a plain full block.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class FacadeGameTests {
  private static final BlockPos ROOT = new BlockPos(1, 1, 1), FACED = new BlockPos(3, 1, 1);

  private FacadeGameTests() {}

  private static NetworkTopology.Membership membership(GameTestHelper h, BlockPos at) {
    return WorldNetworks.get(h.getLevel().getServer())
        .membership(Positions.of(h.getLevel(), h.absolutePos(at)));
  }

  private static void use(
      GameTestHelper h, net.minecraft.server.level.ServerPlayer player, ItemStack held) {
    player.setItemInHand(InteractionHand.MAIN_HAND, held);
    var at = h.absolutePos(FACED);
    h.getLevel()
        .getBlockState(at)
        .useItemOn(
            held,
            h.getLevel(),
            player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
  }

  private static List<ItemStack> dropped(GameTestHelper h) {
    var drops = new ArrayList<ItemStack>();
    for (var entity :
        h.getLevel()
            .getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(FACED)).inflate(2)))
      drops.add(entity.getItem().copy());
    return drops;
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void aFacadeHidesACableWithoutChangingLinksAndComesOffFirst(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    player.setGameMode(GameType.SURVIVAL);
    Net.in(h, player.getUUID()).controller(ROOT);
    var cable = NetworkContent.CABLE.get();
    for (int pass = 0; pass < 2; pass++)
      for (int x = 2; x <= 4; x++) {
        var at = new BlockPos(x, 1, 1);
        h.setBlock(at, cable.connected(h.getLevel(), h.absolutePos(at), CableColor.RED));
      }
    var facades = FacadeItem.of(Blocks.STONE_BRICKS, 2);
    UUID[] network = new UUID[1];
    var before = new net.minecraft.world.level.block.state.BlockState[1];
    h.startSequence()
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    membership(h, FACED).status() == NetworkTopology.Status.VALID, "Cable online"))
        .thenExecute(
            () -> {
              network[0] = membership(h, FACED).scope().network();
              before[0] = h.getBlockState(FACED);
              use(h, player, facades);
              var state = h.getBlockState(FACED);
              h.assertTrue(
                  state.getValue(NetworkCableBlock.FACADE)
                      && state.setValue(NetworkCableBlock.FACADE, false).equals(before[0]),
                  "The facade goes on and nothing else about the cable changes: " + state);
              h.assertTrue(
                  h.getBlockEntity(FACED) instanceof FacadeEntity facade
                      && facade.shown() == Blocks.STONE_BRICKS
                      && facades.getCount() == 1,
                  "It shows stone bricks and used one facade");
              h.assertTrue(
                  state.getShape(h.getLevel(), h.absolutePos(FACED)).bounds().getSize() == 1.0,
                  "A faced cable is a full block");
              use(h, player, facades);
              h.assertTrue(facades.getCount() == 1, "A second facade does not go on");
            })
        .thenIdle(5)
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    membership(h, FACED).status() == NetworkTopology.Status.VALID
                        && membership(h, FACED).scope().network().equals(network[0]),
                    "Still the same network, still online"))
        .thenExecute(
            () -> {
              player.gameMode.destroyBlock(h.absolutePos(FACED));
              var state = h.getBlockState(FACED);
              h.assertTrue(
                  state.is(cable)
                      && !state.getValue(NetworkCableBlock.FACADE)
                      && state.equals(before[0])
                      && h.getLevel().getBlockEntity(h.absolutePos(FACED)) == null,
                  "Breaking takes the facade off and leaves the cable, with no entity");
              var drops = dropped(h);
              h.assertTrue(
                  drops.size() == 1 && FacadeItem.shown(drops.get(0)) == Blocks.STONE_BRICKS,
                  "The facade drops: " + drops);
            })
        .thenWaitUntil(
            () ->
                h.assertTrue(
                    membership(h, FACED).status() == NetworkTopology.Status.VALID
                        && membership(h, FACED).scope().network().equals(network[0]),
                    "The cable is still in the same network"))
        .thenExecute(
            () -> {
              // Removed outright (explosion, Wrench): cable and facade both come back.
              use(h, player, facades);
              var drops =
                  NetworkedBlock.collectDrops(
                      () -> h.getLevel().destroyBlock(h.absolutePos(FACED), true));
              h.assertTrue(
                  drops.size() == 2
                      && drops.stream().anyMatch(d -> NetworkCableBlock.color(d) == CableColor.RED)
                      && drops.stream().anyMatch(d -> FacadeItem.shown(d) == Blocks.STONE_BRICKS),
                  "A removed faced cable drops the red cable and the facade: " + drops);
            })
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void facadesAreCraftedFromACableAndAPlainBlockAndSaveTheirBlock(GameTestHelper h) {
    var manager = h.getLevel().getRecipeManager();
    var cable = new ItemStack(NetworkContent.CABLE_ITEM.get());
    var grid = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    grid.set(0, cable.copy());
    grid.set(1, new ItemStack(Items.STONE_BRICKS));
    var input = CraftingInput.of(3, 3, grid);
    var made =
        manager
            .getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
            .orElseThrow()
            .value()
            .assemble(input, h.getLevel().registryAccess());
    h.assertTrue(
        made.getCount() == 4 && FacadeItem.shown(made) == Blocks.STONE_BRICKS,
        "A cable and stone bricks make four stone brick facades: " + made);
    h.assertTrue(
        made.getHoverName().getString().equals("Stone Bricks Facade"),
        "Named for its block: " + made.getHoverName().getString());
    for (var bad :
        List.of(
            List.of(cable.copy(), new ItemStack(Items.CHEST)),
            List.of(cable.copy(), new ItemStack(Items.GLASS)),
            List.of(cable.copy(), cable.copy(), new ItemStack(Items.STONE_BRICKS)),
            List.of(cable.copy(), new ItemStack(Items.STONE_BRICKS), new ItemStack(Items.DIRT)),
            List.of(
                cable.copy(),
                new ItemStack(dev.izzy.factorycore.platform.storage.TerminalContent.ITEM.get())))) {
      var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
      for (int i = 0; i < bad.size(); i++) cells.set(i, bad.get(i));
      h.assertTrue(
          manager
              .getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, cells), h.getLevel())
              .isEmpty(),
          "Not a facade (entity, see-through, two cables, two blocks, our own block): " + bad);
    }
    // Saved data: the block round-trips; an unknown block loads as no facade.
    var at = new BlockPos(1, 1, 1);
    h.setBlock(
        at,
        NetworkContent.CABLE.get().defaultBlockState().setValue(NetworkCableBlock.FACADE, true));
    var player = TestPlayers.mock(h);
    FacadeEntity facade = h.getBlockEntity(at);
    var registries = h.getLevel().registryAccess();
    var held = FacadeItem.of(Blocks.OAK_PLANKS, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, held);
    h.setBlock(at, NetworkContent.CABLE.get().defaultBlockState());
    h.getBlockState(at)
        .useItemOn(
            held,
            h.getLevel(),
            player,
            InteractionHand.MAIN_HAND,
            new BlockHitResult(
                Vec3.atCenterOf(h.absolutePos(at)), Direction.UP, h.absolutePos(at), false));
    facade = h.getBlockEntity(at);
    var saved = facade.saveWithoutMetadata(registries);
    var copy = new FacadeEntity(h.absolutePos(at), h.getBlockState(at));
    copy.loadWithComponents(saved, registries);
    h.assertTrue(copy.shown() == Blocks.OAK_PLANKS, "The facade's block is saved");
    saved.getCompound("FactoryCoreFacade").putString("Block", "gone:block");
    copy.loadWithComponents(saved, registries);
    h.assertTrue(copy.shown() == null, "An unknown block loads as no facade");
    saved.getCompound("FactoryCoreFacade").putInt("Schema", 2);
    saved.getCompound("FactoryCoreFacade").putString("Block", "minecraft:oak_planks");
    copy.loadWithComponents(saved, registries);
    h.assertTrue(copy.shown() == null, "A future schema loads as no facade");
    h.succeed();
  }
}
