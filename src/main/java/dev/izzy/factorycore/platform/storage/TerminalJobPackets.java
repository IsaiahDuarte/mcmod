package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.crafting.JobStatus;
import dev.izzy.factorycore.core.crafting.PlanTree;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.WireEnums;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Terminal autocrafting payloads. Commands name a current craftable row or job row only; the server
 * owns the craftable keys, the previewed plan and every job. Views carry base registry icons,
 * bounded names and exact decimal counts.
 */
public final class TerminalJobPackets {
  /**
   * WINDOW scrolls the craftable list so {@code selection} is the first visible index (clamped to
   * the last full window); the other actions' {@code selection} is an index into the visible
   * window. KEEP toggles the viewer's keep-stock rule for a craftable row at {@code amount}.
   */
  public static final int WINDOW = 0, PREVIEW = 1, START = 2, CANCEL = 3, KEEP = 4;

  /**
   * A4: DETAIL opens job row {@code selection} in the detail view; {@code amount} 2 closes it.
   * CANCEL and DETAIL index the listed jobs (own first, then others' when the viewer has Manage).
   */
  public static final int DETAIL = 5;

  /**
   * Q7: MAX previews craftable row {@code selection} at the largest amount the network can make, up
   * to {@link #MAX_AMOUNT}; {@code amount} is ignored.
   */
  public static final int MAX = 6;

  /**
   * Q64: TARGET previews craftable row {@code selection} at what is needed to reach {@code amount}
   * stored (up to {@link #MAX_TARGET}; the preview is capped at {@link #MAX_AMOUNT}). QUICK
   * previews {@code amount} and starts it at once; QUICK_TARGET does the same for a target.
   */
  public static final int TARGET = 7, QUICK = 8, QUICK_TARGET = 9;

  /**
   * Q65: NOTIFY sets whether jobs started in this session tell the requester when they finish
   * ({@code selection} 1) or not (0).
   */
  public static final int NOTIFY = 10;

  public static final int MAX_TARGET = 1_000_000_000;

  public static final int ROWS = 8, JOBS = 16, MISSING = 3, MAX_OFFSET = 65_536;

  /** A4: steps sent for the opened job; the rest are counted. */
  public static final int DETAIL_STEPS = 32;

  /** A2: preview breakdown rows sent at most, and cycle explanations (each ≤ 256 chars). */
  public static final int PLAN_ROWS = 64, CYCLES = 3;

  public static final int MAX_AMOUNT = 4_096;

  public record Command(
      int menu, UUID session, long revision, int action, int selection, int amount)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_job_command"));

    public Command {
      if (action < WINDOW
          || action > NOTIFY
          || selection < 0
          || selection
              >= (action == WINDOW
                  ? MAX_OFFSET + 1
                  : action == DETAIL || action == CANCEL ? JOBS : ROWS)
          || amount < 1
          || amount > (action == TARGET || action == QUICK_TARGET ? MAX_TARGET : MAX_AMOUNT))
        throw new IllegalArgumentException("Invalid job command bounds");
    }

