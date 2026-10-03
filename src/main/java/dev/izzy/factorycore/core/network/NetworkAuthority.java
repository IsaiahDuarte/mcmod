package dev.izzy.factorycore.core.network;

import dev.izzy.factorycore.core.network.NetworkPermissions.Actor;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology.Scope;
import dev.izzy.factorycore.core.network.NetworkTopology.Status;
import dev.izzy.factorycore.core.resource.ResourceKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared execution-time scope and current-grant checks for future player/preset/guest/craft
 * adapters.
 */
public final class NetworkAuthority {
  public enum Decision {
    ALLOWED,
    DENIED,
    INVALID_SCOPE,
    EMPTY,
    AMBIGUOUS
  }

  public record MachineQuery(Decision decision, List<Scope> machines, boolean more) {
    public MachineQuery {
      machines = List.copyOf(machines);
    }
  }

  public record StorageQuery(Decision decision, List<UUID> segments) {
    public StorageQuery {
      segments = List.copyOf(segments);
    }
  }

  private final Thread thread = Thread.currentThread();
  private final NetworkTopology topology;
  private final Map<UUID, NetworkPermissions> permissions = new HashMap<>();

  public NetworkAuthority(NetworkTopology topology) {
    this.topology = Objects.requireNonNull(topology);
  }

  public void register(NetworkPermissions network) {
    checkThread();
    var id = network.network();
    if (permissions.containsKey(id))
      throw new IllegalStateException("Network authority already registered");
    if (permissions.size() == 1024) throw new IllegalStateException("Network authority limit");
    permissions.put(id, network);
  }

  public Decision authorize(Actor actor, Scope origin, Permission operation) {
    checkThread();
    Objects.requireNonNull(actor);
    Objects.requireNonNull(operation);
    if (topology.validate(origin) != Status.VALID) return Decision.INVALID_SCOPE;
    var grants = permissions.get(origin.network());
    if (grants == null || !grants.allows(actor, operation)) return Decision.DENIED;
    for (var ancestor : topology.ancestors(origin))
      if (!ancestor.policy().branchPermissions().contains(operation)) return Decision.DENIED;
    return Decision.ALLOWED;
  }

  public Decision authorizeMachine(Actor actor, Scope origin, Scope target, Permission operation) {
    checkThread();
    if (topology.validate(target) != Status.VALID) return Decision.INVALID_SCOPE;
    if (!topology.isInterface(target)) return Decision.DENIED;
    var result = authorize(actor, origin, operation);
    if (result != Decision.ALLOWED) return result;
    return origin.network().equals(target.network()) && origin.segment().equals(target.segment())
        ? Decision.ALLOWED
        : Decision.DENIED;
  }

  public NetworkPermissions.Change setGrants(
      Actor actor, Scope origin, UUID principal, java.util.Set<Permission> grants) {
    checkThread();
    if (authorize(actor, origin, Permission.MANAGE) != Decision.ALLOWED)
      return NetworkPermissions.Change.DENIED;
    return permissions.get(origin.network()).setGrants(actor, principal, grants);
  }

  public NetworkPermissions.Change transferOwnership(Actor actor, Scope origin, UUID nextOwner) {
    checkThread();
    if (authorize(actor, origin, Permission.MANAGE) != Decision.ALLOWED)
      return NetworkPermissions.Change.DENIED;
    return permissions.get(origin.network()).transferOwnership(actor, nextOwner);
  }

  public MachineQuery machines(Actor actor, Scope origin, String label, UUID after, int limit) {
    checkThread();
    var result = authorize(actor, origin, Permission.VIEW);
    if (result != Decision.ALLOWED) return new MachineQuery(result, List.of(), false);
    var page = topology.machines(origin, label, after, limit);
    return new MachineQuery(
        page.machines().isEmpty() ? Decision.EMPTY : Decision.ALLOWED,
        page.machines(),
        page.more());
  }

  public MachineQuery oneMachine(Actor actor, Scope origin, String label) {
    var result = machines(actor, origin, label, null, 2);
    return result.machines().size() > 1
        ? new MachineQuery(Decision.AMBIGUOUS, List.of(), false)
        : result;
  }

  /** Local plus authorized ancestors, nearest first; never child-private segments. */
  public StorageQuery storage(
      Actor actor, Scope origin, ResourceKey resource, Permission operation) {
    checkThread();
    Objects.requireNonNull(resource);
    if (operation != Permission.VIEW
        && operation != Permission.DEPOSIT
        && operation != Permission.WITHDRAW
        && operation != Permission.CRAFT)
      throw new IllegalArgumentException("Expected storage operation");
    var result = authorize(actor, origin, operation);
    if (result != Decision.ALLOWED) return new StorageQuery(result, List.of());
    var segments = new ArrayList<UUID>();
    segments.add(origin.segment());
    for (var ancestor : topology.ancestors(origin)) {
      var policy = ancestor.policy();
      if (!policy.inheritedPermissions().contains(operation)
          || !policy.inheritedKinds().contains(resource.kind())) break;
      segments.add(ancestor.segment());
    }
    return new StorageQuery(Decision.ALLOWED, segments);
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Authority requires owner thread");
  }
}
