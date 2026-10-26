package dev.izzy.factorycore.platform.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PanelLayoutTest {
  private static void assertTiles(Rect row, List<Rect> cells, int gap) {
    assertEquals(row.x(), cells.getFirst().x(), "first cell starts at the row");
    assertEquals(row.right(), cells.getLast().right(), "last cell ends at the row");
    for (int i = 1; i < cells.size(); i++)
      assertEquals(cells.get(i - 1).right() + gap, cells.get(i).x(), "gap before cell " + i);
    for (var cell : cells) {
      assertTrue(row.contains(cell), cell + " inside " + row);
      assertTrue(cell.width() >= 0);
    }
  }

  @Test
  void rowsStackWithGapsAndNeverReachTheFooter() {
    var layout = new PanelLayout(PanelLayout.WIDE, PanelLayout.heightFor(18 + 4 + 40));
    var first = layout.row();
    var second = layout.row(40);
    assertEquals(new Rect(7, 18, 216, 18), first);
    assertEquals(first.bottom() + PanelLayout.GAP, second.y());
    assertEquals(layout.footer().y() - PanelLayout.GAP, second.bottom(), "body is exactly full");
    assertFalse(second.overlaps(layout.footer()));
    assertTrue(layout.bounds().contains(layout.footer()));
    assertEquals(layout.height() - PanelLayout.EDGE, layout.footer().bottom());
    var overflow = assertThrows(IllegalStateException.class, () -> layout.row(1));
    assertTrue(overflow.getMessage().contains("overflows"), overflow.getMessage());
  }

  @Test
  void edgesMatchAVanillaInventory() {
    // A docked 9-slot row drawn from EDGE fills a narrow panel exactly as a vanilla chest does.
    var layout = new PanelLayout(PanelLayout.NARROW, PanelLayout.heightFor(PanelLayout.SLOT));
    assertEquals(9 * PanelLayout.SLOT, layout.innerWidth());
    assertEquals(PanelLayout.EDGE, layout.body().x());
  }

  @Test
  void fitSharesSpareWidthAndTilesTheRow() {
    var row = new Rect(7, 18, 216, 18);
    var cells = PanelLayout.fit(row, new int[] {46, 61, 51, 44}, 4);
    assertTiles(row, cells, 4);
    // 216 - 3 gaps - 202 wanted = 2 spare pixels, given to the two leftmost cells.
    assertEquals(List.of(47, 62, 51, 44), cells.stream().map(Rect::width).toList());
  }

  @Test
  void fitShrinksProportionallyWhenTheMinimumsDoNotFit() {
    var row = new Rect(0, 0, 100, 18);
    var cells = PanelLayout.fit(row, new int[] {100, 50, 50}, 4);
    assertTiles(row, cells, 4);
    assertTrue(cells.get(0).width() > cells.get(1).width());
    assertEquals(PanelLayout.fit(row, new int[0], 4), List.of());
  }

  @Test
  void equalColumnsAndRightAlignedActions() {
    var row = new Rect(7, 160, 216, 18);
    var columns = PanelLayout.columns(row, 3, 4);
    assertTiles(row, columns, 4);
    assertEquals(List.of(70, 69, 69), columns.stream().map(Rect::width).toList());
    var actions = PanelLayout.alignRight(row, new int[] {40, 50}, 4);
    assertEquals(row.right(), actions.getLast().right());
    assertEquals(actions.getFirst().right() + 4, actions.getLast().x());
  }

  @Test
  void panelsTooSmallForAFooterAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new PanelLayout(10, 100));
    assertThrows(IllegalArgumentException.class, () -> new PanelLayout(176, 30));
    assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, -1, 0));
  }
}
