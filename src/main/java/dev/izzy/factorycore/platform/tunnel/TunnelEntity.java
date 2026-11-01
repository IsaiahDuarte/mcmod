package dev.izzy.factorycore.platform.tunnel;

import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WiredMember;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Q37: one Tunnel end. An input forwards what is inserted at its front face to the blocks in front
 * of its outputs, first fit in turn starting after the last output used; outputs repeat its
 * redstone signal. Two ends are linked only while both are loaded, on one valid network and share a
 * frequency. A move that would re-enter a tunnel is refused, so loops cannot recurse.
 */
public final class TunnelEntity extends BlockEntity implements WiredMember {
  static final String FIELD = "FactoryCoreTunnel";

  /** Ticks between redstone re-checks, which cover network changes. */
  static final int SIGNAL_PERIOD = 10;

  private static boolean forwarding;

  private UUID frequency;
  private UUID owner;
  private int signal;
  private int next;

  public TunnelEntity(BlockPos position, BlockState state) {
    super(TunnelContent.ENTITY.get(), position, state);
  }

  public TunnelBlock.Role role() {
    return getBlockState().getValue(TunnelBlock.ROLE);
  }

  public UUID frequency() {
    return frequency;
  }

  /** The front: the face that works on the block it faces. */
  public Direction facing() {
    return getBlockState().getValue(BlockStateProperties.FACING);
  }

  /** The redstone signal this tunnel last read (input) or emits (output). */
  public int signal() {
    return signal;
  }

  void place(UUID owner) {
    this.owner = owner;
    setChanged();
  }

  private NodeRegistry.Position origin() {
    return Positions.of(level, worldPosition);
  }

  boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk = Loaded.chunk(server, worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  private NetworkTopology.Membership membership() {
    return WorldNetworks.get(level.getServer()).membership(origin());
  }

  /** Who may set roles: MANAGE on a valid network, else the placer. */
  boolean mayConfigure(ServerPlayer player) {
    var actor = Actors.of(player);
    var networks = WorldNetworks.get(level.getServer());
    if (networks.membership(origin()).status() == NetworkTopology.Status.VALID)
      return networks.allows(actor, origin(), NetworkPermissions.Permission.MANAGE);
    return actor.operator() || owner == null || actor.id().equals(owner);
  }

  void becomeInput() {
    if (role() == TunnelBlock.Role.INPUT && frequency != null) return;
    retune(TunnelBlock.Role.INPUT, UUID.randomUUID());
    readSignal();
  }

  void becomeOutput(UUID input) {
    retune(TunnelBlock.Role.OUTPUT, input);
    refreshOutput();
  }

  private void retune(TunnelBlock.Role role, UUID next) {
    var tunnels = WorldTunnels.get(level.getServer());
    tunnels.remove(frequency, this);
    frequency = next;
    signal = 0;
    tunnels.add(frequency, this);
    // Clients only: a neighbour update would make the cables around it rebuild the network.
    level.setBlock(
        worldPosition,
        getBlockState().setValue(TunnelBlock.ROLE, role),
        net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    level.invalidateCapabilities(worldPosition);
    notifyFront();
    setChanged();
  }

  /** True when {@code other} is a loaded tunnel on this one's frequency and network. */
  private boolean linked(TunnelEntity other) {
    if (other == this || !current() || !other.current() || frequency == null) return false;
    if (!frequency.equals(other.frequency)) return false;
    var own = membership();
    var theirs = other.membership();
    return own.status() == NetworkTopology.Status.VALID
        && theirs.status() == NetworkTopology.Status.VALID
        && own.scope().network().equals(theirs.scope().network());
  }

  /** This input's linked, loaded outputs, in a stable order. */
  public List<TunnelEntity> outputs() {
    if (role() != TunnelBlock.Role.INPUT || !(level instanceof ServerLevel server))
      return List.of();
    var found = new ArrayList<TunnelEntity>();
    for (var member : WorldTunnels.get(server.getServer()).members(frequency))
      if (member.role() == TunnelBlock.Role.OUTPUT && linked(member)) found.add(member);
    return found;
  }

  /** This output's linked, loaded input, or null. */
  public TunnelEntity input() {
    if (role() != TunnelBlock.Role.OUTPUT || !(level instanceof ServerLevel server)) return null;
    for (var member : WorldTunnels.get(server.getServer()).members(frequency))
      if (member.role() == TunnelBlock.Role.INPUT && linked(member)) return member;
    return null;
  }

  /** The handler of {@code capability} on the block this output faces, if its chunk is loaded. */
  private <T> T target(BlockCapability<T, Direction> capability) {
    var front = worldPosition.relative(facing());
    if (Loaded.chunk(level, front) == null || level.getBlockEntity(front) instanceof TunnelEntity)
      return null;
    return level.getCapability(capability, front, facing().getOpposite());
  }

  /**
   * Offers {@code amount} to each output in turn, starting after the last one used; {@code move}
   * returns what one output's target accepted. Returns the total accepted.
   */
  private <T> long forward(
      BlockCapability<T, Direction> capability,
      long amount,
      boolean simulate,
      BiFunction<T, Long, Long> move) {
    if (forwarding || amount <= 0) return 0;
    forwarding = true;
    try {
      var outputs = outputs();
      long moved = 0;
      for (int step = 0; step < outputs.size() && moved < amount; step++) {
        int index = Math.floorMod(next + step, outputs.size());
        var handler = outputs.get(index).target(capability);
        if (handler == null) continue;
        long accepted = move.apply(handler, amount - moved);
        if (accepted > 0) {
          moved += accepted;
          if (!simulate) next = index + 1;
        }
      }
      return moved;
    } finally {
      forwarding = false;
    }
  }

  /** The handler this tunnel offers on {@code face}: only an input's front face has one. */
  private boolean offers(Direction face) {
    return role() == TunnelBlock.Role.INPUT && (face == null || face == facing());
  }

  public IItemHandler items(Direction face) {
    return offers(face) ? new Items() : null;
  }

  public IFluidHandler fluids(Direction face) {
    return offers(face) ? new Fluids() : null;
  }

  public IEnergyStorage energy(Direction face) {
    return offers(face) ? new Energy() : null;
  }

  /** Reads the signal the input's front receives; a change updates every output. */
  void readSignal() {
    if (role() != TunnelBlock.Role.INPUT || !current()) return;
    int read = level.getSignal(worldPosition.relative(facing()), facing());
    if (read == signal) return;
    signal = read;
    setChanged();
    for (var output : outputs()) output.refreshOutput();
  }

  /** Emits the linked input's signal, or none. */
  void refreshOutput() {
    if (role() != TunnelBlock.Role.OUTPUT || !current()) return;
    var input = input();
    int emitted = input == null ? 0 : input.signal;
    if (emitted == signal) return;
    signal = emitted;
    setChanged();
    notifyFront();
  }

  /** Tells the block in front (and, for strong power, its neighbours other than this). */
  private void notifyFront() {
    var block = getBlockState().getBlock();
    var front = worldPosition.relative(facing());
    if (Loaded.chunk(level, front) == null) return;
    level.neighborChanged(front, block, worldPosition);
    level.updateNeighborsAtExceptFromFacing(front, block, facing().getOpposite());
  }

  void serverTick() {
    if (level.getGameTime() % SIGNAL_PERIOD != Math.floorMod(worldPosition.asLong(), SIGNAL_PERIOD))
      return;
    if (role() == TunnelBlock.Role.INPUT) readSignal();
    else refreshOutput();
  }

  void describe(ServerPlayer player) {
    var message =
        switch (role()) {
          case UNSET -> Component.translatable("message.factorycore.tunnel_unset");
          case INPUT ->
              Component.translatable("message.factorycore.tunnel_input_status", outputs().size());
          case OUTPUT ->
              Component.translatable(
                  input() == null
                      ? "message.factorycore.tunnel_output_unlinked"
                      : "message.factorycore.tunnel_output_status");
        };
    player.displayClientMessage(message, true);
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server) {
      WorldNetworks.changed(level, worldPosition);
      WorldTunnels.get(server.getServer()).add(frequency, this);
    }
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel server) {
      WorldNetworks.changed(level, worldPosition);
      WorldTunnels.get(server.getServer()).remove(frequency, this);
    }
    super.setRemoved();
  }

