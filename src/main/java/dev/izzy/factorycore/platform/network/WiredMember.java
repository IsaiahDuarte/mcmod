package dev.izzy.factorycore.platform.network;

import dev.izzy.factorycore.core.network.NetworkTopology;
import java.util.UUID;

/**
 * A device block entity that is one vertex in wired discovery. Each device declares how it appears
 * in the topology here; its ports come from its block's {@link NetworkedBlock#wiredFace}.
 */
public interface WiredMember {
  /**
   * {@code canonical} is the device's own persistent id, or null for a positional id derived from
   * {@code salt}. Salts are part of vertex identity and must never change for an existing device.
   * {@code label} is the local label machine interfaces are addressed by ("" otherwise).
   */
  record Vertex(
      UUID canonical, String salt, NetworkTopology.Role role, boolean loaded, String label) {
    public Vertex(UUID canonical, String salt, NetworkTopology.Role role, boolean loaded) {
      this(canonical, salt, role, loaded, "");
    }
  }

  Vertex wireVertex();

  /** True when {@code check} (a placed-holder validation) does not reject the holder. */
  static boolean placedValid(Runnable check) {
    try {
      check.run();
      return true;
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      return false;
    }
  }
}
