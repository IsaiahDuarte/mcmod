package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NetworkPermissions.Actor;
import dev.izzy.factorycore.core.network.NetworkPermissions.Change;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkPermissionBudgetTest {
  @Test
  void deniedAdmissionAndOverflowPreserveOwnerGrantsAndAccounting() {
    UUID owner = new UUID(0, 1);
    UUID principal = new UUID(0, 2);
    var actor = new Actor(owner, false);
    int[] bindings = {0};
    int[] notifications = {0};
    var permissions =
        new NetworkPermissions(
            new NetworkPermissions.State(new UUID(0, 3), owner, 1, Map.of()),
            () -> notifications[0]++,
            delta -> bindings[0] + delta <= 1,
            delta -> bindings[0] += delta);
    assertEquals(Change.OK, permissions.setGrants(actor, principal, Set.of(Permission.VIEW)));
    var before = permissions.snapshot();
    assertEquals(
        Change.LIMIT, permissions.setGrants(actor, new UUID(0, 4), Set.of(Permission.DEPOSIT)));
    assertEquals(before, permissions.snapshot());
    assertEquals(1, bindings[0]);
    assertEquals(1, notifications[0]);
    assertEquals(Change.OK, permissions.setGrants(actor, principal, Set.of(Permission.DEPOSIT)));
    assertEquals(1, bindings[0]);
    assertEquals(Change.OK, permissions.transferOwnership(actor, principal));
    assertEquals(0, bindings[0]);
    assertFalse(permissions.allows(actor, Permission.MANAGE));
    int[] callbacks = {0};
    var overflow =
        new NetworkPermissions(
            new NetworkPermissions.State(
                new UUID(0, 3), owner, Long.MAX_VALUE, Map.of(principal, Set.of(Permission.VIEW))),
            () -> callbacks[0]++,
            delta -> {
              callbacks[0]++;
              return true;
            },
            delta -> callbacks[0]++);
    var original = overflow.snapshot();
    assertThrows(ArithmeticException.class, () -> overflow.setGrants(actor, principal, Set.of()));
    assertThrows(ArithmeticException.class, () -> overflow.transferOwnership(actor, principal));
    assertEquals(original, overflow.snapshot());
    assertEquals(0, callbacks[0]);
  }
}
