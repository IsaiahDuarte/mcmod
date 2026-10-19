package dev.izzy.factorycore.platform;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.GatewayPolicy;
import dev.izzy.factorycore.core.network.NetworkTopology;
import java.util.Set;
import java.util.UUID;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

class FactoryCoreConfigTest {
  @Test
  void gatewayDepthValidatesFiniteRangeAndRequiresWorldRestart() {
    var setting = FactoryCoreConfig.GATEWAY_DEPTH.getSpec();
    assertEquals(8, setting.getDefault());
    assertEquals(ModConfigSpec.RestartType.WORLD, setting.restartType());
    for (int value : new int[] {1, 8, 32}) assertTrue(setting.test(value));
    for (Object value : new Object[] {0, -1, 33, "many"}) assertFalse(setting.test(value));
    assertEquals(1, setting.correct(0));
    assertEquals(32, setting.correct(33));
    assertEquals(8, setting.correct("many"));
  }

  @Test
  void acceptedDepthsAllowBoundaryAndRefuseTheNextNestedGateway() {
    var setting = FactoryCoreConfig.GATEWAY_DEPTH.getSpec();
    for (int depth : new int[] {1, 8, 32}) {
      assertTrue(setting.test(depth));
      var topology = new NetworkTopology(id(1000), depth);
      topology.putNode(new NetworkTopology.Node(id(1), NetworkTopology.Role.ROOT, "", true));
      for (int index = 1; index <= depth; index++) {
        topology.putNode(
            new NetworkTopology.Node(id(index + 1), NetworkTopology.Role.CABLE, "", true));
        topology.putGateway(
            new NetworkTopology.Gateway(
                id(100 + index),
                id(index),
                id(index + 1),
                new GatewayPolicy(Set.of(), Set.of(), Set.of()),
                true));
      }
      finish(topology);
      assertEquals(NetworkTopology.Status.VALID, topology.membership(id(depth + 1)).status());
      topology.putNode(
          new NetworkTopology.Node(id(depth + 2), NetworkTopology.Role.CABLE, "", true));
      topology.putGateway(
          new NetworkTopology.Gateway(
              id(101 + depth),
              id(depth + 1),
              id(depth + 2),
              new GatewayPolicy(Set.of(), Set.of(), Set.of()),
              true));
      finish(topology);
      assertEquals(NetworkTopology.Status.DEPTH_LIMIT, topology.membership(id(depth + 2)).status());
    }
  }

  private static UUID id(int value) {
    return new UUID(0, value);
  }

  private static void finish(NetworkTopology topology) {
    var rebuild = topology.beginValidation();
    for (int visits = 0; visits < 100; visits++) if (rebuild.advance(256).done()) return;
    fail("Finite depth topology did not complete bounded rebuilding");
  }
}
