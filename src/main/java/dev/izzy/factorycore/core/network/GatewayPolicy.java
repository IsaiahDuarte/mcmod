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

  /** Editor bit count: branch permissions, then inherited permissions, then inherited kinds. */
  public static final int BITS = 2 * Permission.values().length + ResourceKind.values().length;

  /** Stable editor encoding; bit order follows the enum declaration order. */
  public int mask() {
    int mask = 0, bit = 0;
    for (var permission : Permission.values())
      if (branchPermissions.contains(permission)) mask |= 1 << bit++;
      else bit++;
    for (var permission : Permission.values())
      if (inheritedPermissions.contains(permission)) mask |= 1 << bit++;
      else bit++;
    for (var kind : ResourceKind.values())
      if (inheritedKinds.contains(kind)) mask |= 1 << bit++;
      else bit++;
    return mask;
  }

  public static GatewayPolicy fromMask(int mask) {
    if (mask < 0 || mask >= 1 << BITS) throw new IllegalArgumentException("Invalid policy mask");
    var branch = EnumSet.noneOf(Permission.class);
    var inherited = EnumSet.noneOf(Permission.class);
    var kinds = EnumSet.noneOf(ResourceKind.class);
    int bit = 0;
    for (var permission : Permission.values()) if ((mask & 1 << bit++) != 0) branch.add(permission);
    for (var permission : Permission.values())
      if ((mask & 1 << bit++) != 0) inherited.add(permission);
    for (var kind : ResourceKind.values()) if ((mask & 1 << bit++) != 0) kinds.add(kind);
    return new GatewayPolicy(branch, inherited, kinds);
  }

  /** This policy with one editor bit flipped. */
  public GatewayPolicy toggled(int bit) {
    if (bit < 0 || bit >= BITS) throw new IllegalArgumentException("Invalid policy bit");
    return fromMask(mask() ^ 1 << bit);
  }

  public static GatewayPolicy open() {
    return new GatewayPolicy(
        EnumSet.allOf(Permission.class),
        EnumSet.allOf(Permission.class),
        EnumSet.allOf(ResourceKind.class));
  }
}
