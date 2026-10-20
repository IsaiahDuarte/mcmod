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
  /** KEEP toggles the viewer's keep-stock rule for a craftable row at {@code amount}. */
  public static final int HOME = 0, NEXT = 1, PREVIEW = 2, START = 3, CANCEL = 4, KEEP = 5;

  public static final int ROWS = 8, JOBS = 4, MISSING = 3;
  public static final int MAX_AMOUNT = 4_096;

  public record Command(
      int menu, UUID session, long revision, int action, int selection, int amount)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_job_command"));

    public Command {
      if (action < HOME
          || action > KEEP
          || selection < 0
          || selection >= ROWS
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

  /** One of the requester's jobs: output, exact crafts done/total, state and reason keys. */
  public record Job(Entry output, long done, long total, String state, String reason) {
    public Job {
      if (done < 0 || total < done || state.length() > 32 || reason.length() > 64)
        throw new IllegalArgumentException("Invalid job row bounds");
    }
  }

  public record View(
      int menu,
      UUID session,
      long revision,
      List<Entry> craftables,
      boolean next,
      Entry preview,
      String previewReason,
      int previewSteps,
      long previewCrafts,
      List<Entry> missing,
      List<Job> jobs,
      String status)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "terminal_job_view"));

    public View {
      craftables = List.copyOf(craftables);
      missing = List.copyOf(missing);
      jobs = List.copyOf(jobs);
      if (craftables.size() > ROWS
          || missing.size() > MISSING
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
              buffer.writeBoolean(value.next);
              Entry.write(buffer, value.preview);
              buffer.writeUtf(value.previewReason, 64);
              buffer.writeVarInt(value.previewSteps);
              buffer.writeVarLong(value.previewCrafts);
              entries(buffer, value.missing);
              buffer.writeVarInt(value.jobs.size());
              for (var job : value.jobs) {
                Entry.write(buffer, job.output());
                buffer.writeVarLong(job.done());
                buffer.writeVarLong(job.total());
                buffer.writeUtf(job.state(), 32);
                buffer.writeUtf(job.reason(), 64);
              }
              buffer.writeUtf(value.status, 64);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              UUID session = buffer.readUUID();
              long revision = buffer.readLong();
              var craftables = entries(buffer, ROWS);
              boolean next = buffer.readBoolean();
              var preview = Entry.read(buffer);
              String reason = buffer.readUtf(64);
              int steps = buffer.readVarInt();
              long crafts = buffer.readVarLong();
              var missing = entries(buffer, MISSING);
              int size = buffer.readVarInt();
              if (size < 0 || size > JOBS) throw new IllegalArgumentException("Job row count");
              var jobs = new ArrayList<Job>(size);
              for (int index = 0; index < size; index++)
                jobs.add(
                    new Job(
                        Entry.read(buffer),
                        buffer.readVarLong(),
                        buffer.readVarLong(),
                        buffer.readUtf(32),
                        buffer.readUtf(64)));
              return new View(
                  menu,
                  session,
                  revision,
                  craftables,
                  next,
                  preview,
                  reason,
                  steps,
                  crafts,
                  missing,
                  jobs,
                  buffer.readUtf(64));
            });
  }

  public static final Entry NONE = new Entry("", "", "");

  private TerminalJobPackets() {}
}
