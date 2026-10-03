package dev.izzy.factorycore.core.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Current, private-by-default grants; an operator may administer without bypassing resource scope.
 */
public final class NetworkPermissions {
  public static final int MAX_PRINCIPALS = 256;

  public enum Permission {
    VIEW,
    DEPOSIT,
    WITHDRAW,
    CRAFT,
    EDIT,
    DEPLOY,
    MANAGE
  }

  /** Created by trusted server authentication, never from a client-supplied operator flag. */
  public record Actor(UUID id, boolean operator) {
    public Actor {
      Objects.requireNonNull(id);
    }
  }

  public enum Change {
    OK,
    DENIED,
    LIMIT,
    OWNER_IMPLICIT
  }

  public record State(
      UUID network, UUID owner, long generation, Map<UUID, Set<Permission>> grants) {
    public State {
      Objects.requireNonNull(network);
      Objects.requireNonNull(owner);
      if (generation <= 0 || grants.size() > MAX_PRINCIPALS)
        throw new IllegalArgumentException("Invalid permission state bounds");
      var copy = new HashMap<UUID, Set<Permission>>();
      grants.forEach(
          (principal, permissions) -> {
            if (Objects.requireNonNull(principal).equals(owner) || permissions.isEmpty())
              throw new IllegalArgumentException("Owner/empty grants are implicit");
            copy.put(principal, Set.copyOf(permissions));
          });
      grants = Map.copyOf(copy);
    }
  }

  private final Thread thread = Thread.currentThread();
  private final UUID network;
  private final Runnable changed;
  private final Map<UUID, Set<Permission>> grants = new HashMap<>();
  private UUID owner;
  private long generation;

  public NetworkPermissions(UUID network, UUID owner, Runnable changed) {
    this(new State(network, owner, 1, Map.of()), changed);
  }

  public NetworkPermissions(State state, Runnable changed) {
    network = state.network();
    owner = state.owner();
    generation = state.generation();
    grants.putAll(state.grants());
    this.changed = Objects.requireNonNull(changed);
  }

  public UUID network() {
    checkThread();
    return network;
  }

  public boolean allows(Actor actor, Permission permission) {
    checkThread();
    Objects.requireNonNull(permission);
    return actor.id().equals(owner)
        || (permission == Permission.MANAGE && actor.operator())
        || grants.getOrDefault(actor.id(), Set.of()).contains(permission);
  }

  public Change setGrants(Actor administrator, UUID principal, Set<Permission> permissions) {
    checkThread();
    Objects.requireNonNull(principal);
    var next = Set.copyOf(permissions);
    if (!allows(administrator, Permission.MANAGE)) return Change.DENIED;
    if (principal.equals(owner)) return Change.OWNER_IMPLICIT;
    if (next.equals(grants.getOrDefault(principal, Set.of()))) return Change.OK;
    if (!next.isEmpty() && !grants.containsKey(principal) && grants.size() == MAX_PRINCIPALS)
      return Change.LIMIT;
    long nextGeneration = Math.incrementExact(generation);
    if (next.isEmpty()) grants.remove(principal);
    else grants.put(principal, next);
    generation = nextGeneration;
    changed.run();
    return Change.OK;
  }

  public Change transferOwnership(Actor administrator, UUID nextOwner) {
    checkThread();
    Objects.requireNonNull(nextOwner);
    if (!allows(administrator, Permission.MANAGE)) return Change.DENIED;
    if (owner.equals(nextOwner)) return Change.OK;
    long nextGeneration = Math.incrementExact(generation);
    grants.remove(nextOwner);
    owner = nextOwner;
    generation = nextGeneration;
    changed.run();
    return Change.OK;
  }

  public State snapshot() {
    checkThread();
    return new State(network, owner, generation, grants);
  }

  private void checkThread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Permissions require owner thread");
  }
}
