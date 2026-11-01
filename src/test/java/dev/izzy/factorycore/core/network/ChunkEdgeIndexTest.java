package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** PD7 (Q1): a chunk event reaches a neighbour's region only through the shared edge. */
class ChunkEdgeIndexTest {
  private static final String WORLD = "minecraft:overworld";

  @Test
  void aChunkEventReachesItsOwnRegionsAndNeighboursOnTheSharedEdgeOnly() {
    var index = new ChunkEdgeIndex<String>();
    // Chunk (0, 0) spans blocks 0–15. "inner" sits in the middle, "east" on its east edge.
    index.move(WORLD, 8, 8, null, "inner");
    index.move(WORLD, 15, 8, null, "east");
    assertEquals(Set.of("inner", "east"), index.affected(WORLD, 0, 0));
    assertEquals(Set.of("east"), index.affected(WORLD, 1, 0), "East neighbour: edge cable only");
    assertEquals(Set.of(), index.affected(WORLD, -1, 0), "West neighbour: nothing on that edge");
    assertEquals(Set.of(), index.affected(WORLD, 0, 1));
    assertEquals(Set.of(), index.affected(WORLD, 1, 1), "Diagonals never connect");
    assertEquals(Set.of(), index.affected("minecraft:the_nether", 1, 0));
  }

  @Test
  void negativeCoordinatesCornersAndMovesKeepCountsExact() {
    var index = new ChunkEdgeIndex<String>();
    // Block (-16, -1) is chunk (-1, -1)'s west edge and south edge (a corner).
    index.move(WORLD, -16, -1, null, "corner");
    assertEquals(Set.of("corner"), index.affected(WORLD, -2, -1));
    assertEquals(Set.of("corner"), index.affected(WORLD, -1, 0));
    assertEquals(Set.of(), index.affected(WORLD, 0, -1));
    index.move(WORLD, -15, -1, null, "corner");
    index.move(WORLD, -16, -1, "corner", "merged");
    assertEquals(Set.of("merged"), index.affected(WORLD, -2, -1));
    assertEquals(Set.of("corner", "merged"), index.affected(WORLD, -1, 0));
    index.move(WORLD, -15, -1, "corner", null);
    index.move(WORLD, -16, -1, "merged", null);
    assertEquals(Set.of(), index.affected(WORLD, -1, -1), "Removing every position empties it");
  }
}
