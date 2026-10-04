package dev.izzy.factorycore.core.network;

import dev.izzy.factorycore.core.scheduling.FairScheduler;
import java.util.Objects;
import java.util.UUID;

/** One coalesced discovery job using the common scheduler; owns no timer or worker thread. */
public final class DiscoveryTask {
  private final FairScheduler scheduler;
  private final UUID network;
  private final UUID program;
  private final String key;
  private final WiredDiscovery discovery;
  private final int visits;
  private String diagnostic = "";

  public DiscoveryTask(
      FairScheduler scheduler,
      UUID network,
      UUID program,
      String key,
      WiredDiscovery discovery,
      int visits) {
    this.scheduler = Objects.requireNonNull(scheduler);
    this.network = Objects.requireNonNull(network);
    this.program = Objects.requireNonNull(program);
    this.key = Objects.requireNonNull(key);
    this.discovery = Objects.requireNonNull(discovery);
    if (visits <= 0 || visits > NetworkTopology.MAX_ADVANCE)
      throw new IllegalArgumentException("Invalid scheduled discovery budget");
    this.visits = visits;
  }

  public FairScheduler.Admission submit() {
    var result = scheduler.submit(network, program, key, visits, this::step);
    if (result != FairScheduler.Admission.ACCEPTED && result != FairScheduler.Admission.REPLACED) {
      discovery.cancel();
      diagnostic = "Discovery scheduling rejected: " + result;
    }
    return result;
  }

  private void step() {
    var result = discovery.advance(visits);
    diagnostic = result.diagnostic();
    if (result.state() == WiredDiscovery.State.DISCOVERING
        || result.state() == WiredDiscovery.State.VALIDATING) submit();
  }

  public String diagnostic() {
    return diagnostic;
  }
}
