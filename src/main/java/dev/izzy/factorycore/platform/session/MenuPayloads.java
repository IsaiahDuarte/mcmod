package dev.izzy.factorycore.platform.session;

import java.util.function.BiConsumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers screen payloads in one line each: a command goes to the sender's open menu of the given
 * type, a view to the receiving player's open menu. Payloads for any other open menu are dropped,
 * and the menu itself validates the session (see {@link MenuSession}).
 */
public final class MenuPayloads {
  private MenuPayloads() {}

  /** Handles a client command on the server for an open menu. */
  @FunctionalInterface
  public interface ServerHandler<M, P> {
    void handle(M menu, ServerPlayer sender, P payload);
  }

  public static <M, P extends CustomPacketPayload> void toServer(
      PayloadRegistrar registrar,
      CustomPacketPayload.Type<P> type,
      StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
      Class<M> menu,
      ServerHandler<M, P> handler) {
    registrar.playToServer(
        type,
        codec,
        (payload, context) -> {
          if (context.player() instanceof ServerPlayer player
              && menu.isInstance(player.containerMenu))
            handler.handle(menu.cast(player.containerMenu), player, payload);
        });
  }

  public static <M, P extends CustomPacketPayload> void toClient(
      PayloadRegistrar registrar,
      CustomPacketPayload.Type<P> type,
      StreamCodec<? super RegistryFriendlyByteBuf, P> codec,
      Class<M> menu,
      BiConsumer<M, P> handler) {
    registrar.playToClient(
        type,
        codec,
        (payload, context) -> {
          if (menu.isInstance(context.player().containerMenu))
            handler.accept(menu.cast(context.player().containerMenu), payload);
        });
  }
}
