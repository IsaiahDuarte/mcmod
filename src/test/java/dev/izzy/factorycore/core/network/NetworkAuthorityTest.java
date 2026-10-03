package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.network.NetworkPermissions.*;
import dev.izzy.factorycore.core.network.NetworkTopology.*;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.scheduling.FairScheduler;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NetworkAuthorityTest {
  private static UUID id(int value) {
    return new UUID(0, value);
  }

  private static Actor actor(int value) {
    return new Actor(id(value), false);
  }

  private static final ResourceKey IRON =
      new ResourceKey(ResourceKind.ITEM, "minecraft:iron_ingot", new byte[0]);

  private static NetworkTopology tree() {
    var topology = new NetworkTopology(id(1000), 8);
    topology.putNode(new Node(id(1), Role.ROOT, "", true));
    for (int i = 2; i <= 4; i++) topology.putNode(new Node(id(i), Role.PROGRAM, "", true));
    for (int i = 11; i <= 14; i++) {
      topology.putNode(new Node(id(i), Role.INTERFACE, "smelters", true));
      topology.connect(id(i - 10), id(i));
    }
    topology.putGateway(new Gateway(id(101), id(1), id(2), GatewayPolicy.open(), true));
    topology.putGateway(new Gateway(id(102), id(2), id(3), GatewayPolicy.open(), true));
    topology.putGateway(new Gateway(id(103), id(1), id(4), GatewayPolicy.open(), true));
    NetworkTopologyTest.finish(topology, 256);
    return topology;
  }

  private static NetworkAuthority authority(
      NetworkTopology topology, NetworkPermissions permissions) {
    var authority = new NetworkAuthority(topology);
    authority.register(permissions);
    return authority;
  }

  @Test
  void defaultsOperatorsAndExplicitTransferKeepSeparateGrants() {
    var changes = new AtomicInteger();
    var grants = new NetworkPermissions(id(1), id(20), changes::incrementAndGet);
    for (var permission : Permission.values()) {
      assertTrue(grants.allows(actor(20), permission));
      assertFalse(grants.allows(actor(21), permission));
    }
    var operator = new Actor(id(22), true);
    assertTrue(grants.allows(operator, Permission.MANAGE));
    assertFalse(grants.allows(operator, Permission.WITHDRAW));
    assertEquals(
        Change.DENIED, grants.setGrants(actor(21), id(21), EnumSet.allOf(Permission.class)));
    assertEquals(
        Change.OK, grants.setGrants(operator, id(21), Set.of(Permission.VIEW, Permission.DEPOSIT)));
    assertTrue(grants.allows(actor(21), Permission.DEPOSIT));
    assertFalse(grants.allows(actor(21), Permission.WITHDRAW));
    assertEquals(Change.OWNER_IMPLICIT, grants.setGrants(actor(20), id(20), Set.of()));
    var state = grants.snapshot();
    assertThrows(UnsupportedOperationException.class, () -> state.grants().clear());
    assertThrows(UnsupportedOperationException.class, () -> state.grants().get(id(21)).clear());
    var restored = new NetworkPermissions(state, () -> {});
    assertEquals(state, restored.snapshot());
    assertEquals(Change.OK, grants.transferOwnership(operator, id(21)));
    assertFalse(grants.allows(actor(20), Permission.VIEW));
    assertTrue(grants.allows(actor(21), Permission.MANAGE));
    assertEquals(2, changes.get());
    assertEquals(3, grants.snapshot().generation());
  }

  @Test
  void grantBoundsRevocationAndGenerationOverflowPreserveState() {
    var grants = new NetworkPermissions(id(1), id(20), () -> {});
    for (int i = 100; i < 100 + NetworkPermissions.MAX_PRINCIPALS; i++)
      assertEquals(Change.OK, grants.setGrants(actor(20), id(i), Set.of(Permission.VIEW)));
    var full = grants.snapshot();
    assertEquals(Change.LIMIT, grants.setGrants(actor(20), id(500), Set.of(Permission.VIEW)));
    assertEquals(full, grants.snapshot());
    grants.setGrants(actor(20), id(100), Set.of());
    assertFalse(grants.allows(actor(100), Permission.VIEW));
    assertEquals(Change.OK, grants.setGrants(actor(20), id(500), Set.of(Permission.VIEW)));
    var exhausted =
        new NetworkPermissions(new State(id(1), id(20), Long.MAX_VALUE, Map.of()), () -> {});
    assertThrows(ArithmeticException.class, () -> exhausted.transferOwnership(actor(20), id(21)));
    assertThrows(
        ArithmeticException.class,
        () -> exhausted.setGrants(actor(20), id(21), Set.of(Permission.VIEW)));
    assertEquals(id(20), exhausted.snapshot().owner());
    assertTrue(exhausted.snapshot().grants().isEmpty());
  }

  @Test
  void reusedLabelsNeverCrossGatewayEvenForOwnerAndEmptyAmbiguousQueriesAreExplicit() {
    var topology = tree();
    var grants = new NetworkPermissions(id(1), id(20), () -> {});
    var authority = authority(topology, grants);
    var root = topology.membership(id(1)).scope();
    var branch = topology.membership(id(2)).scope();
    var sibling = topology.membership(id(4)).scope();
    assertEquals(
        NetworkAuthority.Decision.DENIED,
        authority.authorizeMachine(actor(20), root, root, Permission.EDIT));
    assertEquals(
        List.of(topology.membership(id(12)).scope()),
        authority.machines(actor(20), branch, "smelters", null, 64).machines());
    assertEquals(
        List.of(topology.membership(id(11)).scope()),
        authority.machines(actor(20), root, "smelters", null, 64).machines());
    assertEquals(
        NetworkAuthority.Decision.DENIED,
        authority.authorizeMachine(
            actor(20), branch, topology.membership(id(14)).scope(), Permission.EDIT));
    assertEquals(
        NetworkAuthority.Decision.DENIED,
        authority.authorizeMachine(
            actor(20), root, topology.membership(id(12)).scope(), Permission.EDIT));
    assertEquals(
        NetworkAuthority.Decision.EMPTY,
        authority.oneMachine(actor(20), sibling, "Smelters").decision());
    assertEquals(
        NetworkAuthority.Decision.EMPTY, authority.oneMachine(actor(20), sibling, "").decision());
    topology.putNode(new Node(id(15), Role.INTERFACE, "smelters", true));
    topology.connect(id(2), id(15));
    NetworkTopologyTest.finish(topology, 256);
    branch = topology.membership(id(2)).scope();
    assertEquals(
        NetworkAuthority.Decision.AMBIGUOUS,
        authority.oneMachine(actor(20), branch, "smelters").decision());
    var first = authority.machines(actor(20), branch, "smelters", null, 1);
    assertTrue(first.more());
    assertEquals(id(12), first.machines().getFirst().node());
    var second = authority.machines(actor(20), branch, "smelters", id(12), 1);
    assertFalse(second.more());
    assertEquals(id(15), second.machines().getFirst().node());
    assertEquals(
        NetworkAuthority.Decision.DENIED,
        authority.machines(actor(21), branch, "smelters", null, 64).decision());
  }

  @Test
  void inheritedViewsIntersectAllAncestorsWithoutEnumeratingChildPrivateStock() {
    var topology = tree();
    var grants = new NetworkPermissions(id(1), id(20), () -> {});
    var authority = authority(topology, grants);
    var branch = topology.membership(id(3)).scope();
    assertEquals(
        List.of(id(102), id(101), id(1)),
        authority.storage(actor(20), branch, IRON, Permission.WITHDRAW).segments());
    assertEquals(
        List.of(id(1)),
        authority
            .storage(actor(20), topology.membership(id(1)).scope(), IRON, Permission.VIEW)
            .segments());
    var noWithdraw =
        new GatewayPolicy(
            EnumSet.allOf(Permission.class),
            Set.of(Permission.VIEW, Permission.DEPOSIT),
            Set.of(ResourceKind.ITEM));
    topology.putGateway(new Gateway(id(101), id(1), id(2), noWithdraw, true));
    NetworkTopologyTest.finish(topology, 256);
    branch = topology.membership(id(3)).scope();
    assertEquals(
        List.of(id(102), id(101)),
        authority.storage(actor(20), branch, IRON, Permission.WITHDRAW).segments());
    assertEquals(
        List.of(id(102), id(101), id(1)),
        authority.storage(actor(20), branch, IRON, Permission.VIEW).segments());
    var fluid = new ResourceKey(ResourceKind.FLUID, "minecraft:water", new byte[0]);
    assertEquals(
        List.of(id(102), id(101)),
        authority.storage(actor(20), branch, fluid, Permission.VIEW).segments());
    var branchDenied =
        new GatewayPolicy(
            Set.of(Permission.VIEW),
            EnumSet.allOf(Permission.class),
            EnumSet.allOf(ResourceKind.class));
    topology.putGateway(new Gateway(id(102), id(2), id(3), branchDenied, true));
    NetworkTopologyTest.finish(topology, 256);
    assertEquals(
        NetworkAuthority.Decision.DENIED,
        authority.authorize(actor(20), topology.membership(id(3)).scope(), Permission.DEPLOY));
    assertEquals(
        NetworkAuthority.Decision.ALLOWED,
        authority.authorize(actor(20), topology.membership(id(2)).scope(), Permission.DEPLOY));
    var state = grants.snapshot();
    var restricted = topology.membership(id(3)).scope();
    assertEquals(
        Change.DENIED, authority.setGrants(actor(20), restricted, id(21), Set.of(Permission.VIEW)));
    assertEquals(Change.DENIED, authority.transferOwnership(actor(20), restricted, id(21)));
    assertEquals(state, grants.snapshot(), "Branch management denial preserved ownership/grants");
    var root = topology.membership(id(1)).scope();
    assertEquals(Change.OK, authority.setGrants(actor(20), root, id(21), Set.of(Permission.VIEW)));
    assertEquals(Change.OK, authority.transferOwnership(actor(20), root, id(22)));
    assertEquals(
        NetworkAuthority.Decision.DENIED, authority.authorize(actor(20), root, Permission.VIEW));
    assertEquals(
        NetworkAuthority.Decision.ALLOWED, authority.authorize(actor(22), root, Permission.MANAGE));
  }

  @Test
  void queuedWorkRechecksRevocationAndTopologyImmediatelyBeforeMutation() {
    var topology = tree();
    var grants = new NetworkPermissions(id(1), id(20), () -> {});
    var authority = authority(topology, grants);
    grants.setGrants(actor(20), id(21), Set.of(Permission.WITHDRAW));
    var scheduler =
        new FairScheduler(
            FairScheduler.Limits.defaults(), () -> 0, failure -> fail(failure.diagnostic()));
    var scope = topology.membership(id(2)).scope();
    var mutations = new AtomicInteger();
    var results = new ArrayList<NetworkAuthority.Decision>();
    Runnable request =
        () -> {
          var decision = authority.authorize(actor(21), scope, Permission.WITHDRAW);
          results.add(decision);
          if (decision == NetworkAuthority.Decision.ALLOWED) mutations.incrementAndGet();
        };
    assertEquals(
        FairScheduler.Admission.ACCEPTED, scheduler.submit(id(1), id(21), "withdraw", 1, request));
    grants.setGrants(actor(20), id(21), Set.of());
    scheduler.tick();
    assertEquals(0, mutations.get());
    assertEquals(List.of(NetworkAuthority.Decision.DENIED), results);
    grants.setGrants(actor(20), id(21), Set.of(Permission.WITHDRAW));
    scheduler.submit(id(1), id(21), "withdraw", 1, request);
    topology.removeGateway(id(101));
    scheduler.tick();
    assertEquals(0, mutations.get());
    assertEquals(NetworkAuthority.Decision.INVALID_SCOPE, results.getLast());
  }
}
