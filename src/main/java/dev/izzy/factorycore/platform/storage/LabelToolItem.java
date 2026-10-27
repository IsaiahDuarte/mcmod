package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.core.resource.ThroughputTier;
import dev.izzy.factorycore.core.storage.ConnectorSettings;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.ClientScreens;
import dev.izzy.factorycore.platform.ServerScoped;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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

  /** Q9: machines one row label reaches, and cable-run blocks its walk visits. */
  public static final int MAX_ROW = 64, MAX_RUN = 2_048;

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
    if (level.isClientSide())
      ClientScreens.opener().labelTool(hand, data(stack).label(), data(stack).copied());
    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
  }

  /**
   * Machines are handled before the block's own use: otherwise a furnace or chest opens its screen
   * and the tool never reaches {@link #useOn}. Factory Core's own blocks already pass while an item
   * is in hand, so they keep the normal order.
   */
  @Override
  public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
    var level = context.getLevel();
    var entity = level.getBlockEntity(context.getClickedPos());
    var tool = data(stack);
    // Q13: copying from, or pasting onto, a Buffer, Signal, Drive, Gateway, Program Controller
    // or Crafter comes before the block's own use (a Drive or Crafter takes held items).
    boolean device = entity != null && DeviceCopy.kind(entity) != null;
    boolean copying = device && context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
    boolean pasting = device && !copying && tool.copied();
    if (!copying && !pasting)
      return machine(level, context.getClickedPos()) ? useOn(context) : InteractionResult.PASS;
    if (level.isClientSide()) return InteractionResult.SUCCESS;
    if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
    Component message;
    try {
      message =
          copying ? copyDevice(player, stack, tool, entity) : pasteDevice(player, tool, entity);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      message = Component.translatable("message.factorycore.label_tool.invalid");
    }
    player.displayClientMessage(message, true);
    return InteractionResult.CONSUME;
  }

  private static Component copyDevice(
      ServerPlayer player, ItemStack stack, LabelToolContent.Data tool, BlockEntity entity) {
    var copy = DeviceCopy.copy(player, entity);
    if (copy == null) return Component.translatable("message.factorycore.label_tool.denied");
    stack.set(LabelToolContent.DATA.get(), new LabelToolContent.Data(tool.label(), copy));
    PENDING.get(player.server).remove(player.getUUID());
    return Component.translatable("message.factorycore.label_tool.copied");
  }

  /** Q13: the same confirm-once and batch rule as port pastes, keyed on the device's settings. */
  private static Component pasteDevice(
      ServerPlayer player, LabelToolContent.Data tool, BlockEntity entity) {
    if (DeviceCopy.kind(tool.copy()) != DeviceCopy.kind(entity))
      return Component.translatable("message.factorycore.label_tool.paste_mismatch");
    var refusal = confirm(player, entity.getBlockPos(), DeviceCopy.fingerprint(entity));
    if (refusal != null) return Component.translatable("message.factorycore.label_tool." + refusal);
    var outcome = DeviceCopy.paste(player, tool.copy(), entity);
    if (outcome.key().startsWith("pasted")) batch(player, entity.getBlockPos());
    return Component.translatable(
        "message.factorycore.label_tool." + outcome.key(), outcome.arguments());
  }

  /**
   * Null when a paste at {@code at} may go ahead now (confirmed, or inside a batch window), else
   * {@code confirm} or {@code stale}, having recorded the prompt. {@code generation} is whatever
   * changes when the target's settings do.
   */
  private static String confirm(ServerPlayer player, BlockPos at, long generation) {
    var pending = PENDING.get(player.server);
    long now = player.server.getTickCount();
    var before = pending.get(player.getUUID());
    if (before != null && before.batchUntil() >= now) return null;
    if (before == null || !before.at().equals(at) || before.confirmUntil() < now) {
      pending.put(player.getUUID(), new Pending(at, generation, now + CONFIRM_TICKS, -1));
      return "confirm";
    }
    if (before.generation() != generation) {
      pending.put(player.getUUID(), new Pending(at, generation, now + CONFIRM_TICKS, -1));
      return "stale";
    }
    return null;
  }

  private static void batch(ServerPlayer player, BlockPos at) {
    long now = player.server.getTickCount();
    PENDING.get(player.server).put(player.getUUID(), new Pending(at, 0, -1, now + BATCH_TICKS));
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
      // Q12: in the network view, using the tool on a machine inspects it instead.
      if (LabelOverlay.mode(player) == LabelOverlay.Mode.NETWORK) {
        var view = MachineInspection.inspect(player, context.getClickedPos());
        if (view == null)
          player.displayClientMessage(
              Component.translatable("message.factorycore.label_tool.inspect_none"), true);
        else net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, view);
        return InteractionResult.CONSUME;
      }
      String outcome;
      try {
        outcome =
            labelMachine(
                player,
                data(context.getItemInHand()),
                context.getClickedPos(),
                context.getClickedFace());
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
    copy.putString("Tier", port.tier().name());
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
    // Q13: the copied upgrades go in first, from the player's inventory.
    int missing =
        tool.copy().contains("Tier")
            ? DeviceCopy.installUpgrades(
                player, port, ThroughputTier.valueOf(tool.copy().getString("Tier")))
            : 0;
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
    if (missing > 0) return "pasted_upgrades_missing";
    return rate < copied.rate() ? "pasted_rate" : "pasted";
  }

  /**
   * What labelling one machine will do, decided while the network is still valid: relabel the port
   * already aimed at it, turn the touching {@code cable} into a port aimed at it, or a refusal.
   */
  private record Plan(String refusal, MachineInterfaceEntity port, BlockPos cable, Direction side) {
    static Plan refused(String outcome) {
      return new Plan(outcome, null, null, null);
    }
  }

  /**
   * H5 (ADR 0065): labels the machine itself. The cable port already aimed at it is relabelled;
   * otherwise the network cable touching it (the {@code preferred} face first) becomes a cable port
   * aimed at it, under the player's EDIT where that cable is, and takes the label once the network
   * has rebuilt.
   */
  private static String labelMachine(
      ServerPlayer player, LabelToolContent.Data tool, BlockPos machine, Direction preferred) {
    var plan = plan(player, tool, machine, preferred);
    return plan.refusal() != null ? plan.refusal() : apply(player, tool, plan);
  }

  private static Plan plan(
      ServerPlayer player, LabelToolContent.Data tool, BlockPos machine, Direction preferred) {
    if (tool.label().isEmpty()) return Plan.refused("no_label");
    var level = player.serverLevel();
    for (var side : Direction.values()) {
      var at = machine.relative(side);
      var state = level.getBlockState(at);
      if (state.getBlock() instanceof CablePortBlock
          && state.getValue(BlockStateProperties.FACING) == side.getOpposite()
          && level.getBlockEntity(at) instanceof MachineInterfaceEntity port)
        return new Plan(null, port, null, null);
    }
    var sides = new java.util.ArrayList<Direction>(6);
    sides.add(preferred);
    for (var side : Direction.values()) if (side != preferred) sides.add(side);
    for (var side : sides) {
      var at = machine.relative(side);
      if (!(level.getBlockState(at).getBlock()
          instanceof dev.izzy.factorycore.platform.network.NetworkCableBlock)) continue;
      if (level.getCapability(Capabilities.ItemHandler.BLOCK, machine, side) == null
          && level.getCapability(Capabilities.FluidHandler.BLOCK, machine, side) == null
          && level.getCapability(Capabilities.EnergyStorage.BLOCK, machine, side) == null)
        return Plan.refused("label_unsupported");
      if (!WorldNetworks.get(player.server)
          .allows(
              Actors.of(player),
              dev.izzy.factorycore.platform.Positions.of(level, at),
              NetworkPermissions.Permission.EDIT)) return Plan.refused("denied");
      return new Plan(null, null, at.immutable(), side.getOpposite());
    }
    return Plan.refused("no_cable");
  }

  private static String apply(ServerPlayer player, LabelToolContent.Data tool, Plan plan) {
    if (plan.port() != null)
      return plan.port().applySettings(Actors.of(player), plan.port().settings(), tool.label())
              == NetworkAuthority.Decision.ALLOWED
          ? "labeled"
          : "denied";
    var level = player.serverLevel();
    var at = plan.cable();
    var block = PortContent.CABLE_PORT.get();
    level.setBlock(at, block.aimed(level, at, plan.side()), Block.UPDATE_ALL);
    if (!(level.getBlockEntity(at) instanceof MachineInterfaceEntity port)) return "invalid";
    port.place(new ItemStack(PortContent.CABLE_PORT_ITEM.get()));
    port.requestLabel(player.getUUID(), tool.label());
    level.scheduleTick(at, block, MachineInterfaceEntity.RESUME_TICKS);
    return "port_made";
  }

  /** True when the block at {@code at} is a machine the Label Tool labels by itself (H5). */
  static boolean machine(Level level, BlockPos at) {
    var entity = level.getBlockEntity(at);
    return entity != null
        && !(entity instanceof ConnectorEntity)
        && !(entity instanceof SignalInterfaceEntity)
        && !(level.getBlockState(at).getBlock()
            instanceof dev.izzy.factorycore.platform.network.NetworkedBlock);
  }

  /** Cables and cable ports form a cable run; devices end it. */
  static boolean run(BlockState state) {
    return state.getBlock() instanceof dev.izzy.factorycore.platform.network.NetworkCableBlock
        || state.getBlock() instanceof CablePortBlock;
  }

  /**
   * Q10: the label to pick from {@code at}: a cable port or Interface's own label, a Signal
   * Interface's, or for a machine the label of a cable port aimed at it. Empty when there is none;
   * reading a label needs VIEW where the labelled device is.
   */
  public static String pick(ServerPlayer player, BlockPos at) {
    var level = player.serverLevel();
    var actor = Actors.of(player);
    var networks = WorldNetworks.get(player.server);
    var entity = level.getBlockEntity(at);
    if (entity instanceof ConnectorEntity port)
      return networks.allows(actor, port.position(), NetworkPermissions.Permission.VIEW)
          ? port.label()
          : "";
    if (entity instanceof SignalInterfaceEntity signal)
      return networks.allows(actor, signal.origin(), NetworkPermissions.Permission.VIEW)
          ? signal.label()
          : "";
    if (!machine(level, at)) return "";
    for (var side : Direction.values()) {
      var next = at.relative(side);
      var state = level.getBlockState(next);
      if (state.getBlock() instanceof CablePortBlock
          && state.getValue(BlockStateProperties.FACING) == side.getOpposite()
          && level.getBlockEntity(next) instanceof MachineInterfaceEntity port
          && !port.label().isEmpty()
          && networks.allows(actor, port.position(), NetworkPermissions.Permission.VIEW))
        return port.label();
    }
    return "";
  }

  /** Q10: sets the tool's label to the one picked from {@code at}; false when there is none. */
  public static boolean pickInto(ServerPlayer player, ItemStack stack, BlockPos at) {
    if (!(stack.getItem() instanceof LabelToolItem)) return false;
    var label = clean(pick(player, at));
    if (label.isEmpty() || label.length() > MAX_LABEL) return false;
    stack.set(LabelToolContent.DATA.get(), new LabelToolContent.Data(label, data(stack).copy()));
    return true;
  }

  /** Q9: how a row label went. */
  public record RowResult(int labelled, int refused, boolean capped) {}

  /**
   * Q9: labels {@code clicked} and every other machine of the same block that touches the cable run
   * it touches, nearest along the run first, at most {@link #MAX_ROW}. Each machine goes through
   * the single-machine path, so each needs the player's EDIT where its cable is. The walk follows
   * wired faces of cables and cable ports only, visits at most {@link #MAX_RUN} loaded blocks and
   * never loads a chunk. Returns null when {@code clicked} is not such a machine.
   */
  public static RowResult labelRow(ServerPlayer player, ItemStack stack, BlockPos clicked) {
    var level = player.serverLevel();
    if (!(stack.getItem() instanceof LabelToolItem) || !machine(level, clicked)) return null;
    var tool = data(stack);
    var type = level.getBlockState(clicked).getBlock();
    var machines =
        RowWalk.machines(
            new RowWalk.World() {
              @Override
              public boolean loaded(BlockPos at) {
                return level.isLoaded(at);
              }

              @Override
              public boolean run(BlockPos at) {
                return LabelToolItem.run(level.getBlockState(at));
              }

              @Override
              public boolean connected(BlockPos from, Direction side) {
                return dev.izzy.factorycore.platform.network.NetworkedBlock.wired(
                        level.getBlockState(from), side)
                    && dev.izzy.factorycore.platform.network.NetworkedBlock.wired(
                        level.getBlockState(from.relative(side)), side.getOpposite());
              }

              @Override
              public boolean sameMachine(BlockPos at) {
                return level.getBlockState(at).getBlock() == type && machine(level, at);
              }
            },
            clicked,
            MAX_ROW,
            MAX_RUN);
    // Every machine is planned (and authorized) while the network is valid; relabels then commit
    // as one batch, and new ports come last, because each change makes the network rebuild.
    int labelled = 0, refused = 0, done = 0;
    var plans = new java.util.ArrayList<Plan>();
    for (var entry : machines.entrySet()) {
      if (done++ == MAX_ROW) break;
      var plan = plan(player, tool, entry.getKey(), entry.getValue());
      if (plan.refusal() != null) refused++;
      else plans.add(plan);
    }
    var relabels = new java.util.ArrayList<MachineInterfaceEntity.LabelChange>();
    for (var plan : plans)
      if (plan.port() != null)
        relabels.add(
            new MachineInterfaceEntity.LabelChange(
                plan.port(), Actors.of(player), plan.port().settings(), tool.label()));
    for (var outcome : MachineInterfaceEntity.applyLabels(relabels))
      if (outcome.equals("labeled")) labelled++;
      else refused++;
    for (var plan : plans) {
      if (plan.port() != null) continue;
      String outcome;
      try {
        outcome = apply(player, tool, plan);
      } catch (IllegalArgumentException | IllegalStateException failed) {
        outcome = "invalid";
      }
      if (outcome.equals("port_made")) labelled++;
      else refused++;
    }
    return new RowResult(labelled, refused, machines.size() > MAX_ROW);
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
