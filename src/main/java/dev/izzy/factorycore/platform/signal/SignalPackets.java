package dev.izzy.factorycore.platform.signal;

import dev.izzy.factorycore.core.EnumKeys;
import dev.izzy.factorycore.core.resource.ResourceFilter;
import dev.izzy.factorycore.core.storage.SignalRule;
import dev.izzy.factorycore.platform.FactoryCoreMod;
import dev.izzy.factorycore.platform.session.MenuPayloads;
import dev.izzy.factorycore.platform.session.WireEnums;
import dev.izzy.factorycore.platform.storage.FilterEdits;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** C1 Signal Interface screen payloads; the client never names a resource with components. */
public final class SignalPackets {
  /** The last edit's outcome, shown as {@code gui.factorycore.signal.status.<key>}. */
  public enum Status {
    READY,
    LOADING,
    DRAFT,
    LABELED,
    APPLIED,
    INVALID,
    DENIED,
    SWITCH_SAME,
    SWITCH_DENIED,
    SWITCH_BUSY,
    SWITCH_NOT_EMPTY,
    SWITCH_UPGRADED,
    SWITCH_FAULT;

    public String key() {
      return EnumKeys.key(this);
    }

    /** An edit that worked. */
    public boolean good() {
      return this == APPLIED || this == LABELED;
    }
  }

  /**
   * The screen's state. {@code watch} is the watched filter's slots (Q24; empty for none), {@code
   * fluid} its kind, {@code power} the strength now emitted and {@code reason} why it is off (a key
   * suffix, "" when evaluated). {@code status} is the last edit's outcome.
   */
  public record View(
      int menu,
      UUID session,
      long revision,
      SignalRule rule,
      List<FilterEdits.Rule> watch,
      boolean fluid,
      int power,
      String reason,
      boolean editable,
      Status status,
      String label)
      implements CustomPacketPayload {
    public static final Type<View> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "signal_view"));

    public View {
      Objects.requireNonNull(rule);
      Objects.requireNonNull(status);
      watch = List.copyOf(watch);
      if (watch.size() > ResourceFilter.MAX_TERMS
          || power < 0
          || power > SignalRule.MAX
          || reason.length() > 32
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
              b.writeVarInt(v.watch.size());
              for (var rule : v.watch) FilterEdits.Rule.write(b, rule);
              b.writeBoolean(v.fluid);
              b.writeVarInt(v.power);
              b.writeUtf(v.reason, 32);
              b.writeBoolean(v.editable);
              WireEnums.write(b, v.status);
              b.writeUtf(v.label, SignalInterfaceEntity.MAX_LABEL);
            },
            b ->
                new View(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readVarLong(),
                    readRule(b),
                    readWatch(b),
                    b.readBoolean(),
                    b.readVarInt(),
                    b.readUtf(32),
                    b.readBoolean(),
                    WireEnums.read(b, Status.class),
                    b.readUtf(SignalInterfaceEntity.MAX_LABEL)));
  }

  /**
   * RESOURCE adds a slot for the plain ID in {@code text} ({@code fluid} picks the kind; a slot of
   * the other kind starts a new list); CARRIED adds the cursor item (or the fluid inside it); TAG
   * adds the tag in {@code text}; CYCLE turns slot {@code selection}, showing {@code text}, to its
   * next match; REMOVE and CLEAR drop one or all slots. These edit a draft that APPLY saves with
   * {@code rule} under the sender's EDIT. LABEL sets the label programs name (D6) to {@code text}.
   * All of them are refused without EDIT.
   */
  public record Command(
      int menu,
      UUID session,
      int action,
      boolean fluid,
      String text,
      SignalRule rule,
      int selection)
      implements CustomPacketPayload {
    public static final int RESOURCE = 0,
        CARRIED = 1,
        APPLY = 2,
        LABEL = 3,
        TAG = 4,
        CYCLE = 5,
        REMOVE = 6,
        CLEAR = 7;

    public static final Type<Command> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryCoreMod.MOD_ID, "signal_command"));

    public Command {
      Objects.requireNonNull(rule);
      if (action < RESOURCE
          || action > CLEAR
          || text.length() > 256
          || selection < 0
          || selection >= ResourceFilter.MAX_TERMS)
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
              b.writeVarInt(v.selection);
            },
            b ->
                new Command(
                    b.readVarInt(),
                    b.readUUID(),
                    b.readVarInt(),
                    b.readBoolean(),
                    b.readUtf(256),
                    readRule(b),
                    b.readVarInt()));
  }

  private static List<FilterEdits.Rule> readWatch(RegistryFriendlyByteBuf b) {
    int count = b.readVarInt();
    if (count < 0 || count > ResourceFilter.MAX_TERMS)
      throw new IllegalArgumentException("Invalid signal view");
    var rules = new ArrayList<FilterEdits.Rule>(count);
    for (int i = 0; i < count; i++) rules.add(FilterEdits.Rule.read(b));
    return rules;
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
    var registrar = event.registrar("2");
    MenuPayloads.toServer(
        registrar, Command.TYPE, Command.CODEC, SignalMenu.class, SignalMenu::command);
    MenuPayloads.toClient(registrar, View.TYPE, View.CODEC, SignalMenu.class, SignalMenu::accept);
  }
}