  @Override
  public Vertex wireVertex() {
    return new Vertex(null, "tunnel", NetworkTopology.Role.TERMINAL, current());
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    var data = new CompoundTag();
    data.putInt("Schema", 1);
    if (frequency != null) data.putUUID("Frequency", frequency);
    if (owner != null) data.putUUID("Owner", owner);
    data.putInt("Signal", signal);
    tag.put(FIELD, data);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    var data = tag.getCompound(FIELD);
    frequency = data.hasUUID("Frequency") ? data.getUUID("Frequency") : null;
    owner = data.hasUUID("Owner") ? data.getUUID("Owner") : null;
    signal = Math.clamp(data.getInt("Signal"), 0, 15);
  }

  private final class Items implements IItemHandler {
    @Override
    public int getSlots() {
      return 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
      return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
      if (stack.isEmpty()) return stack;
      var left = new ItemStack[] {stack.copy()};
      forward(
          Capabilities.ItemHandler.BLOCK,
          stack.getCount(),
          simulate,
          (handler, amount) -> {
            int before = left[0].getCount();
            for (int target = 0; target < handler.getSlots() && !left[0].isEmpty(); target++)
              left[0] = handler.insertItem(target, left[0], simulate);
            return (long) (before - left[0].getCount());
          });
      return left[0];
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
      return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
      return 64;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
      return true;
    }
  }

  private final class Fluids implements IFluidHandler {
    @Override
    public int getTanks() {
      return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
      return FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
      return Integer.MAX_VALUE;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
      return true;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
      if (resource.isEmpty()) return 0;
      return (int)
          forward(
              Capabilities.FluidHandler.BLOCK,
              resource.getAmount(),
              action.simulate(),
              (handler, amount) ->
                  (long) handler.fill(resource.copyWithAmount((int) (long) amount), action));
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
      return FluidStack.EMPTY;
    }

    @Override
    public FluidStack drain(int amount, FluidAction action) {
      return FluidStack.EMPTY;
    }
  }

  private final class Energy implements IEnergyStorage {
    @Override
    public int receiveEnergy(int amount, boolean simulate) {
      return (int)
          forward(
              Capabilities.EnergyStorage.BLOCK,
              amount,
              simulate,
              (handler, left) -> (long) handler.receiveEnergy((int) (long) left, simulate));
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
      return 0;
    }

    @Override
    public int getEnergyStored() {
      return 0;
    }

    @Override
    public int getMaxEnergyStored() {
      return Integer.MAX_VALUE;
    }

    @Override
    public boolean canExtract() {
      return false;
    }

    @Override
    public boolean canReceive() {
      return true;
    }
  }
}
