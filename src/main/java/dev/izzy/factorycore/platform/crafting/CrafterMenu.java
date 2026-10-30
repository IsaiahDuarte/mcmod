package dev.izzy.factorycore.platform.crafting;

import dev.izzy.factorycore.core.crafting.CraftingJob;
import dev.izzy.factorycore.core.crafting.JobReason;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.resource.ResourceKind;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.session.DeviceStatus;
import dev.izzy.factorycore.platform.session.MenuSession;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Crafter screen (ergonomics Q2, ADR 0060): its nine pattern slots, speed upgrade, the machines
 * beside it and what it is doing. Anyone who can use the block may look; taking or inserting a
 * pattern needs EDIT where the Crafter is, checked on the server at every slot change.
 */
public final class CrafterMenu extends AbstractContainerMenu implements DeviceStatus.Holder {
  /** Panel-relative item positions on the 230-wide kit panel. */
  public static final int PATTERN_X = 35,
      PATTERN_Y = 30,
      INVENTORY_X = 35,
      INVENTORY_Y = 156,
      HOTBAR_Y = INVENTORY_Y + 58;

  private static final int POLL_TICKS = 10;
  public static final int PATTERN_COUNT = PatternHolderEntity.SLOTS;

  private final MenuSession session;
  private final CrafterEntity crafter;
  private long revision;
  private CrafterPackets.View view;
  private DeviceStatus.View deviceStatus;

