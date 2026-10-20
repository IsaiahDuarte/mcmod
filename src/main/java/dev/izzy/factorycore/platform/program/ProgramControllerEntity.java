package dev.izzy.factorycore.platform.program;

import dev.izzy.factorycore.core.network.NodeRegistry;
import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.core.program.ProgramIr;
import dev.izzy.factorycore.core.program.ProgramLog;
import dev.izzy.factorycore.core.wasm.WasmPreflight;
import dev.izzy.factorycore.core.wasm.WasmProgram;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Program Controller holder: an editable draft, at most one deployed program and its deployer, a
 * finite FE buffer and bounded logs. Deployment authority is the deployer's current grants checked
 * at every invocation; breaking the block keeps the draft and FE, never the deployment.
 */
public final class ProgramControllerEntity extends BlockEntity {
  public static final int MAX_DRAFT = FriendlyCompiler.MAX_SOURCE;
  public static final long CAPACITY = 10_000;
  public static final long INPUT_RATE = 1_000;
  public static final int MAX_LOGS = ProgramLog.MAX_LINES;

  /**
   * Schema 2 adds the optional deployed Wasm {@code Artifact}; schema 3 adds its committed {@code
   * GuestState}. Older schemas still load.
   */
  static final int SCHEMA = 3;

  public enum State {
    STOPPED,
    RUNNING,
    PAUSED,
    TRAPPED
  }

  private String draft = "";
  private String deployed;
  private UUID deployer, deployment;
  private State state = State.STOPPED;
  private String message = "";
  private long energy;
  private long inputTick = Long.MIN_VALUE, inputThisTick;
  private ProgramIr.Program program;
  private byte[] artifact;
  private WasmProgram wasm;
  private byte[] guestState;
  private CompoundTag unsupported;
  private final ProgramLog logs = new ProgramLog();

  private final IEnergyStorage storage =
      new IEnergyStorage() {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
          if (amount <= 0 || unsupported != null || level == null || level.isClientSide()) return 0;
          long tick = level.getGameTime();
          long used = tick == inputTick ? inputThisTick : 0;
          long accepted = Math.min(amount, Math.min(CAPACITY - energy, INPUT_RATE - used));
          if (!simulate && accepted > 0) {
            if (tick != inputTick) {
              inputTick = tick;
              inputThisTick = 0;
            }
            inputThisTick += accepted;
            energy += accepted;
            setChanged();
          }
          return (int) Math.max(0, accepted);
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
          return 0;
        }

        @Override
        public int getEnergyStored() {
          return (int) energy;
        }

        @Override
        public int getMaxEnergyStored() {
          return (int) CAPACITY;
        }

        @Override
        public boolean canExtract() {
          return false;
        }

