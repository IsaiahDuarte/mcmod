package dev.izzy.factorycore.platform.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Q30: the block a faced cable is drawn as. Saved as the block's id (schema 1, field {@code
 * FactoryCoreFacade}); an unknown or no-longer-allowed block shows nothing and drops no facade, so
 * a removed mod never breaks the cable or its links. It holds no resources.
 */
public final class FacadeEntity extends BlockEntity {
  static final String FIELD = "FactoryCoreFacade";

  private Block shown;

  public FacadeEntity(BlockPos position, BlockState state) {
    super(NetworkContent.FACADE_ENTITY.get(), position, state);
  }

  /** The block shown, or null. */
  public Block shown() {
    return shown;
  }

  void show(Block block) {
    shown = FacadeItem.allowed(block) ? block : null;
    setChanged();
    if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (shown == null) return;
    var data = new CompoundTag();
    data.putInt("Schema", 1);
    data.putString("Block", BuiltInRegistries.BLOCK.getKey(shown).toString());
    tag.put(FIELD, data);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.loadAdditional(tag, registries);
    shown = null;
    var data = tag.getCompound(FIELD);
    if (data.getInt("Schema") != 1) return;
    var id = ResourceLocation.tryParse(data.getString("Block"));
    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return;
    var block = BuiltInRegistries.BLOCK.get(id);
    if (FacadeItem.allowed(block)) shown = block;
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = new CompoundTag();
    saveAdditional(tag, registries);
    return tag;
  }

  @Override
  public Packet<ClientGamePacketListener> getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }
}
