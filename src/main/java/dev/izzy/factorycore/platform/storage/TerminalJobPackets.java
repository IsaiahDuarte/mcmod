package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.ArrayList;
import java.util.List;
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
          || action > DETAIL
          || selection < 0
          || selection
              >= (action == WINDOW
                  ? MAX_OFFSET + 1
                  : action == DETAIL || action == CANCEL ? JOBS : ROWS)
          || amount < 1
          || amount > MAX_AMOUNT) throw new IllegalArgumentException("Invalid job command bounds");
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
   * One displayed item with an exact decimal amount (empty when not applicable). {@code note} is a
   * status key: on a kept craftable row, why its keep-stock rule is waiting ("" when satisfied).
   */
  public record Entry(String icon, String name, String amount, String note) {
    public Entry {
      if (icon.length() > 256 || name.length() > 96 || amount.length() > 32 || note.length() > 64)
        throw new IllegalArgumentException("Invalid job entry bounds");
    }

    public Entry(String icon, String name, String amount) {
      this(icon, name, amount, "");
    }

    static void write(RegistryFriendlyByteBuf buffer, Entry value) {
      buffer.writeUtf(value.icon, 256);
      buffer.writeUtf(value.name, 96);
      buffer.writeUtf(value.amount, 32);
      buffer.writeUtf(value.note, 64);
    }

    static Entry read(RegistryFriendlyByteBuf buffer) {
      return new Entry(
          buffer.readUtf(256), buffer.readUtf(96), buffer.readUtf(32), buffer.readUtf(64));
    }
  }

  /**
   * One resource of the previewed plan (A2): units taken from stock, crafted by the plan and
   * missing. The entry's amount is empty; the three counts are exact.
   */
  public record PlanRow(Entry item, long stored, long crafted, long missing) {
    public PlanRow {
      if (stored < 0 || crafted < 0 || missing < 0)
        throw new IllegalArgumentException("Invalid plan row bounds");
    }
  }

  /** One of the requester's jobs: output, exact crafts done/total, state and reason keys. */
  /**
   * One listed job: output, exact crafts done/total, state and reason keys. {@code owner} is the
   * requester's name for another player's job (shown to Manage), empty for the viewer's own.
   */
  public record Job(
      Entry output, long done, long total, String state, String reason, String owner) {
    public Job(Entry output, long done, long total, String state, String reason) {
      this(output, done, total, state, reason, "");
    }

    public Job {
      if (done < 0
          || total < done
          || state.length() > 32
          || reason.length() > 64
          || owner.length() > 48) throw new IllegalArgumentException("Invalid job row bounds");
    }
  }

  /**
   * One step of the opened job (A4): its output, crafts done/total, and a status key: done, running
   * (the current step), waiting (the current step; the job's reason says why) or queued.
   */
  public record StepRow(Entry output, long done, long total, String status) {
    public StepRow {
      if (done < 0 || total < done || status.length() > 16)
        throw new IllegalArgumentException("Invalid step row bounds");
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

  public static final Detail NO_DETAIL =
      new Detail(new Job(new Entry("", "", ""), 0, 0, "", ""), false, List.of(), 0);

  static void writeJob(RegistryFriendlyByteBuf buffer, Job job) {
    Entry.write(buffer, job.output());
    buffer.writeVarLong(job.done());
    buffer.writeVarLong(job.total());
    buffer.writeUtf(job.state(), 32);
    buffer.writeUtf(job.reason(), 64);
    buffer.writeUtf(job.owner(), 48);
  }

  static Job readJob(RegistryFriendlyByteBuf buffer) {
    return new Job(
        Entry.read(buffer),
        buffer.readVarLong(),
        buffer.readVarLong(),
        buffer.readUtf(32),
        buffer.readUtf(64),
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
      String previewReason,
      int previewSteps,
      long previewCrafts,
      List<Entry> missing,
      List<PlanRow> plan,
      int planMore,
      List<String> cycles,
      List<Job> jobs,
      Detail detail,
      String status)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_job_view"));

    public View {
      craftables = List.copyOf(craftables);
      missing = List.copyOf(missing);
      plan = List.copyOf(plan);
      cycles = List.copyOf(cycles);
      jobs = List.copyOf(jobs);
      if (craftables.size() > ROWS
          || offset < 0
          || total < offset + craftables.size()
          || missing.size() > MISSING
          || plan.size() > PLAN_ROWS
          || planMore < 0
          || cycles.size() > CYCLES
          || cycles.stream().anyMatch(cycle -> cycle.length() > 256)
          || jobs.size() > JOBS
          || previewReason.length() > 64
          || previewSteps < 0
          || previewCrafts < 0
          || status.length() > 64) throw new IllegalArgumentException("Invalid job view bounds");
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
              buffer.writeUtf(value.previewReason, 64);
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
                buffer.writeUtf(step.status(), 16);
              }
              buffer.writeVarInt(value.detail.moreSteps());
              buffer.writeUtf(value.status, 64);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              UUID session = buffer.readUUID();
              long revision = buffer.readLong();
              var craftables = entries(buffer, ROWS);
              int offset = buffer.readVarInt();
              int total = buffer.readVarInt();
              var preview = Entry.read(buffer);
              String reason = buffer.readUtf(64);
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
                        buffer.readUtf(16)));
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
                  buffer.readUtf(64));
            });
  }

  public static final Entry NONE = new Entry("", "", "");

  private TerminalJobPackets() {}
}
