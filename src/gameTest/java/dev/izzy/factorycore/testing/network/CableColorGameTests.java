package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.CableColor;
import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.network.WorldWireReader;
import dev.izzy.factorycore.testing.Net;
import dev.izzy.factorycore.testing.TestPlayers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Q29 (QD5): two coloured runs side by side stay two networks; an uncoloured cable between them
 * joins both; recolouring with a dye or a wet sponge rebuilds the network; arms match the wire
 * reader's ports; dyeing in the grid and breaking keep the colour.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class CableColorGameTests {
  // Red run along z=1 from a root at x=1; blue run along z=2 from a root at x=5.
  private static final BlockPos RED_ROOT = new BlockPos(1, 1, 1),
      BLUE_ROOT = new BlockPos(5, 1, 2),
      MIDDLE_RED = new BlockPos(3, 1, 1),
      MIDDLE_BLUE = new BlockPos(3, 1, 2);

  private CableColorGameTests() {}

  private static void cable(GameTestHelper h, BlockPos at, CableColor color) {
    var block = NetworkContent.CABLE.get();
    h.setBlock(at, block.connected(h.getLevel(), h.absolutePos(at), color));
  }

  private static NetworkTopology.Membership membership(GameTestHelper h, BlockPos at) {
    return WorldNetworks.get(h.getLevel().getServer())
        .membership(Positions.of(h.getLevel(), h.absolutePos(at)));
  }

  private static boolean valid(GameTestHelper h, BlockPos at) {
    return membership(h, at).status() == NetworkTopology.Status.VALID;
  }

  private static boolean separate(GameTestHelper h) {
    return valid(h, MIDDLE_RED)
        && valid(h, MIDDLE_BLUE)
        && !membership(h, MIDDLE_RED)
            .scope()
            .network()
            .equals(membership(h, MIDDLE_BLUE).scope().network());
  }

  /** Two roots in one network: neither run is valid. */
  private static boolean joined(GameTestHelper h) {
    var red = membership(h, MIDDLE_RED).status();
    var blue = membership(h, MIDDLE_BLUE).status();
    return red != NetworkTopology.Status.VALID
        && red != NetworkTopology.Status.REBUILDING
        && blue != NetworkTopology.Status.VALID
        && blue != NetworkTopology.Status.REBUILDING;
  }

  /** The faces the cable at {@code at} draws arms to equal the faces the wire reader ports. */
  private static void armsMatchPorts(GameTestHelper h, BlockPos at) {
    var reader = new WorldWireReader(h.getLevel(), position -> null);
    var origin = h.absolutePos(at);
    var state = h.getLevel().getBlockState(origin);
    var ported = EnumSet.noneOf(Direction.class);
    for (var port : reader.apply(Positions.of(h.getLevel(), origin)).ports())
      for (var face : Direction.values())
        if (origin.relative(face).asLong() == port.neighbor().block()) ported.add(face);
    var armed = EnumSet.noneOf(Direction.class);
    var drawn = EnumSet.noneOf(Direction.class);
    for (var face : Direction.values()) {
      var neighbour = h.getLevel().getBlockState(origin.relative(face));
      if (NetworkedBlock.wired(state, face) && NetworkCableBlock.linksTo(state, neighbour, face)
          || !(neighbour.getBlock() instanceof NetworkedBlock) && NetworkedBlock.wired(state, face))
        armed.add(face);
      if (state.getValue(NetworkCableBlock.property(face))) drawn.add(face);
    }
    h.assertTrue(ported.equals(armed), at + ": reader ports " + ported + ", links " + armed);
    for (var face : drawn)
      h.assertTrue(ported.contains(face), at + ": an arm toward " + face + " has no port");
  }

  @GameTest(template = "empty", timeoutTicks = 300)
  public static void colouredRunsStaySeparateAndRecolouringRebuilds(GameTestHelper h) {
    var player = TestPlayers.mock(h);
    player.setGameMode(GameType.SURVIVAL);
    Net.in(h, player.getUUID()).controller(RED_ROOT);
    Net.in(h, player.getUUID()).controller(BLUE_ROOT);
    for (int x = 2; x <= 4; x++) {
      cable(h, new BlockPos(x, 1, 1), CableColor.RED);
      cable(h, new BlockPos(x, 1, 2), CableColor.BLUE);
    }
    var use =
        (java.util.function.Consumer<ItemStack>)
            held -> {
              player.setItemInHand(InteractionHand.MAIN_HAND, held);
              var at = h.absolutePos(MIDDLE_RED);
              h.getLevel()
                  .getBlockState(at)
                  .useItemOn(
                      held,
                      h.getLevel(),
                      player,
                      InteractionHand.MAIN_HAND,
                      new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
            };
    var dye = new ItemStack(Items.RED_DYE, 2);
    h.startSequence()
        .thenWaitUntil(() -> h.assertTrue(separate(h), "Red and blue runs side by side: two"))
        .thenExecute(
            () -> {
              armsMatchPorts(h, MIDDLE_RED);
              armsMatchPorts(h, MIDDLE_BLUE);
              h.assertTrue(
                  !h.getBlockState(MIDDLE_RED)
                      .getValue(NetworkCableBlock.property(Direction.SOUTH)),
                  "No arm between red and blue");
              cable(h, MIDDLE_RED, CableColor.NONE);
            })
        .thenWaitUntil(
            () -> h.assertTrue(joined(h), "An uncoloured cable between them joins both roots"))
        .thenExecute(
            () -> {
              armsMatchPorts(h, MIDDLE_RED);
              use.accept(dye);
              h.assertTrue(
                  NetworkCableBlock.color(h.getBlockState(MIDDLE_RED)) == CableColor.RED
                      && dye.getCount() == 1,
                  "A dye colours the placed cable and is used up");
            })
        .thenWaitUntil(() -> h.assertTrue(separate(h), "Dyed red: two networks again"))
        .thenExecute(
            () -> {
              armsMatchPorts(h, MIDDLE_RED);
              var sponge = new ItemStack(Items.WET_SPONGE);
              use.accept(sponge);
              h.assertTrue(
                  NetworkCableBlock.color(h.getBlockState(MIDDLE_RED)) == CableColor.NONE
                      && sponge.getCount() == 1,
                  "A wet sponge clears it and is kept");
            })
        .thenWaitUntil(() -> h.assertTrue(joined(h), "Cleared: one joined network again"))
        .thenSucceed();
  }

  @GameTest(template = "empty")
  public static void theGridRecoloursCablesAndBreakingKeepsTheColour(GameTestHelper h) {
    var manager = h.getLevel().getRecipeManager();
    var cables = new ItemStack(NetworkContent.CABLE_ITEM.get());
    var grid = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
    grid.set(0, cables.copy());
    grid.set(1, cables.copy());
    grid.set(2, NetworkCableBlock.item(CableColor.BLUE, 1));
    grid.set(4, new ItemStack(Items.RED_DYE));
    var input = CraftingInput.of(3, 3, grid);
    var found = manager.getRecipeFor(RecipeType.CRAFTING, input, h.getLevel());
    h.assertTrue(found.isPresent(), "Three cables of any colour and a dye match");
    var red = found.get().value().assemble(input, h.getLevel().registryAccess());
    h.assertTrue(
        red.getCount() == 3 && NetworkCableBlock.color(red) == CableColor.RED,
        "They come out as three red cables: " + red);
    grid.set(4, new ItemStack(Items.WATER_BUCKET));
    input = CraftingInput.of(3, 3, grid);
    var clear = manager.getRecipeFor(RecipeType.CRAFTING, input, h.getLevel()).orElseThrow();
    var plain = clear.value().assemble(input, h.getLevel().registryAccess());
    h.assertTrue(
        plain.getCount() == 3
            && plain.get(DataComponents.BLOCK_STATE) == null
            && ItemStack.isSameItemSameComponents(plain, cables),
        "A water bucket clears them into plain cable that stacks with crafted cable");
    h.assertTrue(
        clear.value().getRemainingItems(input).get(4).is(Items.BUCKET),
        "The bucket comes back empty");
    for (var bad :
        List.of(
            List.of(new ItemStack(Items.RED_DYE), new ItemStack(Items.BLUE_DYE), cables.copy()),
            List.of(new ItemStack(Items.RED_DYE), new ItemStack(Items.WATER_BUCKET), cables),
            List.of(new ItemStack(Items.RED_DYE), new ItemStack(Items.STONE), cables.copy()),
            List.of(new ItemStack(Items.RED_DYE)))) {
      var cells = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
      for (int i = 0; i < bad.size(); i++) cells.set(i, bad.get(i).copy());
      h.assertTrue(
          manager
              .getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, cells), h.getLevel())
              .isEmpty(),
          "Not a recolour: " + bad);
    }
    // Breaking keeps the colour; an uncoloured cable drops plain cable.
    var at = new BlockPos(1, 1, 1);
    cable(h, at, CableColor.GREEN);
    var drops =
        NetworkedBlock.collectDrops(() -> h.getLevel().destroyBlock(h.absolutePos(at), true));
    h.assertTrue(
        drops.size() == 1 && NetworkCableBlock.color(drops.get(0)) == CableColor.GREEN,
        "A green cable drops green: " + drops);
    h.assertTrue(
        drops.get(0).getHoverName().getString().equals("Green Network Cable"),
        "Named for its colour: " + drops.get(0).getHoverName().getString());
    cable(h, at, CableColor.NONE);
    drops = NetworkedBlock.collectDrops(() -> h.getLevel().destroyBlock(h.absolutePos(at), true));
    h.assertTrue(
        drops.size() == 1 && ItemStack.isSameItemSameComponents(drops.get(0), cables),
        "An uncoloured cable drops plain cable: " + drops);
    h.succeed();
  }
}
