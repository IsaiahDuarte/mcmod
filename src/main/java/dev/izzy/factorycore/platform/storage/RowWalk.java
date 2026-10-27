package dev.izzy.factorycore.platform.storage;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Q9: the cable-run walk behind row labelling, apart from the level so its bounds can be tested
 * directly. From a clicked machine it walks breadth-first along connected run blocks (cables and
 * cable ports) and collects machines of the clicked one's type that touch the run, nearest first.
 */
final class RowWalk {
  /** What the walk may ask about the world; every position it asks about is a loaded one. */
  interface World {
    boolean loaded(BlockPos at);

    /** A cable or cable port. */
    boolean run(BlockPos at);

    /** {@code from}'s {@code side} face and the run block beyond it are wired to each other. */
    boolean connected(BlockPos from, Direction side);

    /** A machine of the clicked machine's type. */
    boolean sameMachine(BlockPos at);
  }

  private RowWalk() {}

  /**
   * The machines found, each with its face toward the run block it was found from, in walk order
   * with {@code clicked} first. Stops once it holds {@code maxMachines + 1} (so a caller can tell
   * the row was cut) or has visited {@code maxRun} run blocks.
   */
  static Map<BlockPos, Direction> machines(
      World world, BlockPos clicked, int maxMachines, int maxRun) {
    var machines = new LinkedHashMap<BlockPos, Direction>();
    var visited = new HashSet<BlockPos>();
    var queue = new ArrayDeque<BlockPos>();
    for (var side : Direction.values()) {
      var at = clicked.relative(side);
      if (world.loaded(at) && world.run(at) && visited.add(at)) {
        machines.putIfAbsent(clicked, side);
        queue.add(at);
      }
    }
    machines.putIfAbsent(clicked, Direction.UP);
    while (!queue.isEmpty() && machines.size() <= maxMachines) {
      var cable = queue.poll();
      for (var side : Direction.values()) {
        var next = cable.relative(side);
        if (!world.loaded(next)) continue;
        if (world.run(next)) {
          if (visited.size() < maxRun && world.connected(cable, side) && visited.add(next))
            queue.add(next);
        } else if (machines.size() <= maxMachines && world.sameMachine(next))
          machines.putIfAbsent(next.immutable(), side.getOpposite());
      }
    }
    return machines;
  }
}
