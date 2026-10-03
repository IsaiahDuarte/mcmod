package dev.izzy.factorycore.testing;

import java.io.File;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.gametest.framework.LogTestReporter;
import net.minecraft.gametest.framework.TestReporter;
import net.neoforged.fml.common.Mod;

/** Test-only loader entry; it is excluded from the distributed mod. */
@Mod("factorycore_tests")
public final class GameTestMod {
  public GameTestMod() throws ParserConfigurationException {
    if (!Boolean.getBoolean("neoforge.gameTestServer")) return;
    var xml = new JUnitLikeTestReporter(new File(System.getProperty("factorycore.test.report")));
    var log = new LogTestReporter();
    GlobalTestReporter.replaceWith(
        new TestReporter() {
          private int completed;

          @Override
          public void onTestFailed(GameTestInfo test) {
            completed++;
            xml.onTestFailed(test);
            log.onTestFailed(test);
          }

          @Override
          public void onTestSuccess(GameTestInfo test) {
            completed++;
            xml.onTestSuccess(test);
          }

          @Override
          public void finish() {
            int required = GameTestRegistry.getAllTestFunctions().size();
            if (required == 0 || completed != required)
              throw new IllegalStateException("GameTest collection/completion mismatch");
            xml.finish();
          }
        });
  }
}