  /** Client constructor. */
  public CrafterMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, MenuSession.read(buffer), null);
  }

  private CrafterMenu(int id, Inventory inventory, UUID nonce, CrafterEntity crafter) {
    super(CraftingContent.CRAFTER_MENU.get(), id);
    session = new MenuSession(this, inventory.player, nonce, crafter != null, POLL_TICKS);
    this.crafter = crafter;
    IItemHandler patterns =
        crafter != null
            ? crafter.patterns()
            : new ItemStackHandler(PATTERN_COUNT) {
              @Override
              public int getSlotLimit(int slot) {
                return 1;
              }
            };
    for (int slot = 0; slot < PATTERN_COUNT; slot++)
      addSlot(new PatternSlot(patterns, slot, PATTERN_X + 18 * slot, PATTERN_Y));
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 9; column++)
        addSlot(
            new Slot(
                inventory,
                9 + row * 9 + column,
                INVENTORY_X + 18 * column,
                INVENTORY_Y + 18 * row));
    for (int column = 0; column < 9; column++)
      addSlot(new Slot(inventory, column, INVENTORY_X + 18 * column, HOTBAR_Y));
    view = CrafterPackets.View.loading(id, nonce);
    deviceStatus = DeviceStatus.View.pending(id, nonce);
  }

  public static void open(ServerPlayer player, CrafterEntity crafter) {
    MenuSession.open(
        player,
        Component.translatable("block.factorycore.crafter"),
        (id, inventory, nonce) -> new CrafterMenu(id, inventory, nonce, crafter));
  }

  public CrafterPackets.View view() {
    return view;
  }

  public void accept(CrafterPackets.View packet) {
    if (!session.server()
        && session.owns(packet.menu(), packet.session())
        && packet.revision() >= view.revision()) view = packet;
  }

  @Override
  public DeviceStatus.View deviceStatus() {
    return deviceStatus;
  }

  @Override
  public void acceptDeviceStatus(DeviceStatus.View packet) {
    if (!session.server())
      deviceStatus = DeviceStatus.accept(containerId, session.id(), deviceStatus, packet);
  }

  /** May the viewer move patterns: EDIT on the server, the last view on the client. */
  public boolean editable() {
    if (!session.server()) return view.editable();
    return crafter != null
        && WorldNetworks.get(session.player().server)
            .allows(
                Actors.of(session.player()), crafter.origin(), NetworkPermissions.Permission.EDIT);
  }

  /** A pattern slot: takes and accepts one pattern, only while the viewer may edit. */
  private final class PatternSlot extends SlotItemHandler {
    PatternSlot(IItemHandler handler, int index, int x, int y) {
      super(handler, index, x, y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
      return (stack.is(CraftingContent.CRAFTING_PATTERN.get())
              || stack.is(CraftingContent.PROCESSING_PATTERN.get()))
          && editable()
          && super.mayPlace(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
      return editable() && super.mayPickup(player);
    }

    @Override
    public void setChanged() {
      super.setChanged();
      session.pollNow();
    }
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
    var slot = slots.get(index);
    if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
    var stack = slot.getItem();
    var original = stack.copy();
    boolean moved =
        index < PATTERN_COUNT
            ? moveItemStackTo(stack, PATTERN_COUNT, slots.size(), true)
            : moveItemStackTo(stack, 0, PATTERN_COUNT, false);
    if (!moved) return ItemStack.EMPTY;
    if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
    else slot.setChanged();
    return original;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (crafter == null || !session.due(() -> crafter.current())) return;
    var server = session.player().server;
    deviceStatus =
        DeviceStatus.publish(
            session.player(),
            deviceStatus,
            DeviceStatus.of(server, containerId, session.id(), crafter.origin(), "", ""));
    publish();
  }

  private void publish() {
    var jobs = DeviceSavedData.get(session.player().server).craftingJobs().all();
    var origin = crafter.origin();
    var held = patternIds();
    var machines = new ArrayList<CrafterPackets.Machine>();
    boolean working = false;
    JobReason waiting = null;
    for (var job : jobs) {
      var dispatch = job.dispatch();
      if (dispatch != null && dispatch.machine().equals(origin)) working = true;
      int step = job.current();
      if (step < 0 || !held.contains(job.steps().get(step).pattern())) continue;
      if (job.reason() == null || job.reason() == JobReason.PROCESSING) working = true;
      else if (waiting == null) waiting = job.reason();
    }
    for (var side : Direction.values()) {
      if (!crafter.machineSide(side)) continue;
      BlockPos at = crafter.getBlockPos().relative(side);
      var target = Positions.of(crafter.getLevel(), at);
      String block =
          BuiltInRegistries.BLOCK
              .getKey(crafter.getLevel().getBlockState(at).getBlock())
              .toString();
      var holder = holding(jobs, origin, target);
      if (holder == null) {
        machines.add(
            new CrafterPackets.Machine(
                side, block, CrafterPackets.MachineState.FREE, "", false, null));
        continue;
      }
      int step = holder.current();
      var output = step < 0 ? null : holder.steps().get(step).output();
      var reason = holder.reason() == JobReason.PROCESSING ? null : holder.reason();
      machines.add(
          new CrafterPackets.Machine(
              side,
              block,
              reason == null
                  ? CrafterPackets.MachineState.BUSY
                  : CrafterPackets.MachineState.WAITING,
              output == null ? "" : output.registryId(),
              output != null && output.kind() == ResourceKind.FLUID,
              reason));
    }
    var activity =
        crafter.patternCount() == 0
            ? CrafterPackets.Activity.NO_PATTERNS
            : working
                ? CrafterPackets.Activity.WORKING
                : waiting != null ? CrafterPackets.Activity.WAITING : CrafterPackets.Activity.IDLE;
    var next =
        new CrafterPackets.View(
            containerId,
            session.id(),
            revision,
            crafter.tier(),
            editable(),
            activity,
            activity == CrafterPackets.Activity.WAITING ? waiting : null,
            machines);
    if (!next.equals(view)) {
      next =
          new CrafterPackets.View(
              containerId,
              session.id(),
              ++revision,
              next.tier(),
              next.editable(),
              next.activity(),
              next.reason(),
              next.machines());
      view = next;
      PacketDistributor.sendToPlayer(session.player(), next);
    }
  }

  /** The job whose dispatch from this Crafter holds {@code target}, or null. */
  private static CraftingJob holding(
      Iterable<CraftingJob> jobs, NodeRegistry.Position origin, NodeRegistry.Position target) {
    for (var job : jobs) {
      var dispatch = job.dispatch();
      if (dispatch == null || !dispatch.machine().equals(origin)) continue;
      // A legacy dispatch without a proven target fences every side, as WorldCraftingJobs does.
      if (dispatch.target() == null || dispatch.target().equals(target)) return job;
    }
    return null;
  }

  private Set<UUID> patternIds() {
    var ids = new HashSet<UUID>();
    for (int slot = 0; slot < PATTERN_COUNT; slot++) {
      var stack = crafter.patterns().getStackInSlot(slot);
      var crafting = stack.get(CraftingContent.PATTERN.get());
      if (crafting != null) ids.add(crafting.id());
      var processing = stack.get(CraftingContent.PROCESSING.get());
      if (processing != null) ids.add(processing.id());
    }
    return ids;
  }

  @Override
  public boolean stillValid(Player player) {
    if (!session.stillValid(player)) return false;
    if (crafter == null) return true;
    return crafter.current() && player.distanceToSqr(crafter.getBlockPos().getCenter()) <= 64;
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    session.close();
  }
}
