package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.crafting.WorldCrafting;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side Jobs mode of one terminal session: craftable paging, plan preview, starting the
 * previewed plan, keep-stock toggles and cancelling the viewer's own jobs. {@link TerminalMenu}
 * keeps packet/session validation and rate limiting and calls in only for accepted commands.
 */
final class TerminalJobs {
  private final ServerPlayer viewer;
  private final TerminalAccess terminal;
  private final int menu;
  private final UUID session;
  private TerminalJobPackets.View sent;
  private long jobRevision = 1;
  private int craftableOffset;
  private List<ResourceKey> craftables = List.of();
  private boolean craftablesNext;
  private ResourceKey previewKey;
  private int previewAmount;
  private WorldCraftingJobs.Preview preview;
  private String jobStatus = "ready";
  private List<UUID> jobIds = List.of();

  /** {@code initial} is the view the client already holds, so an unchanged view is not resent. */
  TerminalJobs(
      ServerPlayer viewer,
      TerminalAccess terminal,
      int menu,
      UUID session,
      TerminalJobPackets.View initial) {
    this.viewer = viewer;
    this.terminal = terminal;
    this.menu = menu;
    this.session = session;
    sent = initial;
  }

  TerminalJobPackets.View view() {
    return sent;
  }

  private NetworkPermissions.Actor actor() {
    return new NetworkPermissions.Actor(viewer.getUUID(), viewer.hasPermissions(2));
  }

  /** Lists craftable outputs of this segment's Crafting Units, eight per page, in key order. */
  void refreshCraftables() {
    var member = WorldNetworks.get(viewer.server).membership(terminal.origin());
    if (member.status() != NetworkTopology.Status.VALID) {
      craftables = List.of();
      craftablesNext = false;
      return;
    }
    var keys =
        new ArrayList<>(WorldCrafting.get(viewer.server).index(member.scope()).byOutput().keySet());
    keys.sort(null);
    craftableOffset = Math.min(craftableOffset, Math.max(0, keys.size() - 1) / 8 * 8);
    craftables =
        List.copyOf(keys.subList(craftableOffset, Math.min(keys.size(), craftableOffset + 8)));
    craftablesNext = keys.size() > craftableOffset + 8;
  }

  /**
   * Applies one accepted command. Returns true when the view must be republished: a change bumps
   * the revision, a stale revision republishes the current view, an ignored command does neither.
   */
  boolean command(TerminalJobPackets.Command command) {
    var jobs = WorldCraftingJobs.get(viewer.server);
    switch (command.action()) {
      case TerminalJobPackets.HOME -> {
        craftableOffset = 0;
        refreshCraftables();
      }
      case TerminalJobPackets.NEXT -> {
        if (craftablesNext) craftableOffset += 8;
        refreshCraftables();
      }
      case TerminalJobPackets.PREVIEW -> {
        if (command.revision() != jobRevision || command.selection() >= craftables.size()) {
          return true;
        }
        previewKey = craftables.get(command.selection());
        previewAmount = command.amount();
        preview = jobs.preview(actor(), terminal.origin(), previewKey, previewAmount);
        jobStatus = preview.reason().isEmpty() ? "previewed" : preview.reason();
      }
      case TerminalJobPackets.START -> {
        if (!terminal.spendAction(viewer)) {
          jobStatus = "no_power";
          break;
        }
        if (command.revision() != jobRevision || previewKey == null) {
          return true;
        }
        var submission = jobs.submit(actor(), terminal.origin(), previewKey, previewAmount);
        if (submission.job() != null) {
          jobStatus = "started";
          previewKey = null;
          preview = null;
        } else {
          jobStatus = submission.reason();
          if (submission.plan() != null)
            preview = new WorldCraftingJobs.Preview(submission.plan(), submission.reason());
        }
      }
      case TerminalJobPackets.KEEP -> {
        if (command.revision() != jobRevision || command.selection() >= craftables.size()) {
          return true;
        }
        var key = craftables.get(command.selection());
        var existing = jobs.rule(actor(), terminal.origin(), key);
        if (existing != null)
          jobStatus = jobs.unkeep(actor(), existing.id()) ? "unkept" : "refused";
        else {
          var reason =
              jobs.keep(actor(), terminal.origin(), key, command.amount(), command.amount());
          jobStatus = reason.isEmpty() ? "kept" : reason;
        }
      }
      case TerminalJobPackets.CANCEL -> {
        if (command.selection() >= jobIds.size()) return false;
        jobStatus =
            jobs.cancel(actor(), jobIds.get(command.selection())) ? "cancelling" : "refused";
      }
      default -> {
        return false;
      }
    }
    jobRevision++;
    return true;
  }

  private TerminalJobPackets.Entry entry(ResourceKey key, String amount) {
    String name;
    try {
      name =
          new PlatformResourceCodec(viewer.registryAccess())
              .itemStack(key, 1)
              .getHoverName()
              .getString()
              .replaceAll("[\\p{Cntrl}§]", "");
    } catch (IllegalArgumentException unsupported) {
      name = key.registryId();
    }
    if (name.length() > 96) name = name.substring(0, 96);
    return new TerminalJobPackets.Entry(key.registryId(), name, amount);
  }

  /** Sends the Jobs view when it differs from the last one sent. */
  void publish() {
    var rows = new ArrayList<TerminalJobPackets.Entry>();
    var runtime = WorldCraftingJobs.get(viewer.server);
    for (var key : craftables) {
      // A craftable row's amount carries the viewer's keep-stock threshold, if any.
      var rule = runtime.rule(actor(), terminal.origin(), key);
      rows.add(entry(key, rule == null ? "" : Long.toString(rule.low())));
    }
    var missing = new ArrayList<TerminalJobPackets.Entry>();
    int steps = 0;
    long crafts = 0;
    String reason = "";
    if (preview != null && preview.plan() != null) {
      reason = preview.reason();
      steps = preview.plan().steps().size();
      for (var step : preview.plan().steps()) crafts = Math.addExact(crafts, step.crafts());
      for (var entry : preview.plan().missing().entrySet()) {
        if (missing.size() == TerminalJobPackets.MISSING) break;
        missing.add(entry(entry.getKey(), entry.getValue().toString()));
      }
    } else if (preview != null) reason = preview.reason();
    var ids = new ArrayList<UUID>();
    var jobRows = new ArrayList<TerminalJobPackets.Job>();
    for (var job : WorldCraftingJobs.get(viewer.server).jobs(viewer.getUUID())) {
      if (jobRows.size() == TerminalJobPackets.JOBS) break;
      ids.add(job.id());
      jobRows.add(
          new TerminalJobPackets.Job(
              entry(job.request(), Long.toString(job.amount())),
              job.craftsDone(),
              job.craftsTotal(),
              job.state().name().toLowerCase(Locale.ROOT),
              job.reason()));
    }
    jobIds = List.copyOf(ids);
    var packet =
        new TerminalJobPackets.View(
            menu,
            session,
            jobRevision,
            rows,
            craftablesNext,
            previewKey == null
                ? TerminalJobPackets.NONE
                : entry(previewKey, Integer.toString(previewAmount)),
            reason,
            steps,
            crafts,
            missing,
            jobRows,
            jobStatus);
    if (!packet.equals(sent)) {
      sent = packet;
      PacketDistributor.sendToPlayer(viewer, packet);
    }
  }
}
