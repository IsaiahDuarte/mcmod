package dev.izzy.factorycore.platform.program;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProgramPacketsTest {
  @Test
  void draftsSplitIntoBoundedChunksThatRebuildExactly() {
    var id = UUID.randomUUID();
    var text = "x".repeat(ProgramControllerEntity.MAX_DRAFT);
    var chunks = ProgramPackets.chunks(1, id, text);
    assertEquals(ProgramPackets.MAX_CHUNKS, chunks.size());
    var rebuilt = new StringBuilder();
    for (var chunk : chunks) {
      assertTrue(chunk.text().length() <= ProgramPackets.CHUNK_CHARS);
      rebuilt.append(chunk.text());
    }
    assertEquals(text, rebuilt.toString());
    assertEquals(1, ProgramPackets.chunks(1, id, "").size(), "An empty draft is one empty chunk");
    assertThrows(IllegalArgumentException.class, () -> ProgramPackets.chunks(1, id, text + "y"));
  }

  @Test
  void commandAndViewBoundsReject() {
    var id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class, () -> new ProgramPackets.Command(1, id, 6, 0, 1, ""));
    assertThrows(
        IllegalArgumentException.class, () -> new ProgramPackets.Command(1, id, 0, 1, 1, "a"));
    assertThrows(
        IllegalArgumentException.class, () -> new ProgramPackets.Command(1, id, 0, 0, 9, "a"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProgramPackets.Command(1, id, 2, 0, 1, "text on an action"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProgramPackets.Command(1, id, 0, 0, 1, "a".repeat(2049)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProgramPackets.View(1, id, 1, "running", "", -1, "", true, List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProgramPackets.View(
                1,
                id,
                1,
                "running",
                "",
                0,
                "",
                true,
                java.util.Collections.nCopies(9, "x"),
                List.of()));
  }
}
