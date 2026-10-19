package dev.izzy.factorycore.platform.program;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Program editor payloads. The draft travels in at most eight 2,048-character chunks so a full
 * 16,384-character program fits serverbound limits; the server owns compilation and deployment.
 */
public final class ProgramPackets {
  public static final int CHUNK = 0, CHECK = 1, DEPLOY = 2, PAUSE = 3, RESUME = 4, STOP = 5;
  public static final int CHUNK_CHARS = 2_048, MAX_CHUNKS = 8, LINES = 8;

  public record Command(int menu, UUID session, int action, int index, int count, String text)
      implements CustomPacketPayload {
    public static final Type<Command> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "program_command"));

    public Command {
      if (action < CHUNK
          || action > STOP
          || count < 1
          || count > MAX_CHUNKS
          || index < 0
          || index >= count
          || text.length() > CHUNK_CHARS
          || action != CHUNK && !text.isEmpty())
        throw new IllegalArgumentException("Invalid program command bounds");
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
              buffer.writeVarInt(value.action);
              buffer.writeVarInt(value.index);
              buffer.writeVarInt(value.count);
              buffer.writeUtf(value.text, CHUNK_CHARS * 3);
            },
            buffer ->
                new Command(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(CHUNK_CHARS * 3)));
  }

  /** Status, compiler diagnostics ("line:column message") and recent logs. */
  public record View(
      int menu,
      UUID session,
      long revision,
      String state,
      String message,
      long energy,
      String status,
      boolean deployed,
      List<String> diagnostics,
      List<String> logs)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "program_view"));

    public View {
      diagnostics = List.copyOf(diagnostics);
      logs = List.copyOf(logs);
      if (state.length() > 32
          || message.length() > 256
          || status.length() > 32
          || energy < 0
          || diagnostics.size() > LINES
          || logs.size() > LINES
          || diagnostics.stream().anyMatch(line -> line.length() > 192)
          || logs.stream().anyMatch(line -> line.length() > 192))
        throw new IllegalArgumentException("Invalid program view bounds");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }

    private static void lines(RegistryFriendlyByteBuf buffer, List<String> values) {
      buffer.writeVarInt(values.size());
      for (var value : values) buffer.writeUtf(value, 192 * 3);
    }

    private static List<String> lines(RegistryFriendlyByteBuf buffer) {
      int size = buffer.readVarInt();
      if (size < 0 || size > LINES) throw new IllegalArgumentException("Line count");
      var values = new ArrayList<String>(size);
      for (int index = 0; index < size; index++) values.add(buffer.readUtf(192 * 3));
      return values;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeLong(value.revision);
              buffer.writeUtf(value.state, 32);
              buffer.writeUtf(value.message, 256 * 3);
              buffer.writeVarLong(value.energy);
              buffer.writeUtf(value.status, 32);
              buffer.writeBoolean(value.deployed);
              lines(buffer, value.diagnostics);
              lines(buffer, value.logs);
            },
            buffer ->
                new View(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readLong(),
                    buffer.readUtf(32),
                    buffer.readUtf(256 * 3),
                    buffer.readVarLong(),
                    buffer.readUtf(32),
                    buffer.readBoolean(),
                    lines(buffer),
                    lines(buffer)));
  }

  /** One chunk of a Wasm artifact upload; the last chunk deploys it (needs DEPLOY). */
  public record Artifact(int menu, UUID session, int index, int count, byte[] bytes)
      implements CustomPacketPayload {
    public static final int CHUNK_BYTES = 8_192, MAX_CHUNKS = 16;
    public static final Type<Artifact> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "program_artifact"));

    public Artifact {
      bytes = bytes.clone();
      if (count < 1
          || count > MAX_CHUNKS
          || index < 0
          || index >= count
          || bytes.length > CHUNK_BYTES)
        throw new IllegalArgumentException("Invalid artifact chunk bounds");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public Type<Artifact> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Artifact> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeVarInt(value.index);
              buffer.writeVarInt(value.count);
              buffer.writeByteArray(value.bytes);
            },
            buffer ->
                new Artifact(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readByteArray(CHUNK_BYTES)));

    /** Splits an artifact (at most 128 KiB) into bounded upload chunks. */
    public static List<Artifact> chunks(int menu, UUID session, byte[] artifact) {
      if (artifact.length > CHUNK_BYTES * MAX_CHUNKS)
        throw new IllegalArgumentException("Artifact too large");
      int count = Math.max(1, (artifact.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
      var chunks = new ArrayList<Artifact>(count);
      for (int index = 0; index < count; index++)
        chunks.add(
            new Artifact(
                menu,
                session,
                index,
                count,
                java.util.Arrays.copyOfRange(
                    artifact,
                    index * CHUNK_BYTES,
                    Math.min(artifact.length, (index + 1) * CHUNK_BYTES))));
      return chunks;
    }
  }

  /** The current draft, sent once when the editor opens. */
  public record Draft(int menu, UUID session, String text) implements CustomPacketPayload {
    public static final Type<Draft> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "program_draft"));

    public Draft {
      if (text.length() > ProgramControllerEntity.MAX_DRAFT)
        throw new IllegalArgumentException("Draft too long");
    }

    @Override
    public Type<Draft> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Draft> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeUtf(value.text, ProgramControllerEntity.MAX_DRAFT * 3);
            },
            buffer ->
                new Draft(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readUtf(ProgramControllerEntity.MAX_DRAFT * 3)));
  }

  /** Explicit authoring queries, independently throttled; they never deploy or execute a rule. */
  public record AuthorRequest(int menu, UUID session, boolean preview, int rule, String after)
      implements CustomPacketPayload {
    public static final Type<AuthorRequest> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "program_author_request"));

    public AuthorRequest {
      if (rule < 0 || rule >= 64 || after.length() > 64)
        throw new IllegalArgumentException("Invalid authoring request");
    }

    @Override
    public Type<AuthorRequest> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, AuthorRequest> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeBoolean(value.preview);
              buffer.writeVarInt(value.rule);
              buffer.writeUtf(value.after, 192);
            },
            buffer ->
                new AuthorRequest(
                    buffer.readVarInt(),
                    buffer.readUUID(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readUtf(192)));
  }

  /** A single local-label page or read-only rule preview, with the exact current segment scope. */
  public record AuthorView(
      int menu,
      UUID session,
      String scope,
      List<String> labels,
      boolean more,
      int rule,
      int total,
      List<String> preview)
      implements CustomPacketPayload {
    public static final Type<AuthorView> TYPE =
        new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "program_author_view"));

    public AuthorView {
      labels = List.copyOf(labels);
      preview = List.copyOf(preview);
      if (scope.length() > 80
          || labels.size() > 16
          || labels.stream().anyMatch(label -> label.length() > 64)
          || rule < 0
          || rule >= 64
          || total < 0
          || total > 64
          || preview.size() > LINES
          || preview.stream().anyMatch(line -> line.length() > 192))
        throw new IllegalArgumentException("Invalid authoring view");
    }

    @Override
    public Type<AuthorView> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, AuthorView> CODEC =
        StreamCodec.of(
            (buffer, value) -> {
              buffer.writeVarInt(value.menu);
              buffer.writeUUID(value.session);
              buffer.writeUtf(value.scope, 240);
              buffer.writeVarInt(value.labels.size());
              for (var label : value.labels) buffer.writeUtf(label, 192);
              buffer.writeBoolean(value.more);
              buffer.writeVarInt(value.rule);
              buffer.writeVarInt(value.total);
              View.lines(buffer, value.preview);
            },
            buffer -> {
              int menu = buffer.readVarInt();
              var session = buffer.readUUID();
              var scope = buffer.readUtf(240);
              int count = buffer.readVarInt();
              if (count < 0 || count > 16) throw new IllegalArgumentException("Label count");
              var labels = new ArrayList<String>();
              for (int index = 0; index < count; index++) labels.add(buffer.readUtf(192));
              return new AuthorView(
                  menu,
                  session,
                  scope,
                  labels,
                  buffer.readBoolean(),
                  buffer.readVarInt(),
                  buffer.readVarInt(),
                  View.lines(buffer));
            });
  }

  private ProgramPackets() {}

  /** Splits text into the bounded chunk commands that replace a draft. */
  public static List<Command> chunks(int menu, UUID session, String text) {
    if (text.length() > ProgramControllerEntity.MAX_DRAFT)
      throw new IllegalArgumentException("Draft too long");
    int count = Math.max(1, (text.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
    var commands = new ArrayList<Command>(count);
    for (int index = 0; index < count; index++)
      commands.add(
          new Command(
              menu,
              session,
              CHUNK,
              index,
              count,
              text.substring(
                  index * CHUNK_CHARS, Math.min(text.length(), (index + 1) * CHUNK_CHARS))));
    return commands;
  }

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    registrar.playToServer(
        Command.TYPE,
        Command.CODEC,
        (value, context) -> {
          if (context.player() instanceof ServerPlayer player
              && player.containerMenu instanceof ProgramMenu menu) menu.command(player, value);
        });
    registrar.playToServer(
        AuthorRequest.TYPE,
        AuthorRequest.CODEC,
        (value, context) -> {
          if (context.player() instanceof ServerPlayer player
              && player.containerMenu instanceof ProgramMenu menu) menu.author(player, value);
        });
    registrar.playToClient(
        AuthorView.TYPE,
        AuthorView.CODEC,
        (value, context) -> {
          if (context.player().containerMenu instanceof ProgramMenu menu) menu.accept(value);
        });
    registrar.playToServer(
        Artifact.TYPE,
        Artifact.CODEC,
        (value, context) -> {
          if (context.player() instanceof ServerPlayer player
              && player.containerMenu instanceof ProgramMenu menu) menu.artifact(player, value);
        });
    registrar.playToClient(
        View.TYPE,
        View.CODEC,
        (value, context) -> {
          if (context.player().containerMenu instanceof ProgramMenu menu) menu.accept(value);
        });
    registrar.playToClient(
        Draft.TYPE,
        Draft.CODEC,
        (value, context) -> {
          if (context.player().containerMenu instanceof ProgramMenu menu) menu.accept(value);
        });
  }
}
