package dev.izzy.factorycore.platform.upgrade;

import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.WirelessBudget;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.BufferedInterfaceEntity;
import dev.izzy.factorycore.platform.storage.ConnectorEntity;
import dev.izzy.factorycore.platform.wireless.LinkModule;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Player-visible throughput feedback (ADR 0054): the installed tier, the device's own per-tick
 * ceiling and whether the shared server budget recently throttled the device's network. The
 * throttle state is read from the scheduler; it never changes a budget.
 */
public final class ThroughputStatus {
  /** One second of scheduler ticks. */
  public static final int WINDOW = 20;

  private ThroughputStatus() {}

  /** True when the network containing {@code position} waited for the fair budget recently. */
  public static boolean throttled(MinecraftServer server, NodeRegistry.Position position) {
    var networks = WorldNetworks.get(server);
    var member = networks.membership(position);
    return member.status() == NetworkTopology.Status.VALID
        && networks.scheduler().throttled(member.scope().network(), WINDOW);
  }

  public static Component budget(boolean throttled) {
    return Component.translatable(
        "throughput.factorycore.server." + (throttled ? "throttled" : "ok"));
  }

  static Component units(ResourceKind kind) {
    return Component.translatable(
        "gui.factorycore.machine.units." + kind.name().toLowerCase(Locale.ROOT));
  }

  public static Component port(MinecraftServer server, ConnectorEntity port) {
    var kind = port.settings().kind();
    return Component.translatable(
        "message.factorycore.throughput_status",
        ThroughputContent.name(port.tier()),
        port.tier().portRate(kind),
        units(kind),
        budget(throttled(server, port.position())));
  }

  public static Component buffer(MinecraftServer server, BufferedInterfaceEntity buffer) {
    return Component.translatable(
        "message.factorycore.throughput_status",
        ThroughputContent.name(buffer.tier()),
        buffer.tier().bufferRate(),
        units(ResourceKind.ITEM),
        budget(throttled(server, buffer.position())));
  }

  public static Component link(MinecraftServer server, LinkModule link) {
    int scale = link.pairScale();
    var position = link.origin();
    return Component.translatable(
        "message.factorycore.link_throughput",
        ThroughputContent.name(link.tier()),
        WirelessBudget.ITEMS * scale,
        WirelessBudget.FLUID_MB * scale,
        WirelessBudget.ENERGY_FE * scale,
        budget(throttled(server, position)));
  }
}
