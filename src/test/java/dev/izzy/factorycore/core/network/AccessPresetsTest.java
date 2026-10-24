package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.izzy.factorycore.core.network.GatewayPolicy.Preset;
import dev.izzy.factorycore.core.network.NetworkPermissions.AccessRole;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** U8: each gateway preset and access role is one exact policy or grant set. */
class AccessPresetsTest {
  private static final Set<Permission> ALL = EnumSet.allOf(Permission.class);

  @Test
  void gatewayPresetsMapToExactPolicies() {
    assertEquals(
        new GatewayPolicy(ALL, ALL, EnumSet.allOf(ResourceKind.class)), Preset.SHARED.policy());
    assertEquals(GatewayPolicy.open(), Preset.SHARED.policy());
    assertEquals(
        new GatewayPolicy(ALL, Set.of(Permission.VIEW), EnumSet.allOf(ResourceKind.class)),
        Preset.READ_ONLY.policy());
    assertEquals(new GatewayPolicy(ALL, Set.of(), Set.of()), Preset.ISOLATED.policy());
  }

  @Test
  void gatewayPresetRecognitionIsExactAndRoundTripsTheEditorMask() {
    for (var preset : Preset.values()) {
      assertEquals(preset, Preset.matching(preset.policy()));
      assertEquals(preset, Preset.matching(GatewayPolicy.fromMask(preset.policy().mask())));
    }
    // One toggled bit away from any preset is Custom.
    for (var preset : Preset.values())
      for (int bit = 0; bit < GatewayPolicy.BITS; bit++)
        assertNull(Preset.matching(preset.policy().toggled(bit)), preset + " bit " + bit);
  }

  @Test
  void accessRolesMapToExactGrantSets() {
    assertEquals(Set.of(Permission.VIEW), AccessRole.GUEST.grants());
    assertEquals(
        Set.of(Permission.VIEW, Permission.DEPOSIT, Permission.WITHDRAW, Permission.CRAFT),
        AccessRole.MEMBER.grants());
    assertEquals(
        Set.of(
            Permission.VIEW,
            Permission.DEPOSIT,
            Permission.WITHDRAW,
            Permission.CRAFT,
            Permission.EDIT,
            Permission.DEPLOY),
        AccessRole.BUILDER.grants());
    assertEquals(ALL, AccessRole.ADMIN.grants());
  }

  @Test
  void accessRoleRecognitionIsExact() {
    for (var role : AccessRole.values())
      assertEquals(role, AccessRole.matching(EnumSet.copyOf(role.grants())));
    assertNull(AccessRole.matching(Set.of()));
    assertNull(AccessRole.matching(Set.of(Permission.VIEW, Permission.DEPOSIT)));
    assertNull(AccessRole.matching(Set.of(Permission.MANAGE)));
  }
}