    @Override
    public Type<Command> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeLong(value.revision);
              buffer.writeVarInt(value.action);
              buffer.writeVarInt(value.selection);
              buffer.writeVarInt(value.amount);
            },
            buffer ->
                new Command(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()));
  }

  /**
   * One displayed item with an exact decimal amount (empty when not applicable). {@code note} is,
   * on a kept craftable row, why its keep-stock rule is waiting (null when it is not).
   */
  public record Entry(String icon, String name, String amount, JobStatus note) {
    public Entry {
      if (icon.length() > 256 || name.length() > 96 || amount.length() > 32)
        throw new IllegalArgumentException("Invalid job entry bounds");
    }

    public Entry(String icon, String name, String amount) {
      this(icon, name, amount, null);
    }

    static void write(RegistryFriendlyByteBuf buffer, Entry value) {
      buffer.writeUtf(value.icon, 256);
      buffer.writeUtf(value.name, 96);
      buffer.writeUtf(value.amount, 32);
      WireEnums.write(buffer, value.note);
    }

    static Entry read(RegistryFriendlyByteBuf buffer) {
      return new Entry(
          buffer.readUtf(256),
          buffer.readUtf(96),
          buffer.readUtf(32),
          WireEnums.readNullable(buffer, JobStatus.class));
    }
  }

  /**
   * One resource of the previewed plan (A2): units taken from stock, crafted by the plan and
   * missing. The entry's amount is empty; the three counts are exact.
   */
  /**
   * Q65: one node of the plan drawn as a tree, depth first: {@code amount} units of {@code item} at
   * {@code depth} (0 is the request), and how the plan gets it.
   */
  public record TreeRow(Entry item, int depth, long amount, PlanTree.Kind kind) {
    public TreeRow {
      Objects.requireNonNull(kind);
      if (depth < 0 || depth > PlanTree.MAX_DEPTH || amount < 0)
        throw new IllegalArgumentException("Invalid tree row bounds");
    }
  }

  public record PlanRow(Entry item, long stored, long crafted, long missing) {
    public PlanRow {
      if (stored < 0 || crafted < 0 || missing < 0)
        throw new IllegalArgumentException("Invalid plan row bounds");
    }
  }

  /**
   * One listed job: output, exact crafts done/total, state and why it waits (null when it does
   * not). {@code owner} is the requester's name for another player's job (shown to Manage), empty
   * for the viewer's own.
   */
  public record Job(
      Entry output,
      long done,
      long total,
      CraftingJob.State state,
      JobReason reason,
      String owner) {
    public Job(Entry output, long done, long total, CraftingJob.State state, JobReason reason) {
      this(output, done, total, state, reason, "");
    }

    public Job {
      Objects.requireNonNull(state);
      if (done < 0 || total < done || owner.length() > 48)
        throw new IllegalArgumentException("Invalid job row bounds");
    }
  }

  /** Where a step of the opened job stands; shown as {@code ...terminal.job.step.<key>}. */
  public enum StepStatus {
    DONE,
    /** The current step. */
    RUNNING,
    /** The current step of a waiting job; the job's reason says why. */
    WAITING,
    QUEUED;

    public String key() {
      return EnumKeys.key(this);
    }
  }

  /** One step of the opened job (A4): its output, crafts done/total and status. */
  public record StepRow(Entry output, long done, long total, StepStatus status) {
    public StepRow {
      Objects.requireNonNull(status);
      if (done < 0 || total < done) throw new IllegalArgumentException("Invalid step row bounds");
    }
  }

  /**
   * The opened job, or {@link #NO_DETAIL}. {@code cancellable} is whether this viewer may cancel it
   * (its requester, or an operator); Manage alone lets a player see but not cancel it.
   */
  public record Detail(Job job, boolean cancellable, List<StepRow> steps, int moreSteps) {
    public Detail {
      steps = List.copyOf(steps);
      if (steps.size() > DETAIL_STEPS || moreSteps < 0)
        throw new IllegalArgumentException("Invalid job detail bounds");
    }
  }

  /** No opened job: an empty output icon marks it; the state is a placeholder. */
  public static final Detail NO_DETAIL =
      new Detail(
          new Job(new Entry("", "", ""), 0, 0, CraftingJob.State.RUNNING, null),
          false,
          List.of(),
          0);

  static void writeJob(RegistryFriendlyByteBuf buffer, Job job) {
    Entry.write(buffer, job.output());
    buffer.writeVarLong(job.done());
    buffer.writeVarLong(job.total());
    WireEnums.write(buffer, job.state());
    WireEnums.write(buffer, job.reason());
    buffer.writeUtf(job.owner(), 48);
  }

  static Job readJob(RegistryFriendlyByteBuf buffer) {
    return new Job(
        Entry.read(buffer),
        buffer.readVarLong(),
        buffer.readVarLong(),
        WireEnums.read(buffer, CraftingJob.State.class),
        WireEnums.readNullable(buffer, JobReason.class),
        buffer.readUtf(48));
  }

  public record View(
      int menu,
      UUID session,
      long revision,
      List<Entry> craftables,
      int offset,
      int total,
      Entry preview,
      JobStatus previewReason,
      int previewSteps,
      long previewCrafts,
      List<Entry> missing,
      List<PlanRow> plan,
      int planMore,
      List<String> cycles,
      List<Job> jobs,
      Detail detail,
      JobStatus status,
      List<TreeRow> tree,
      boolean treeMore,
      boolean notices)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_job_view"));

    public View(
        int menu,
        UUID session,
        long revision,
        List<Entry> craftables,
        int offset,
        int total,
        Entry preview,
        JobStatus previewReason,
        int previewSteps,
        long previewCrafts,
        List<Entry> missing,
        List<PlanRow> plan,
        int planMore,
        List<String> cycles,
        List<Job> jobs,
        Detail detail,
        JobStatus status) {
      this(
          menu,
          session,
          revision,
          craftables,
          offset,
          total,
          preview,
          previewReason,
          previewSteps,
          previewCrafts,
          missing,
          plan,
          planMore,
          cycles,
          jobs,
          detail,
          status,
          List.of(),
          false,
          true);
    }

    public View {
      tree = List.copyOf(tree);
      if (tree.size() > PLAN_ROWS) throw new IllegalArgumentException("Tree rows");
      craftables = List.copyOf(craftables);
      missing = List.copyOf(missing);
      plan = List.copyOf(plan);
      cycles = List.copyOf(cycles);
      jobs = List.copyOf(jobs);
      Objects.requireNonNull(status);
      if (craftables.size() > ROWS
          || offset < 0
          || total < offset + craftables.size()
          || missing.size() > MISSING
          || plan.size() > PLAN_ROWS
          || planMore < 0
          || cycles.size() > CYCLES
          || cycles.stream().anyMatch(cycle -> cycle.length() > 256)
          || jobs.size() > JOBS
          || previewSteps < 0
          || previewCrafts < 0) throw new IllegalArgumentException("Invalid job view bounds");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }

    private static void entries(RegistryFriendlyByteBuf buffer, List<Entry> values) {
      buffer.writeVarInt(values.size());
      for (var value : values) Entry.write(buffer, value);
    }

    private static List<Entry> entries(RegistryFriendlyByteBuf buffer, int max) {
      int size = buffer.readVarInt();
      if (size < 0 || size > max) throw new IllegalArgumentException("Job entry count");
      var values = new ArrayList<Entry>(size);
      for (int index = 0; index < size; index++) values.add(Entry.read(buffer));
      return values;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeLong(value.revision);
              entries(buffer, value.craftables);
              buffer.writeVarInt(value.offset);
              buffer.writeVarInt(value.total);
              Entry.write(buffer, value.preview);
              WireEnums.write(buffer, value.previewReason);
              buffer.writeVarInt(value.previewSteps);
              buffer.writeVarLong(value.previewCrafts);
              entries(buffer, value.missing);
              buffer.writeVarInt(value.plan.size());
              for (var row : value.plan) {
                Entry.write(buffer, row.item());
                buffer.writeVarLong(row.stored());
                buffer.writeVarLong(row.crafted());
                buffer.writeVarLong(row.missing());
              }
              buffer.writeVarInt(value.planMore);
              buffer.writeVarInt(value.cycles.size());
              for (var cycle : value.cycles) buffer.writeUtf(cycle, 256);
              buffer.writeVarInt(value.jobs.size());
              for (var job : value.jobs) writeJob(buffer, job);
              writeJob(buffer, value.detail.job());
              buffer.writeBoolean(value.detail.cancellable());
              buffer.writeVarInt(value.detail.steps().size());
              for (var step : value.detail.steps()) {
                Entry.write(buffer, step.output());
                buffer.writeVarLong(step.done());
                buffer.writeVarLong(step.total());
                WireEnums.write(buffer, step.status());
              }
              buffer.writeVarInt(value.detail.moreSteps());
              WireEnums.write(buffer, value.status);
              buffer.writeVarInt(value.tree.size());
              for (var row : value.tree) {
                Entry.write(buffer, row.item());
                buffer.writeVarInt(row.depth());
                buffer.writeVarLong(row.amount());
                WireEnums.write(buffer, row.kind());
              }
              buffer.writeBoolean(value.treeMore);
              buffer.writeBoolean(value.notices);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              UUID session = buffer.readUUID();
              long revision = buffer.readLong();
              var craftables = entries(buffer, ROWS);
              int offset = buffer.readVarInt();
              int total = buffer.readVarInt();
              var preview = Entry.read(buffer);
              var reason = WireEnums.readNullable(buffer, JobStatus.class);
              int steps = buffer.readVarInt();
              long crafts = buffer.readVarLong();
              var missing = entries(buffer, MISSING);
              int rows = buffer.readVarInt();
              if (rows < 0 || rows > PLAN_ROWS) throw new IllegalArgumentException("Plan rows");
              var plan = new ArrayList<PlanRow>(rows);
              for (int index = 0; index < rows; index++)
                plan.add(
                    new PlanRow(
                        Entry.read(buffer),
                        buffer.readVarLong(),
                        buffer.readVarLong(),
                        buffer.readVarLong()));
              int planMore = buffer.readVarInt();
              int cycleCount = buffer.readVarInt();
              if (cycleCount < 0 || cycleCount > CYCLES)
                throw new IllegalArgumentException("Cycle count");
              var cycles = new ArrayList<String>(cycleCount);
              for (int index = 0; index < cycleCount; index++) cycles.add(buffer.readUtf(256));
              int size = buffer.readVarInt();
              if (size < 0 || size > JOBS) throw new IllegalArgumentException("Job row count");
              var jobs = new ArrayList<Job>(size);
              for (int index = 0; index < size; index++) jobs.add(readJob(buffer));
              var detailJob = readJob(buffer);
              boolean cancellable = buffer.readBoolean();
              int stepCount = buffer.readVarInt();
              if (stepCount < 0 || stepCount > DETAIL_STEPS)
                throw new IllegalArgumentException("Detail step count");
              var stepRows = new ArrayList<StepRow>(stepCount);
              for (int index = 0; index < stepCount; index++)
                stepRows.add(
                    new StepRow(
                        Entry.read(buffer),
                        buffer.readVarLong(),
                        buffer.readVarLong(),
                        WireEnums.read(buffer, StepStatus.class)));
              var detail = new Detail(detailJob, cancellable, stepRows, buffer.readVarInt());
              return new View(
                  menu,
                  session,
                  revision,
                  craftables,
                  offset,
                  total,
                  preview,
                  reason,
                  steps,
                  crafts,
                  missing,
                  plan,
                  planMore,
                  cycles,
                  jobs,
                  detail,
                  WireEnums.read(buffer, JobStatus.class),
                  readTree(buffer),
                  buffer.readBoolean(),
                  buffer.readBoolean());
            });

    private static List<TreeRow> readTree(RegistryFriendlyByteBuf buffer) {
      int count = buffer.readVarInt();
      if (count < 0 || count > PLAN_ROWS) throw new IllegalArgumentException("Tree rows");
      var rows = new ArrayList<TreeRow>(count);
      for (int index = 0; index < count; index++)
        rows.add(
            new TreeRow(
                Entry.read(buffer),
                buffer.readVarInt(),
                buffer.readVarLong(),
                WireEnums.read(buffer, PlanTree.Kind.class)));
      return rows;
    }
  }

  public static final Entry NONE = new Entry("", "", "");

  private TerminalJobPackets() {}
}
