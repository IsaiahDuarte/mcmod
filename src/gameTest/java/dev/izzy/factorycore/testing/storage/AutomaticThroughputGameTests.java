package dev.izzy.factorycore.testing.storage;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Fresh-world workload: prior suite fixtures must not consume the measured discovery budget. */
@GameTestHolder("factorycore_throughput_tests")
@PrefixGameTestTemplate(false)
public final class AutomaticThroughputGameTests {
  private AutomaticThroughputGameTests() {}

  @GameTest(template = "empty", timeoutTicks = 1200)
  public static void paidTiersIncreaseAutomaticChestImport(GameTestHelper helper) {
    MachinePresetGameTests.paidTiersIncreaseAutomaticChestImport(helper);
  }
}
