package dev.izzy.factorycore.core.network;

import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.resource.ResourceKind;
import java.util.EnumSet;
import java.util.Set;

/**
 * Branch grants and inherited stock restrictions are distinct; all ancestor constraints intersect.
 */
public record GatewayPolicy(
    Set<Permission> branchPermissions,
    Set<Permission> inheritedPermissions,
    Set<ResourceKind> inheritedKinds) {
  public GatewayPolicy {
    branchPermissions = Set.copyOf(branchPermissions);
    inheritedPermissions = Set.copyOf(inheritedPermissions);
    inheritedKinds = Set.copyOf(inheritedKinds);
  }

  public static GatewayPolicy open() {
    return new GatewayPolicy(
        EnumSet.allOf(Permission.class),
        EnumSet.allOf(Permission.class),
        EnumSet.allOf(ResourceKind.class));
  }
}
