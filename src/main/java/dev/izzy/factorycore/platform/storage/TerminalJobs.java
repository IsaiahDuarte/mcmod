package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.crafting.PlanRows;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.*;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.crafting.WorldCrafting;
import dev.izzy.factorycore.platform.crafting.WorldCraftingJobs;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side Jobs mode of one terminal session: a scrolled craftable window, plan preview,
 * starting the previewed plan, keep-stock toggles and cancelling the viewer's own jobs. {@link
 * TerminalMenu} keeps packet/session validation and rate limiting and calls in only for accepted
 * commands.
 */
final class TerminalJobs {
  private static final java.util.Set<String> HEALTHY_RULES =
      java.util.Set.of("", "satisfied", "started", "in_flight");

  private final ServerPlayer viewer;
  private final TerminalAccess terminal;
  private final int menu;
  private final UUID session;
  private TerminalJobPackets.View sent;
  private long jobRevision = 1;
  private int craftableOffset;
  private List<ResourceKey> craftables = List.of();
  private int craftableTotal;
  private ResourceKey previewKey;
  private int previewAmount;
  private WorldCraftingJobs.Preview preview;
  private List<TerminalJobPackets.PlanRow> planRows = List.of();
  private int planMore;
  private List<String> planCycles = List.of();
  private String jobStatus = "ready";
  private List<UUID> jobIds = List.of();

