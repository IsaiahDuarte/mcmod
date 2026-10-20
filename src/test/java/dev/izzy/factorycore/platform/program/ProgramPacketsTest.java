package dev.izzy.factorycore.platform.program;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
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
                1, id, 1, "running", "", 0, "", true, Collections.nCopies(9, "x"), List.of()));
  }

  @Test
  void authoringPayloadBoundsRejectOversizedCatalogsAndSnapshots() {
    var id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProgramPackets.AuthorRequest(1, id, true, 64, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProgramPackets.AuthorRequest(1, id, false, 0, "x".repeat(65)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProgramPackets.AuthorView(
                1, id, "scope", Collections.nCopies(17, "label"), false, 0, 0, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProgramPackets.AuthorView(
                1, id, "scope", List.of(), false, 0, 1, Collections.nCopies(9, "line")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProgramPackets.AuthorView(
                1, id, "scope", List.of(), false, 0, 1, List.of("x".repeat(193))));
    var view =
        new ProgramPackets.AuthorView(
            1, id, "scope", List.of("a"), false, 0, 1, List.of("snapshot"));
    assertThrows(UnsupportedOperationException.class, () -> view.labels().clear());
    assertThrows(UnsupportedOperationException.class, () -> view.preview().clear());
  }
}
