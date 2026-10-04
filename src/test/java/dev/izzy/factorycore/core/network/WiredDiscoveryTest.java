package dev.izzy.factorycore.core.network;

import static org.junit.jupiter.api.Assertions.*;

import dev.izzy.factorycore.core.scheduling.FairScheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WiredDiscoveryTest {
  private static UUID id(int value) {
    return new UUID(0, value);
  }

  private static NodeRegistry.Position pos(int value) {
    return new NodeRegistry.Position("minecraft:overworld", value);
  }

  private static WiredDiscovery.Wire wire(int value, NetworkTopology.Role role, int... neighbors) {
    var ports = new ArrayList<WiredDiscovery.Port>();
    for (var neighbor : neighbors) ports.add(new WiredDiscovery.Port(pos(neighbor), id(value)));
    return new WiredDiscovery.Wire(
        List.of(new NetworkTopology.Node(id(value), role, "", true)), ports, null);
  }

  private static WiredDiscovery job(
      Map<NodeRegistry.Position, WiredDiscovery.Wire> world, int... seeds) {
    var roots = new ArrayList<NodeRegistry.Position>();
    for (var seed : seeds) roots.add(pos(seed));
    return new WiredDiscovery(UUID.randomUUID(), 8, roots.iterator(), world::get, () -> 1);
  }

  private static WiredDiscovery.Advance finish(WiredDiscovery job) {
    for (int i = 0; i < 200000; i++) {
      var result = job.advance(1);
      assertTrue(result.work() <= 1);
      if (result.state() != WiredDiscovery.State.DISCOVERING
          && result.state() != WiredDiscovery.State.VALIDATING) return result;
    }
    throw new AssertionError("Discovery did not finish under admitted bounds");
  }

  private static Map<NodeRegistry.Position, WiredDiscovery.Wire> branch() {
    var world = new HashMap<NodeRegistry.Position, WiredDiscovery.Wire>();
    world.put(pos(1), wire(1, NetworkTopology.Role.ROOT, 2));
    world.put(
        pos(2),
        new WiredDiscovery.Wire(
            List.of(
                new NetworkTopology.Node(id(20), NetworkTopology.Role.CABLE, "", true),
                new NetworkTopology.Node(id(21), NetworkTopology.Role.CABLE, "", true)),
            List.of(
                new WiredDiscovery.Port(pos(1), id(20)), new WiredDiscovery.Port(pos(3), id(21))),
            new NetworkTopology.Gateway(id(22), id(20), id(21), GatewayPolicy.open(), true)));
    world.put(pos(3), wire(3, NetworkTopology.Role.STORAGE, 2));
    world.put(pos(4), wire(4, NetworkTopology.Role.STORAGE, 2));
    return world;
  }

  @Test
  void reciprocalPortsKeepGatewaySidesAndChildStoragePrivate() {
    var world = branch();
    var discovery = job(world, 1, 4);
    assertThrows(IllegalStateException.class, discovery::publishedTopology);
    assertEquals(WiredDiscovery.State.PUBLISHED, finish(discovery).state());
    var topology = discovery.publishedTopology();
    var root = topology.membership(id(1)).scope();
    var child = topology.membership(id(3)).scope();
    assertEquals(NetworkTopology.Status.NO_ROOT, topology.membership(id(4)).status());
    assertEquals(root.network(), child.network());
    assertNotEquals(root.segment(), child.segment());
    assertEquals(id(22), child.segment());
    assertEquals(
        List.of(new NetworkTopology.Ancestor(root.segment(), GatewayPolicy.open())),
        topology.ancestors(child));
    assertNull(discovery.captured(pos(99)));
  }

  @Test
  void cachedUnloadSuspendsDescendantsWithoutDisablingUpstream() {
    var world = branch();
    world.put(pos(2), world.get(pos(2)).unloaded());
    var discovery = job(world, 1);
    finish(discovery);
    assertEquals(
        NetworkTopology.Status.VALID, discovery.publishedTopology().membership(id(1)).status());
    assertEquals(
        NetworkTopology.Status.UNLOADED, discovery.publishedTopology().membership(id(3)).status());
    world = branch();
    world.put(pos(1), world.get(pos(1)).unloaded());
    discovery = job(world, 3);
    finish(discovery);
    assertEquals(
        NetworkTopology.Status.UNLOADED, discovery.publishedTopology().membership(id(3)).status());
    world.remove(pos(2));
    discovery = job(world, 1, 3);
    finish(discovery);
    assertEquals(
        NetworkTopology.Status.NO_ROOT, discovery.publishedTopology().membership(id(3)).status());
  }

  @Test
  void physicalBypassAndIdentityCollisionRemainUnavailable() {
    var world = branch();
    world.put(pos(1), wire(1, NetworkTopology.Role.ROOT, 2, 3));
    world.put(pos(3), wire(3, NetworkTopology.Role.STORAGE, 2, 1));
    var discovery = job(world, 1);
    finish(discovery);
    assertEquals(
        NetworkTopology.Status.GATEWAY_BYPASS,
        discovery.publishedTopology().membership(id(3)).status());
    world.put(
        pos(3),
        new WiredDiscovery.Wire(
            List.of(new NetworkTopology.Node(id(1), NetworkTopology.Role.STORAGE, "", true)),
            List.of(new WiredDiscovery.Port(pos(2), id(1))),
            null));
    discovery = job(world, 1);
    assertEquals(WiredDiscovery.State.FAILED, finish(discovery).state());
    assertThrows(IllegalStateException.class, discovery::publishedTopology);
    var broken =
        new WiredDiscovery(
            UUID.randomUUID(),
            8,
            List.of(pos(1)).iterator(),
            p -> {
              throw new IllegalStateException("x".repeat(1000));
            },
            () -> 1);
    var failure = finish(broken);
    assertEquals(WiredDiscovery.State.FAILED, failure.state());
    assertEquals(512, failure.diagnostic().length());
    assertThrows(IllegalStateException.class, broken::publishedTopology);
  }

  @Test
  void epochsAndCancellationInvalidateEvenCompletedPublication() {
    var world = branch();
    long[] epoch = {1};
    var discovery =
        new WiredDiscovery(
            UUID.randomUUID(), 8, List.of(pos(1)).iterator(), world::get, () -> epoch[0]);
    finish(discovery);
    var topology = discovery.publishedTopology();
    var old = topology.membership(id(3)).scope();
    epoch[0]++;
    assertThrows(IllegalStateException.class, discovery::publishedTopology);
    assertEquals(NetworkTopology.Status.STALE, topology.validate(old));
    discovery =
        new WiredDiscovery(
            UUID.randomUUID(), 8, List.of(pos(1)).iterator(), world::get, () -> epoch[0]);
    discovery.advance(1);
    discovery.cancel();
    assertEquals(WiredDiscovery.State.CANCELLED, discovery.advance(256).state());
    assertThrows(IllegalStateException.class, discovery::publishedTopology);
  }

  @Test
  void maximumChainAndSeedAdmissionStayBounded() {
    var world = new HashMap<NodeRegistry.Position, WiredDiscovery.Wire>();
    for (int i = 1; i <= NetworkTopology.MAX_NODES; i++) {
      int[] neighbors =
          i == 1
              ? new int[] {2}
              : i == NetworkTopology.MAX_NODES ? new int[] {i - 1} : new int[] {i - 1, i + 1};
      world.put(
          pos(i),
          wire(i, i == 1 ? NetworkTopology.Role.ROOT : NetworkTopology.Role.CABLE, neighbors));
    }
    int[] physicalReads = {0};
    var discovery =
        new WiredDiscovery(
            UUID.randomUUID(),
            8,
            List.of(pos(1)).iterator(),
            p -> {
              physicalReads[0]++;
              return world.get(p);
            },
            () -> 1);
    WiredDiscovery.Advance result = null;
    for (int i = 0; i < 200000; i++) {
      int before = physicalReads[0];
      result = discovery.advance(1);
      assertTrue(physicalReads[0] - before <= 1, "One host probe maximum per one-visit call");
      assertTrue(result.work() <= 1);
      if (result.state() == WiredDiscovery.State.PUBLISHED
          || result.state() == WiredDiscovery.State.FAILED) break;
    }
    assertNotNull(result);
    assertEquals(WiredDiscovery.State.PUBLISHED, result.state());
    assertEquals(NetworkTopology.MAX_NODES, result.captured());
    assertEquals(
        NetworkTopology.Status.VALID,
        discovery.publishedTopology().membership(id(NetworkTopology.MAX_NODES)).status());
    world.put(
        pos(NetworkTopology.MAX_NODES),
        wire(
            NetworkTopology.MAX_NODES,
            NetworkTopology.Role.CABLE,
            NetworkTopology.MAX_NODES - 1,
            NetworkTopology.MAX_NODES + 1));
    world.put(
        pos(NetworkTopology.MAX_NODES + 1),
        wire(NetworkTopology.MAX_NODES + 1, NetworkTopology.Role.CABLE, NetworkTopology.MAX_NODES));
    assertEquals(WiredDiscovery.State.FAILED, finish(job(world, 1)).state());
    var seeds = java.util.Collections.nCopies(WiredDiscovery.MAX_SEEDS + 1, pos(0));
    discovery = new WiredDiscovery(UUID.randomUUID(), 8, seeds.iterator(), p -> null, () -> 1);
    assertEquals(WiredDiscovery.State.FAILED, finish(discovery).state());
  }

  @Test
  void commonSchedulerReschedulesAndRejectedAdmissionCancels() {
    var scheduler =
        new FairScheduler(FairScheduler.Limits.defaults(), () -> 0, f -> fail(f.diagnostic()));
    var discovery = job(branch(), 1);
    var task = new DiscoveryTask(scheduler, id(100), id(101), "discovery", discovery, 1);
    assertEquals(FairScheduler.Admission.ACCEPTED, task.submit());
    for (int i = 0; i < 100 && scheduler.backlog() > 0; i++) scheduler.tick();
    assertEquals(0, scheduler.backlog());
    assertEquals(
        NetworkTopology.Status.VALID, discovery.publishedTopology().membership(id(3)).status());
    var limited =
        new FairScheduler(
            new FairScheduler.Limits(1, 1, 1, 1, 1, 1, 1, 1, 1, 100),
            () -> 0,
            f -> fail(f.diagnostic()));
    var rejected = job(branch(), 1);
    var denied = new DiscoveryTask(limited, id(100), id(101), "discovery", rejected, 2);
    assertEquals(FairScheduler.Admission.COST_LIMIT, denied.submit());
    assertFalse(denied.diagnostic().isEmpty());
    assertEquals(WiredDiscovery.State.CANCELLED, rejected.advance(1).state());
    assertEquals(0, limited.backlog());
  }
}
