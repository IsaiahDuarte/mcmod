package dev.izzy.factorycore.platform.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FitTextTest {
  private static final List<String> STATUS = List.of("Online", "Ready to import", "1.2k FE");

  private static String fit(int width) {
    return FitText.fit(STATUS, width, String::length);
  }

  @Test
  void dropsWholePartsBeforeWords() {
    assertEquals("Online · Ready to import · 1.2k FE", fit(100));
    assertEquals("Online · Ready to import …", fit(26));
    assertEquals("Online …", fit(25));
    assertEquals("Online …", fit(8));
  }

  @Test
  void neverCutsMidWordUnlessOneWordIsTooWide() {
    var parts = List.of("Network rebuilding now");
    assertEquals("Network rebuilding…", FitText.fit(parts, 19, String::length));
    assertEquals("Network…", FitText.fit(parts, 10, String::length));
    assertEquals("Net…", FitText.fit(parts, 4, String::length));
    assertEquals("", FitText.fit(parts, 1, String::length));
  }
}
