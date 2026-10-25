package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** C1 Signal Interface screen payloads; the client never names a resource with components. */
public final class SignalPackets {
  /**
   * The screen's state. {@code resource} is the watched registry ID ("" for none), {@code fluid}
   * its kind, {@code power} the strength now emitted and {@code reason} why it is off (a key
   * suffix, "" when evaluated). {@code status} names the last edit's outcome.
   */
  public record View(
      int menu,
      UUID session,
      long revision,
      SignalRule rule,
      String resource,
      String name,
      boolean fluid,
      int power,
      String reason,
      boolean editable,
      String status,
      String label)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "signal_view"));

    public View {
      Objects.requireNonNull(rule);
      if (resource.length() > 256
          || name.length() > 96
          || power < 0
          || power > SignalRule.MAX
          || reason.length() > 32
          || status.length() > 32
          || label.length() > SignalInterfaceEntity.MAX_LABEL)
        throw new IllegalArgumentException("Invalid signal view");
    }

    @Override
    public Type<View> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu);
              b.writeUUID(v.session);
              b.writeVarLong(v.revision);
              writeRule(b, v.rule);
              b.writeUtf(v.resource, 256);
              b.writeUtf(v.name, 96);
              b.writeBoolean(v.fluid);
              b.writeVarInt(v.power);
              b.writeUtf(v.reason, 32);
              b.writeBoolean(v.editable);
              b.writeUtf(v.status, 32);
              b.writeUtf(v.label, SignalInterfaceEntity.MAX_LABEL);
            },
            b ->
                new View(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readVarLong(),
                    readRule(b),
                    b.readUtf(256),
                    b.readUtf(96),
                    b.readBoolean(),
                    b.readVarInt(),
                    b.readUtf(32),
                    b.readBoolean(),
                    b.readUtf(32),
                    b.readUtf(SignalInterfaceEntity.MAX_LABEL)));
  }

  /**
   * RESOURCE sets the watched resource to the plain ID in {@code text} ({@code fluid} picks the
   * kind); CARRIED takes it from the cursor item; APPLY saves {@code rule} with that resource under
   * the sender's EDIT; LABEL sets the label programs name (D6) to {@code text}. All of them are
   * refused without EDIT.
   */
  public record Command(
      int menu, UUID session, int action, boolean fluid, String text, SignalRule rule)
      implements CustomPacketPayload {
    public static final int RESOURCE = 0, CARRIED = 1, APPLY = 2, LABEL = 3;

    public static final Type<Command> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "signal_command"));

    public Command {
      Objects.requireNonNull(rule);
      if (action < RESOURCE || action > LABEL || text.length() > 256)
        throw new IllegalArgumentException("Invalid signal command");
    }

    @Override
    public Type<Command> type() {
      return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC =
        StreamCodec.of(
            (b, v) -> {
              b.writeVarInt(v.menu);
              b.writeUUID(v.session);
              b.writeVarInt(v.action);
              b.writeBoolean(v.fluid);
              b.writeUtf(v.text, 256);
              writeRule(b, v.rule);
            },
            b ->
                new Command(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readVarInt(),
                    b.readBoolean(),
                    b.readUtf(256),
                    readRule(b)));
  }

  static void writeRule(RegistryFriendlyByteBuf b, SignalRule rule) {
    b.writeEnum(rule.condition());
    b.writeEnum(rule.compare());
    b.writeVarLong(rule.threshold());
    b.writeEnum(rule.output());
  }

  static SignalRule readRule(RegistryFriendlyByteBuf b) {
    return new SignalRule(
        b.readEnum(SignalRule.Condition.class),
        b.readEnum(SignalRule.Compare.class),
        b.readVarLong(),
        b.readEnum(SignalRule.Output.class));
  }

  private SignalPackets() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    var registrar = event.registrar("1");
    MenuPayloads.toServer(
        registrar, Command.TYPE, Command.CODEC, SignalMenu.class, SignalMenu::command);
    MenuPayloads.toClient(registrar, View.TYPE, View.CODEC, SignalMenu.class, SignalMenu::accept);
  }
}
