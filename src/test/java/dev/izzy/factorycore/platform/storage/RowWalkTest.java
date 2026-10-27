package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** Q9: the bounds and rules of the row-labelling walk on synthetic layouts. */
class RowWalkTest {
  private static final int MAX = 64, RUN = 2_048;

  /** Run blocks connect on every face unless a face is in {@code cut}. */
  private record World(
      Set<BlockPos> runs, Set<BlockPos> machines, Set<BlockPos> unloaded, Set<BlockPos> cut)
      implements RowWalk.World {
    World(Set<BlockPos> runs, Set<BlockPos> machines) {
      this(runs, machines, Set.of(), Set.of());
    }

    @Override
    public boolean loaded(BlockPos at) {
      return !unloaded.contains(at);
    }

    @Override
    public boolean run(BlockPos at) {
      return runs.contains(at);
    }

    @Override
    public boolean connected(BlockPos from, Direction side) {
      return !cut.contains(from.relative(side));
    }

    @Override
    public boolean sameMachine(BlockPos at) {
      return machines.contains(at);
    }
  }

  /** Cable x = 0..length-1 at z = 0; a machine at z = 1 beside each cable in {@code beside}. */
  private static World line(int length, int... beside) {
    var runs = new HashSet<BlockPos>();
    var machines = new HashSet<BlockPos>();
    for (int x = 0; x < length; x++) runs.add(new BlockPos(x, 0, 0));
    for (int x : beside) machines.add(new BlockPos(x, 0, 1));
    return new World(runs, machines);
  }

  @Test
  void findsEveryMachineAlongTheRunNearestFirst() {
    var world = line(8, 0, 1, 2, 3, 4, 5, 6, 7);
    var found = RowWalk.machines(world, new BlockPos(3, 0, 1), MAX, RUN);
    assertEquals(8, found.size());
    var order = new ArrayList<>(found.keySet());
    assertEquals(new BlockPos(3, 0, 1), order.getFirst(), "The clicked machine first");
    assertEquals(
        Set.of(new BlockPos(2, 0, 1), new BlockPos(4, 0, 1)),
        Set.copyOf(order.subList(1, 3)),
        "Its neighbours along the run next");
    for (var face : found.values()) assertEquals(Direction.NORTH, face, "Faces toward the cable");
  }

  @Test
  void otherBlocksAndMachinesOffTheRunAreSkipped() {
    var world = line(4, 0, 1, 2, 3);
    var runs = new HashSet<>(world.runs());
    var machines = new HashSet<>(world.machines());
    // Diagonal to the run: touches no run block.
    machines.add(new BlockPos(4, 0, 1));
    var found =
        RowWalk.machines(new World(runs, machines), new BlockPos(0, 0, 1), MAX, RUN).keySet();
    assertEquals(4, found.size(), "A chest is not a same-type machine; the diagonal one is off");
    assertFalse(found.contains(new BlockPos(4, 0, 1)));
  }

  /** 17 levels of a spine with four arms and a machine beyond each: 68 machines. */
  @Test
  void stopsOneAfterTheRowLimit() {
    var runs = new HashSet<BlockPos>();
    var machines = new HashSet<BlockPos>();
    for (int y = 0; y < 17; y++) {
      runs.add(new BlockPos(0, y, 0));
      for (var side : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
        runs.add(new BlockPos(0, y, 0).relative(side));
        machines.add(new BlockPos(0, y, 0).relative(side, 2));
      }
    }
    var found = RowWalk.machines(new World(runs, machines), new BlockPos(0, 0, 2), MAX, RUN);
    assertEquals(MAX + 1, found.size(), "One past the limit tells the caller it was cut");
  }

  @Test
  void theRunBoundAndUnloadedOrUnwiredBlocksEndTheWalk() {
    var far = line(3_000, 0, 2_999);
    assertEquals(
        1,
        RowWalk.machines(far, new BlockPos(0, 0, 1), MAX, RUN).size(),
        "The far machine is past 2,048 run blocks");
    assertEquals(2, RowWalk.machines(far, new BlockPos(0, 0, 1), MAX, 4_000).size());
    var gap = line(6, 0, 5);
    var unloaded = new World(gap.runs(), gap.machines(), Set.of(new BlockPos(3, 0, 0)), Set.of());
    assertEquals(1, RowWalk.machines(unloaded, new BlockPos(0, 0, 1), MAX, RUN).size());
    var unwired = new World(gap.runs(), gap.machines(), Set.of(), Set.of(new BlockPos(3, 0, 0)));
    assertEquals(1, RowWalk.machines(unwired, new BlockPos(0, 0, 1), MAX, RUN).size());
  }
}