        @Override
        public boolean canReceive() {
          return unsupported == null;
        }
      };

  public ProgramControllerEntity(BlockPos position, BlockState state) {
    super(ProgramContent.ENTITY.get(), position, state);
  }

  public IEnergyStorage energyStorage() {
    return storage;
  }

  public long energy() {
    return energy;
  }

  /** Spends {@code amount} FE if available; returns false and spends nothing otherwise. */
  public boolean spend(long amount) {
    if (amount < 0 || amount > energy) return false;
    energy -= amount;
    setChanged();
    return true;
  }

  public String draft() {
    return draft;
  }

  public void draft(String source) {
    if (source.length() > MAX_DRAFT) throw new IllegalArgumentException("Draft too long");
    draft = Objects.requireNonNull(source);
    setChanged();
  }

  public String deployedSource() {
    return deployed;
  }

  public UUID deployer() {
    return deployer;
  }

  public UUID deployment() {
    return deployment;
  }

  public State state() {
    return state;
  }

  public String message() {
    return message;
  }

  public ProgramIr.Program program() {
    return program;
  }

  /** The live Wasm instance of a Wasm deployment, or null (friendly or none). */
  public WasmProgram wasm() {
    return wasm;
  }

  public boolean deployed() {
    return deployed != null || artifact != null;
  }

  public boolean wasmDeployment() {
    return artifact != null;
  }

  /**
   * Replaces a failed or missing instance with a fresh one from the persisted artifact; no
   * suspended guest state survives. Returns the failure message, or empty on success.
   */
  public String freshWasm() {
    if (artifact == null) return "No Wasm deployment";
    try {
      wasm = new WasmProgram(artifact, System::nanoTime, guestState);
      return "";
    } catch (RuntimeException failure) {
      wasm = null;
      return failure.getMessage() == null
          ? failure.getClass().getSimpleName()
          : failure.getMessage();
    }
  }

  /** Committed guest state of the current Wasm deployment, or null. */
  public byte[] guestState() {
    return guestState == null ? null : guestState.clone();
  }

  /** Records a callback's committed guest state; trusted runtime call only. */
  public void guestState(byte[] state) {
    guestState = state == null ? null : state.clone();
    setChanged();
  }

  /** Replaces the deployment with an initialized Wasm guest; trusted runtime call only. */
  public void deployWasm(byte[] bytes, WasmProgram instance, UUID by) {
    deployed = null;
    program = null;
    artifact = bytes.clone();
    wasm = instance;
    guestState = null;
    deployer = by;
    deployment = UUID.randomUUID();
    state = State.RUNNING;
    message = "";
    logs.clear();
    setChanged();
  }

  public String diagnostic() {
    return unsupported == null ? "" : "Unsupported program controller data; retained unchanged";
  }

  public List<String> logs() {
    return logs.lines();
  }

  /** Appends a bounded runtime line; an immediate repeat is counted rather than appended. */
  public void log(String line) {
    logs.add(line);
  }

  /** Appends one invocation's guest log lines under the per-invocation limit. */
  public int logInvocation(String rule, List<String> lines) {
    return logs.addInvocation(rule, lines);
  }

  /** Replaces the deployment with an already compiled program; trusted runtime call only. */
  public void deploy(String source, ProgramIr.Program compiled, UUID by) {
    deployed = source;
    program = compiled;
    artifact = null;
    wasm = null;
    guestState = null;
    deployer = by;
    deployment = UUID.randomUUID();
    state = State.RUNNING;
    message = "";
    logs.clear();
    setChanged();
  }

  /** Trusted runtime transition; authorization is the caller's responsibility. */
  public void state(State next, String why) {
    state = next;
    message = why == null ? "" : why;
    setChanged();
  }

  /** Trusted runtime removal of the deployment; the draft and FE remain. */
  public void undeploy() {
    deployed = null;
    program = null;
    artifact = null;
    wasm = null;
    guestState = null;
    deployer = null;
    deployment = null;
    state = State.STOPPED;
    message = "";
    setChanged();
  }

  public NodeRegistry.Position origin() {
    return Positions.of(level, worldPosition);
  }

  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel server)) return false;
    var chunk = Loaded.chunk(server, worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel server) WorldPrograms.get(server.getServer()).loaded(this);
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel server && WorldPrograms.exists(server.getServer()))
      WorldPrograms.get(server.getServer()).removed(this);
    WorldNetworks.changed(level, worldPosition);
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    if (level instanceof ServerLevel server && WorldPrograms.exists(server.getServer()))
      WorldPrograms.get(server.getServer()).removed(this);
    super.onChunkUnloaded();
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (unsupported != null) {
      for (var key : unsupported.getAllKeys()) tag.put(key, unsupported.get(key).copy());
      return;
    }
    tag.putInt("Schema", SCHEMA);
    tag.putString("Draft", draft);
    tag.putLong("Energy", energy);
    tag.putString("State", state.name());
    tag.putString("Message", message);
    if (deployed != null) tag.putString("Deployed", deployed);
    if (artifact != null) tag.putByteArray("Artifact", artifact);
    if (artifact != null && guestState != null) tag.putByteArray("GuestState", guestState);
    if (deployed()) {
      tag.putUUID("Deployer", deployer);
      tag.putUUID("Deployment", deployment);
    }
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    unsupported = null;
    if (!tag.contains("Schema")) return;
    try {
      int schema = tag.getInt("Schema");
      if (schema < 1 || schema > SCHEMA) throw new IllegalArgumentException("Schema");
      if (schema < 2 && tag.contains("Artifact")) throw new IllegalArgumentException("Artifact");
      if ((schema < 3 || !tag.contains("Artifact")) && tag.contains("GuestState"))
        throw new IllegalArgumentException("GuestState");
      var savedDraft = tag.getString("Draft");
      long savedEnergy = tag.getLong("Energy");
      var savedState = State.valueOf(tag.getString("State"));
      if (savedDraft.length() > MAX_DRAFT || savedEnergy < 0 || savedEnergy > CAPACITY)
        throw new IllegalArgumentException("Range");
      draft = savedDraft;
      energy = savedEnergy;
      message = tag.getString("Message");
      artifact = null;
      wasm = null;
      guestState = null;
      if (tag.contains("Artifact")) {
        var bytes = tag.getByteArray("Artifact");
        if (bytes.length > WasmPreflight.MAX_ARTIFACT)
          throw new IllegalArgumentException("Artifact size");
        deployed = null;
        program = null;
        artifact = bytes;
        deployer = tag.getUUID("Deployer");
        deployment = tag.getUUID("Deployment");
        if (tag.contains("GuestState")) {
          var state = tag.getByteArray("GuestState");
          if (state.length > WasmProgram.MAX_STATE)
            throw new IllegalArgumentException("GuestState size");
          guestState = state;
        }
        var failure = freshWasm();
        state = failure.isEmpty() ? savedState : State.TRAPPED;
        if (!failure.isEmpty()) message = "Deployed Wasm no longer loads: " + failure;
      } else if (tag.contains("Deployed")) {
        deployed = tag.getString("Deployed");
        deployer = tag.getUUID("Deployer");
        deployment = tag.getUUID("Deployment");
        var compiled = FriendlyCompiler.compile(deployed);
        // A deployment that no longer compiles is kept but cannot run.
        program = compiled.program();
        state = compiled.ok() ? savedState : State.TRAPPED;
        if (!compiled.ok()) message = "Deployed program no longer compiles";
      } else {
        deployed = null;
        deployer = null;
        deployment = null;
        program = null;
        state = State.STOPPED;
      }
    } catch (IllegalArgumentException invalid) {
      unsupported = tag.copy();
      for (var key : List.of("id", "x", "y", "z", "components")) unsupported.remove(key);
    }
  }

  @Override
  protected void applyImplicitComponents(DataComponentInput input) {
    super.applyImplicitComponents(input);
    var saved = input.get(ProgramContent.DRAFT.get());
    if (saved != null) draft = saved;
    var fe = input.get(ProgramContent.ENERGY.get());
    if (fe != null) energy = fe;
  }

  @Override
  protected void collectImplicitComponents(DataComponentMap.Builder components) {
    super.collectImplicitComponents(components);
    if (unsupported != null) return;
    if (!draft.isEmpty()) components.set(ProgramContent.DRAFT.get(), draft);
    if (energy > 0) components.set(ProgramContent.ENERGY.get(), energy);
  }
}
