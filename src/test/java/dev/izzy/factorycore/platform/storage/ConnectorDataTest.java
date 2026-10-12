package dev.izzy.factorycore.platform.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectorDataTest {
  @Test
  void committedLocalFixtureFreezesFieldTypesAndPolicies() throws Exception {
    try (var input = getClass().getResourceAsStream("/persistence/connector-v1.snbt")) {
      assertNotNull(input);
      var tag =
          net.minecraft.nbt.TagParser.parseTag(
              new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
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
                ResourceKind.FLUID, ConnectorSettings.Access.READ_WRITE, 0, 1, 64, 1000));
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
