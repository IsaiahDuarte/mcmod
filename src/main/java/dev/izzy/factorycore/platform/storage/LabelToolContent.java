package dev.izzy.factorycore.platform.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.FactoryRegistries;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** D8 (PD3 option a): the Label Tool item, its saved data and its label packet. */
public final class LabelToolContent {
  /**
   * The tool's label and copied Interface settings ({@code copy} empty when none). The copy is the
   * {@link LabelToolItem} schema-1 tag: Kind, the connector settings fields, Label.
   */
  public record Data(String label, CompoundTag copy) {
    public static final Data EMPTY = new Data("", new CompoundTag());

    public Data {
      if (label.length() > LabelToolItem.MAX_LABEL) throw new IllegalArgumentException("Label");
      copy = copy.copy();
    }

    public boolean copied() {
      return !copy.isEmpty();
    }

    public static final Codec<Data> CODEC =
        RecordCodecBuilder.create(
            instance ->
                instance
                    .group(
                        Codec.string(0, LabelToolItem.MAX_LABEL)
                            .fieldOf("label")
                            .forGetter(Data::label),
                        CompoundTag.CODEC.fieldOf("copy").forGetter(Data::copy))
                    .apply(instance, Data::new));
    public static final StreamCodec<ByteBuf, Data> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(LabelToolItem.MAX_LABEL),
            Data::label,
            ByteBufCodecs.COMPOUND_TAG,
            Data::copy,
            Data::new);
  }

  public static final DeferredHolder<DataComponentType<?>, DataComponentType<Data>> DATA =
      FactoryRegistries.COMPONENTS.registerComponentType(
          "label_tool",
          builder -> builder.persistent(Data.CODEC).networkSynchronized(Data.STREAM_CODEC));

  public static final DeferredItem<LabelToolItem> ITEM =
      FactoryRegistries.ITEMS.register("label_tool", LabelToolItem::new);

  /** From the tool's screen: set the label and optionally forget the copied settings. */
  public record SetLabel(boolean mainHand, String label, boolean clearCopy)
      implements CustomPacketPayload {
    public static final Type<SetLabel> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "label_tool_set"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetLabel> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.BOOL,
            SetLabel::mainHand,
            ByteBufCodecs.stringUtf8(256),
            SetLabel::label,
            ByteBufCodecs.BOOL,
            SetLabel::clearCopy,
            SetLabel::new);

    @Override
    public Type<SetLabel> type() {
      return TYPE;
    }
  }

  private LabelToolContent() {}

  private static void handle(SetLabel packet, ServerPlayer player) {
    var stack =
        player.getItemInHand(
            packet.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
    if (!stack.is(ITEM.get())) return;
    var clean = LabelToolItem.clean(packet.label());
    if (clean.length() > LabelToolItem.MAX_LABEL) return;
    var data = stack.getOrDefault(DATA.get(), Data.EMPTY);
    stack.set(DATA.get(), new Data(clean, packet.clearCopy() ? new CompoundTag() : data.copy()));
  }

  public static void register(IEventBus bus) {
    bus.addListener(
        (RegisterPayloadHandlersEvent event) ->
            event
                .registrar("1")
                .playToServer(
                    SetLabel.TYPE,
                    SetLabel.CODEC,
                    // Handlers run on the server thread, as in MenuPayloads.
                    (packet, context) -> {
                      if (context.player() instanceof ServerPlayer player) handle(packet, player);
                    }));
  }
}
