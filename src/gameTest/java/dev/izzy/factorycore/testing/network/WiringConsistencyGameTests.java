package dev.izzy.factorycore.testing.network;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkCableBlock;
import dev.izzy.factorycore.platform.network.NetworkedBlock;
import dev.izzy.factorycore.platform.network.WorldWireReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Pins the one wiring invariant: for every Factory Core networked block, in every facing, the faces
 * a cable draws an arm to (and that start discovery) are exactly the faces the wire reader gives a
 * port. Adding a device or changing its ports cannot let the two disagree.
 */
@GameTestHolder("factorycore_tests")
@PrefixGameTestTemplate(false)
public final class WiringConsistencyGameTests {
  private static final BlockPos AT = new BlockPos(1, 1, 1);

  private WiringConsistencyGameTests() {}

  private static List<BlockState> states(BlockState base) {
    var states = new ArrayList<BlockState>();
    for (var property : base.getProperties())
      if (property instanceof DirectionProperty facing
          && (property == BlockStateProperties.FACING
              || property == BlockStateProperties.HORIZONTAL_FACING)) {
        for (var value : facing.getPossibleValues()) states.add(base.setValue(facing, value));
        return states;
      }
    states.add(base);
    return states;
  }

  @GameTest(template = "empty")
  public static void cableArmsMatchWireReaderPortsForEveryNetworkedBlock(GameTestHelper helper) {
    var reader = new WorldWireReader(helper.getLevel(), position -> null);
    int checked = 0;
    for (var block : BuiltInRegistries.BLOCK) {
      var id = BuiltInRegistries.BLOCK.getKey(block);
      if (!id.getNamespace().equals(FactoryCoreMod.MOD_ID) || !(block instanceof NetworkedBlock))
        continue;
      for (var state : states(block.defaultBlockState())) {
        helper.setBlock(AT, Blocks.AIR);
        helper.setBlock(AT, state);
        var origin = helper.absolutePos(AT);
        var wire = reader.apply(Positions.of(helper.getLevel(), origin));
        var ported = EnumSet.noneOf(Direction.class);
        if (wire != null)
          for (var port : wire.ports())
            for (var face : Direction.values())
              if (origin.relative(face).asLong() == port.neighbor().block()) ported.add(face);
        var linked = EnumSet.noneOf(Direction.class);
        for (var face : Direction.values())
          if (NetworkCableBlock.linksTo(state, face.getOpposite())) linked.add(face);
        helper.assertTrue(
            ported.equals(linked),
            id + " " + state + ": reader ports " + ported + " but cable links " + linked);
        checked++;
      }
    }
    helper.assertTrue(checked >= 20, "Checked every networked block state: " + checked);
    helper.succeed();
  }
}
