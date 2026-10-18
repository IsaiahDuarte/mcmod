package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatewayPolicyTest {
  @Test
  void maskRoundTripsEveryCombinationOfTheSeventeenEditorBits() {
    assertEquals(17, GatewayPolicy.BITS);
    for (int mask = 0; mask < 1 << GatewayPolicy.BITS; mask += 97)
      assertEquals(mask, GatewayPolicy.fromMask(mask).mask());
    assertEquals((1 << 17) - 1, GatewayPolicy.open().mask());
  }

  @Test
  void toggledFlipsExactlyOneSectionEntry() {
    var open = GatewayPolicy.open();
    var noBranchCraft = open.toggled(Permission.CRAFT.ordinal());
    assertFalse(noBranchCraft.branchPermissions().contains(Permission.CRAFT));
    assertEquals(open.inheritedPermissions(), noBranchCraft.inheritedPermissions());
    var noInheritedWithdraw = open.toggled(7 + Permission.WITHDRAW.ordinal());
    assertFalse(noInheritedWithdraw.inheritedPermissions().contains(Permission.WITHDRAW));
    assertTrue(noInheritedWithdraw.branchPermissions().contains(Permission.WITHDRAW));
    var noFluids = open.toggled(14 + ResourceKind.FLUID.ordinal());
    assertEquals(Set.of(ResourceKind.ITEM, ResourceKind.ENERGY), noFluids.inheritedKinds());
    assertEquals(open, noFluids.toggled(14 + ResourceKind.FLUID.ordinal()));
  }

  @Test
  void outOfRangeBitsAndMasksAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> GatewayPolicy.open().toggled(17));
    assertThrows(IllegalArgumentException.class, () -> GatewayPolicy.open().toggled(-1));
    assertThrows(IllegalArgumentException.class, () -> GatewayPolicy.fromMask(1 << 17));
  }
}