  /** A4: the job opened in the detail view, or null. */
  private UUID detailJob;

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
    return Actors.of(viewer);
  }

  /**
   * Outputs of patterns visible to {@code origin}'s segment (its own Crafters plus branches whose
   * gateways publish CRAFT), in key order, at most {@link WorldStorageSearch#MAX_CRAFTABLE}. Empty
   * while the segment is not VALID.
   */
  static Set<ResourceKey> craftableKeys(
      net.minecraft.server.MinecraftServer server,
      dev.izzy.factorycore.core.network.NodeRegistry.Position origin) {
    var member = WorldNetworks.get(server).membership(origin);
    if (member.status() != NetworkTopology.Status.VALID) return Set.of();
    var keys = new TreeSet<>(WorldCrafting.get(server).index(member.scope()).byOutput().keySet());
    var bounded = new LinkedHashSet<ResourceKey>();
    for (var key : keys) {
      if (bounded.size() == WorldStorageSearch.MAX_CRAFTABLE) break;
      bounded.add(key);
    }
    return bounded;
  }

  /**
   * Lists a window of up to {@link TerminalJobPackets#ROWS} craftable outputs of this segment's
   * Crafters in key order, starting at the scrolled offset (clamped so the last window is full).
   */
  void refreshCraftables() {
    var member = WorldNetworks.get(viewer.server).membership(terminal.origin());
    if (member.status() != NetworkTopology.Status.VALID) {
      craftables = List.of();
      craftableTotal = 0;
      craftableOffset = 0;
      return;
    }
    var keys =
        new ArrayList<>(WorldCrafting.get(viewer.server).index(member.scope()).byOutput().keySet());
    keys.sort(null);
    int rows = TerminalJobPackets.ROWS;
    craftableTotal = Math.min(keys.size(), TerminalJobPackets.MAX_OFFSET + rows);
    craftableOffset = Math.max(0, Math.min(craftableOffset, craftableTotal - rows));
    craftables =
        List.copyOf(
            keys.subList(craftableOffset, Math.min(craftableTotal, craftableOffset + rows)));
  }

  /**
   * A3: scrolls the craftable list so {@code key} is visible (first unless the last window is
   * clamped) for a request started from the Items grid. False when no visible pattern makes it.
   */
  boolean focus(ResourceKey key) {
    int index = new ArrayList<>(craftableKeys(viewer.server, terminal.origin())).indexOf(key);
    if (index < 0) return false;
    craftableOffset = index;
    refreshCraftables();
    jobRevision++;
    return true;
  }

  /**
   * Applies one accepted command. Returns true when the view must be republished: a change bumps
   * the revision, a stale revision republishes the current view, an ignored command does neither.
   */
  boolean command(TerminalJobPackets.Command command) {
    var jobs = WorldCraftingJobs.get(viewer.server);
    switch (command.action()) {
      case TerminalJobPackets.WINDOW -> {
        craftableOffset = command.selection();
        refreshCraftables();
      }
      case TerminalJobPackets.PREVIEW -> {
        if (command.revision() != jobRevision || command.selection() >= craftables.size()) {
          return true;
        }
        previewKey = craftables.get(command.selection());
        previewAmount = command.amount();
        preview = jobs.preview(actor(), terminal.origin(), previewKey, previewAmount);
        summarize();
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
          summarize();
        } else {
          jobStatus = submission.reason();
          if (submission.plan() != null)
            preview = new WorldCraftingJobs.Preview(submission.plan(), submission.reason());
          summarize();
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
      case TerminalJobPackets.DETAIL -> {
        if (command.amount() == 2) detailJob = null;
        else if (command.selection() < jobIds.size()) detailJob = jobIds.get(command.selection());
        else return false;
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

  /**
   * Rebuilds the bounded preview breakdown (A2) once per preview change rather than per publish:
   * per resource stored, crafted and missing units, plus cycle explanations by display name.
   */
  private void summarize() {
    if (preview == null || preview.plan() == null) {
      planRows = List.of();
      planMore = 0;
      planCycles = List.of();
      return;
    }
    var units = new HashMap<UUID, Long>();
    var member = WorldNetworks.get(viewer.server).membership(terminal.origin());
    if (member.status() == NetworkTopology.Status.VALID)
      for (var offers : WorldCrafting.get(viewer.server).index(member.scope()).byOutput().values())
        for (var offer : offers) units.put(offer.pattern().id(), offer.pattern().outputAmount());
    var summary =
        PlanRows.of(preview.plan(), id -> units.getOrDefault(id, 1L), TerminalJobPackets.PLAN_ROWS);
    var rows = new ArrayList<TerminalJobPackets.PlanRow>(summary.rows().size());
    for (var row : summary.rows())
      rows.add(
          new TerminalJobPackets.PlanRow(
              entry(row.key(), ""), row.stored(), row.crafted(), row.missing()));
    planRows = List.copyOf(rows);
    planMore = summary.more();
    var cycles = new ArrayList<String>();
    for (var cycle : PlanRows.cycles(preview.plan(), TerminalJobPackets.CYCLES)) {
      var names = new ArrayList<String>();
      for (var key : cycle) names.add(entry(key, "").name());
      String text = String.join(" → ", names);
      cycles.add(text.length() > 256 ? text.substring(0, 255) + "…" : text);
    }
    planCycles = List.copyOf(cycles);
  }

  /**
   * The viewer's own jobs, then (A4) every other job on this network when the viewer has Manage at
   * the terminal. Manage shows other players' jobs; only their requester or an operator may cancel
   * them ({@link WorldCraftingJobs#cancel}).
   */
  private List<dev.izzy.factorycore.core.crafting.CraftingJob> listed() {
    var runtime = WorldCraftingJobs.get(viewer.server);
    var own = runtime.jobs(viewer.getUUID());
    var networks = WorldNetworks.get(viewer.server);
    var member = networks.membership(terminal.origin());
    if (member.status() != NetworkTopology.Status.VALID
        || !networks.allows(actor(), terminal.origin(), NetworkPermissions.Permission.MANAGE))
      return own;
    var all = new ArrayList<>(own);
    for (var job : DeviceSavedData.get(viewer.server).craftingJobs().all())
      if (!job.principal().equals(viewer.getUUID())
          && job.network().equals(member.scope().network())) all.add(job);
    return all;
  }

  private TerminalJobPackets.Job row(dev.izzy.factorycore.core.crafting.CraftingJob job) {
    String owner = "";
    if (!job.principal().equals(viewer.getUUID())) {
      owner =
          dev.izzy.factorycore.platform.network.AccessAdministration.name(
                  viewer.server, job.principal())
              .getString();
      if (owner.length() > 48) owner = owner.substring(0, 48);
    }
    return new TerminalJobPackets.Job(
        entry(job.request(), Long.toString(job.amount())),
        job.craftsDone(),
        job.craftsTotal(),
        job.state().name().toLowerCase(Locale.ROOT),
        job.reason(),
        owner);
  }

  /** Steps of the opened job: done, the current one running or waiting, the rest queued. */
  private TerminalJobPackets.Detail detail(
      dev.izzy.factorycore.core.crafting.CraftingJob job, TerminalJobPackets.Job row) {
    var steps = new ArrayList<TerminalJobPackets.StepRow>();
    int current = job.current();
    boolean waiting = job.state() == dev.izzy.factorycore.core.crafting.CraftingJob.State.WAITING;
    for (int index = 0; index < job.steps().size(); index++) {
      if (steps.size() == TerminalJobPackets.DETAIL_STEPS) break;
      var step = job.steps().get(index);
      String status =
          step.done() >= step.crafts()
              ? "done"
              : index == current ? waiting ? "waiting" : "running" : "queued";
      steps.add(
          new TerminalJobPackets.StepRow(
              entry(step.output(), ""), step.done(), step.crafts(), status));
    }
    boolean cancellable = job.principal().equals(viewer.getUUID()) || actor().operator();
    return new TerminalJobPackets.Detail(
        row, cancellable, steps, job.steps().size() - steps.size());
  }

  /** Sends the Jobs view when it differs from the last one sent. */
  void publish() {
    var rows = new ArrayList<TerminalJobPackets.Entry>();
    var runtime = WorldCraftingJobs.get(viewer.server);
    for (var key : craftables) {
      // A craftable row's amount carries the viewer's keep-stock threshold, if any.
      var rule = runtime.rule(actor(), terminal.origin(), key);
      var row = entry(key, rule == null ? "" : Long.toString(rule.low()));
      var status = rule == null ? "" : runtime.ruleStatus(rule.id());
      // Only a blocked rule is "waiting"; satisfied, started and in-flight rules are healthy.
      var waiting = HEALTHY_RULES.contains(status) ? "" : status;
      rows.add(
          waiting.isEmpty()
              ? row
              : new TerminalJobPackets.Entry(row.icon(), row.name(), row.amount(), waiting));
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
    TerminalJobPackets.Detail detail = TerminalJobPackets.NO_DETAIL;
    for (var job : listed()) {
      if (jobRows.size() == TerminalJobPackets.JOBS) break;
      ids.add(job.id());
      var row = row(job);
      jobRows.add(row);
      if (job.id().equals(detailJob)) detail = detail(job, row);
    }
    // A job that finished, or is no longer listed, closes its detail view.
    if (detail == TerminalJobPackets.NO_DETAIL) detailJob = null;
    jobIds = List.copyOf(ids);
    var packet =
        new TerminalJobPackets.View(
            menu,
            session,
            jobRevision,
            rows,
            craftableOffset,
            craftableTotal,
            previewKey == null
                ? TerminalJobPackets.NONE
                : entry(previewKey, Integer.toString(previewAmount)),
            reason,
            steps,
            crafts,
            missing,
            planRows,
            planMore,
            planCycles,
            jobRows,
            detail,
            jobStatus);
    if (!packet.equals(sent)) {
      sent = packet;
      PacketDistributor.sendToPlayer(viewer, packet);
    }
  }
}
