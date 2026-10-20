package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;

class ConnectorDataTest {
  @Test
  void upgradedPortAndMachineSchemasRetainTierAndRejectImpossibleOrFutureData() {
    for (var kind : List.of(NodeRegistry.Kind.CONNECTOR, NodeRegistry.Kind.MACHINE_INTERFACE)) {
      var lease = new NodeRegistry.Handle(UUID.randomUUID(), UUID.randomUUID(), 3, kind);
      for (var tier : List.of(ThroughputTier.ADVANCED, ThroughputTier.ENDGAME)) {
        var state =
            new ConnectorData.State(
                lease,
                new ConnectorSettings(
                    ResourceKind.FLUID,
                    ConnectorSettings.Access.READ_WRITE,
                    0,
                    0,
                    64,
                    tier.portRate(ResourceKind.FLUID)),
                7,
                "retained fault",
                kind == NodeRegistry.Kind.MACHINE_INTERFACE ? "coolant" : "",
                tier);
        var tag = ConnectorData.write(state);
        assertEquals(kind == NodeRegistry.Kind.CONNECTOR ? 3 : 4, tag.getInt("Schema"));
        assertEquals(state, ConnectorData.read(tag));
        for (String invalid : List.of("BASE", "FUTURE")) {
          var malformed = tag.copy();
          malformed.putString("Throughput", invalid);
          var before = malformed.copy();
          assertThrows(IllegalArgumentException.class, () -> ConnectorData.read(malformed));
          assertEquals(before, malformed);
        }
        var oversized = tag.copy();
        oversized.putString("Throughput", "ADVANCED");
        oversized.putInt("Rate", 4001);
        assertThrows(IllegalArgumentException.class, () -> ConnectorData.read(oversized));
      }
    }
  }

  @Test
  void committedLocalFixtureFreezesFieldTypesAndPolicies() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/connector-v1.snbt")) {
      assertNotNull(input);
      var tag = TagParser.parseTag(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      var state = ConnectorData.read(tag);
      assertEquals(ResourceKind.FLUID, state.settings().kind());
      assertEquals(ConnectorSettings.Access.READ_ONLY, state.settings().access());
      assertEquals(7, state.settings().priority());
      assertEquals(2, state.lease().generation());
      assertEquals(3, state.generation());
      assertEquals(tag, ConnectorData.write(state));
    }
  }

  @Test
  void policiesAndFaultEvidenceRoundTripAndUnknownFieldsReject() {
    var lease =
        new NodeRegistry.Handle(
            UUID.randomUUID(), UUID.randomUUID(), 3, NodeRegistry.Kind.CONNECTOR);
    var state =
        new ConnectorData.State(
            lease,
            new ConnectorSettings(
                ResourceKind.ITEM, ConnectorSettings.Access.READ_ONLY, -4, 2, 3, 32),
            7,
            "uncertain external extraction");
    var tag = ConnectorData.write(state);
    assertEquals(state, ConnectorData.read(tag));
    var future = tag.copy();
    future.putInt("Schema", 2);
    assertThrows(IllegalArgumentException.class, () -> ConnectorData.read(future));
    tag.putInt("UnknownPolicy", 1);
    assertThrows(IllegalArgumentException.class, () -> ConnectorData.read(tag));
  }

  @Test
  void selectedFluidWindowPersistsWithoutChangingLegacyWholeCapabilityBytes() {
    var lease =
        new NodeRegistry.Handle(
            UUID.randomUUID(), UUID.randomUUID(), 1, NodeRegistry.Kind.CONNECTOR);
    var settings =
        new ConnectorSettings(
            ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 0, Integer.MAX_VALUE, 1, 1000);
    var state = new ConnectorData.State(lease, settings, 1, "");
    assertEquals(state, ConnectorData.read(ConnectorData.write(state)));
    assertFalse(settings.wholeFluidHandler());
    assertTrue(ConnectorSettings.defaults(ResourceKind.FLUID).wholeFluidHandler());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectorSettings(
                ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 0, -1, 1, 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectorSettings(
                ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 0, 1, 65, 1000));
  }

  @Test
  void limitsWrongKindAndOverflowGenerationReject() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectorSettings(
                ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 1001, 0, 64, 64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectorSettings(
                ResourceKind.ENERGY, ConnectorSettings.Access.READ_WRITE, 0, 1, 64, 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConnectorSettings(
                ResourceKind.ITEM, ConnectorSettings.Access.READ_WRITE, 0, 0, 65, 64));
    var wrong =
        new ConnectorData.State(
            new NodeRegistry.Handle(
                UUID.randomUUID(), UUID.randomUUID(), 1, NodeRegistry.Kind.GATEWAY),
            ConnectorSettings.defaults(ResourceKind.ITEM),
            1,
            "");
    assertThrows(
        IllegalArgumentException.class, () -> ConnectorData.read(ConnectorData.write(wrong)));
    var valid =
        ConnectorData.write(
            new ConnectorData.State(
                new NodeRegistry.Handle(
                    UUID.randomUUID(), UUID.randomUUID(), 1, NodeRegistry.Kind.CONNECTOR),
                ConnectorSettings.defaults(ResourceKind.ENERGY),
                1,
                ""));
    valid.putLong("Generation", 0);
    assertThrows(IllegalArgumentException.class, () -> ConnectorData.read(valid));
  }
}
