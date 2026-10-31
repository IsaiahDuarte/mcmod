package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.platform.FactoryCoreMod;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * A5: a requester's job finished or was cancelled. The client shows a toast while a screen is open
 * or {@code toast} is set (Q99: the requester carries a charged, bound Wireless Terminal), and the
 * usual chat line otherwise; a client without this payload gets the chat line from the server
 * instead. {@code icon} is the requested registry ID; the client names it in its own language.
 */
public record JobNotice(String icon, long amount, boolean cancelled, boolean toast)
    implements CustomPacketPayload {
  public static final Type<JobNotice> TYPE =
      new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "job_notice"));

  public static final StreamCodec<RegistryFriendlyByteBuf, JobNotice> CODEC =
      StreamCodec.of(
          (buffer, value) -> {
            buffer.writeUtf(value.icon, 256);
            buffer.writeVarLong(value.amount);
            buffer.writeBoolean(value.cancelled);
            buffer.writeBoolean(value.toast);
          },
          buffer ->
              new JobNotice(
                  buffer.readUtf(256),
                  buffer.readVarLong(),
                  buffer.readBoolean(),
                  buffer.readBoolean()));

  /** Set by the client at setup; mandatory code never loads client classes itself. */
  private static volatile Consumer<JobNotice> sink = notice -> {};

  public JobNotice {
    if (icon.length() > 256 || amount < 1) throw new IllegalArgumentException("Job notice bounds");
  }

  public JobNotice(String icon, long amount, boolean cancelled) {
    this(icon, amount, cancelled, false);
  }

  public static void sink(Consumer<JobNotice> client) {
    sink = client;
  }

  @Override
  public Type<JobNotice> type() {
    return TYPE;
  }

  static void register(RegisterPayloadHandlersEvent event) {
    event
        .registrar("2")
        .optional()
        .playToClient(TYPE, CODEC, (payload, context) -> sink.accept(payload));
  }
}
