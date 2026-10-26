package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.ServerScoped;
import dev.izzy.factorycore.platform.client.LabelToolScreen;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.signal.SignalInterfaceEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * D8 (PD3 option a): a handheld Label Tool. Use on a Machine or Signal Interface to apply its
 * label; sneak-use on a Machine or Storage Interface to copy its settings (VIEW); then use on
 * others of the same mode to paste them (EDIT per Interface). The first paste asks for a second use
 * on the same Interface, refused as stale if its configuration changed in between; a confirmed
 * paste opens a {@link #BATCH_TICKS} window in which further pastes need no confirmation. Use in
 * the air to set the label or forget the copy.
 */
public final class LabelToolItem extends Item {
  public static final int MAX_LABEL = 64;
  public static final int CONFIRM_TICKS = 200, BATCH_TICKS = 600;

  /** Server-thread confirmation state per player; runtime only. */
  private record Pending(BlockPos at, long generation, long confirmUntil, long batchUntil) {}

  private static final ServerScoped<Map<UUID, Pending>> PENDING =
      ServerScoped.of((MinecraftServer server) -> new HashMap<>());

  public LabelToolItem() {
    super(new Item.Properties().stacksTo(1));
  }

  /** Labels as players type them: control characters removed, trimmed. */
  public static String clean(String label) {
    return label.replaceAll("[\\p{Cntrl}§]", "").strip();
  }

  private static LabelToolContent.Data data(ItemStack stack) {
    return stack.getOrDefault(LabelToolContent.DATA.get(), LabelToolContent.Data.EMPTY);
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    var stack = player.getItemInHand(hand);
    // The client class is only resolved on the client branch.
    if (level.isClientSide()) LabelToolScreen.open(hand, data(stack).label(), data(stack).copied());
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    var level = context.getLevel();
    var entity = level.getBlockEntity(context.getClickedPos());
    if (!(entity instanceof ConnectorEntity) && !(entity instanceof SignalInterfaceEntity)) {
      // H5: any other block with an entity may be a machine on a cable.
      if (entity == null
          || level.getBlockState(context.getClickedPos()).getBlock()
              instanceof dev.izzy.factorycore.platform.network.NetworkedBlock)
        return InteractionResult.PASS;
      if (level.isClientSide()) return InteractionResult.SUCCESS;
      if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
      String outcome;
      try {
        outcome = labelMachine(player, data(context.getItemInHand()), context);
      } catch (IllegalArgumentException | IllegalStateException refused) {
        outcome = "invalid";
      }
      player.displayClientMessage(
          Component.translatable("message.factorycore.label_tool." + outcome), true);
      return InteractionResult.CONSUME;
    }
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
    var stack = context.getItemInHand();
    var tool = data(stack);
    String outcome;
    try {
      if (player.isShiftKeyDown()) outcome = copy(player, stack, tool, entity);
      else if (tool.copied()) outcome = paste(player, tool, entity);
      else outcome = label(player, tool, entity);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      outcome = "invalid";
    }
    player.displayClientMessage(
        Component.translatable("message.factorycore.label_tool." + outcome), true);
    return InteractionResult.CONSUME;
  }

  private static String copy(
      ServerPlayer player, ItemStack stack, LabelToolContent.Data tool, Object entity) {
    if (!(entity instanceof ConnectorEntity port)) return "copy_unsupported";
    if (!WorldNetworks.get(player.server)
        .allows(Actors.of(player), port.position(), NetworkPermissions.Permission.VIEW))
      return "denied";
    var copy = new CompoundTag();
    copy.putInt("Schema", 1);
    copy.putString("Mode", port instanceof MachineInterfaceEntity ? "MACHINE" : "STORAGE");
    var settings = port.settings();
    copy.putString("Kind", settings.kind().name());
    copy.putString("Access", settings.access().name());
    copy.putInt("Priority", settings.priority());
    copy.putInt("FirstSlot", settings.firstSlot());
    copy.putInt("SlotCount", settings.slotCount());
    copy.putInt("Rate", settings.rate());
    if (settings.insert() != null) copy.put("Insert", FilterCodec.encode(settings.insert()));
    copy.putString("Label", port.label());
    stack.set(
        LabelToolContent.DATA.get(),
        new LabelToolContent.Data(port.label().isEmpty() ? tool.label() : port.label(), copy));
    PENDING.get(player.server).remove(player.getUUID());
    return "copied";
  }

  private static ConnectorSettings settings(CompoundTag copy) {
    if (copy.getInt("Schema") != 1) throw new IllegalArgumentException("Copy schema");
    return new ConnectorSettings(
        ResourceKind.valueOf(copy.getString("Kind")),
        ConnectorSettings.Access.valueOf(copy.getString("Access")),
        copy.getInt("Priority"),
        copy.getInt("FirstSlot"),
        copy.getInt("SlotCount"),
        copy.getInt("Rate"),
        copy.contains("Insert")
            ? FilterCodec.decode(copy.getCompound("Insert"), "label tool")
            : null);
  }

  private static String paste(ServerPlayer player, LabelToolContent.Data tool, Object entity) {
    if (!(entity instanceof ConnectorEntity port)) return "paste_mismatch";
    boolean machine = port instanceof MachineInterfaceEntity;
    if (!tool.copy().getString("Mode").equals(machine ? "MACHINE" : "STORAGE"))
      return "paste_mismatch";
    var pending = PENDING.get(player.server);
    long now = player.server.getTickCount();
    var before = pending.get(player.getUUID());
    var at = port.getBlockPos();
    boolean batch = before != null && before.batchUntil() >= now;
    if (!batch) {
      if (before == null || !before.at().equals(at) || before.confirmUntil() < now) {
        pending.put(player.getUUID(), new Pending(at, port.generation(), now + CONFIRM_TICKS, -1));
        return "confirm";
      }
      if (before.generation() != port.generation()) {
        pending.put(player.getUUID(), new Pending(at, port.generation(), now + CONFIRM_TICKS, -1));
        return "stale";
      }
    }
    var copied = settings(tool.copy());
    // The target's own throughput tier bounds the rate; a higher copied rate is lowered to fit.
    int rate = Math.min(copied.rate(), port.tier().portRate(copied.kind()));
    var applied =
        new ConnectorSettings(
            copied.kind(),
            copied.access(),
            copied.priority(),
            copied.firstSlot(),
            copied.slotCount(),
            rate,
            machine ? null : copied.insert());
    var actor = Actors.of(player);
    var decision =
        machine
            ? ((MachineInterfaceEntity) port)
                .applySettings(actor, applied, tool.copy().getString("Label"))
            : port.configure(actor, applied);
    if (decision != NetworkAuthority.Decision.ALLOWED) return "denied";
    pending.put(player.getUUID(), new Pending(at, port.generation(), -1, now + BATCH_TICKS));
    return rate < copied.rate() ? "pasted_rate" : "pasted";
  }

  /**
   * H5 (ADR 0065): labels the machine itself. The cable port already aimed at it is relabelled;
   * otherwise the network cable touching it (the one on the clicked face first) becomes a cable
   * port aimed at it, under the player's EDIT where that cable is, and takes the label once the
   * network has rebuilt.
   */
  private static String labelMachine(
      ServerPlayer player, LabelToolContent.Data tool, UseOnContext context) {
    if (tool.label().isEmpty()) return "no_label";
    var level = player.serverLevel();
    var machine = context.getClickedPos();
    var actor = Actors.of(player);
    for (var side : Direction.values()) {
      var at = machine.relative(side);
      var state = level.getBlockState(at);
      if (state.getBlock() instanceof CablePortBlock
          && state.getValue(BlockStateProperties.FACING) == side.getOpposite()
          && level.getBlockEntity(at) instanceof MachineInterfaceEntity port)
        return port.applySettings(actor, port.settings(), tool.label())
                == NetworkAuthority.Decision.ALLOWED
            ? "labeled"
            : "denied";
    }
    var sides = new java.util.ArrayList<Direction>(6);
    sides.add(context.getClickedFace());
    for (var side : Direction.values()) if (side != context.getClickedFace()) sides.add(side);
    for (var side : sides) {
      var at = machine.relative(side);
      if (!(level.getBlockState(at).getBlock()
          instanceof dev.izzy.factorycore.platform.network.NetworkCableBlock)) continue;
      if (level.getCapability(Capabilities.ItemHandler.BLOCK, machine, side) == null
          && level.getCapability(Capabilities.FluidHandler.BLOCK, machine, side) == null
          && level.getCapability(Capabilities.EnergyStorage.BLOCK, machine, side) == null)
        return "label_unsupported";
      if (!WorldNetworks.get(player.server)
          .allows(
              actor,
              dev.izzy.factorycore.platform.Positions.of(level, at),
              NetworkPermissions.Permission.EDIT)) return "denied";
      var block = PortContent.CABLE_PORT.get();
      level.setBlock(at, block.aimed(level, at, side.getOpposite()), Block.UPDATE_ALL);
      if (!(level.getBlockEntity(at) instanceof MachineInterfaceEntity port)) return "invalid";
      port.place(new ItemStack(PortContent.CABLE_PORT_ITEM.get()));
      port.requestLabel(player.getUUID(), tool.label());
      level.scheduleTick(at, block, MachineInterfaceEntity.RESUME_TICKS);
      return "port_made";
    }
    return "no_cable";
  }

  private static String label(ServerPlayer player, LabelToolContent.Data tool, Object entity) {
    if (tool.label().isEmpty()) return "no_label";
    var actor = Actors.of(player);
    if (entity instanceof SignalInterfaceEntity signal) {
      if (!WorldNetworks.get(player.server)
          .allows(actor, signal.origin(), NetworkPermissions.Permission.EDIT)) return "denied";
      signal.label(tool.label());
      return "labeled";
    }
    if (!(entity instanceof MachineInterfaceEntity machine)) return "label_storage";
    return machine.applySettings(actor, machine.settings(), tool.label())
            == NetworkAuthority.Decision.ALLOWED
        ? "labeled"
        : "denied";
  }
}
