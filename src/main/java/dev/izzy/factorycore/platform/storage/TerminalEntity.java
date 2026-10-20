package dev.izzy.factorycore.platform.storage;

import dev.izzy.factorycore.core.network.*;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Loaded;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Access-only holder; stock, grants and menu credentials are never copied into chunk/item NBT. */
public final class TerminalEntity extends BlockEntity implements MenuProvider, TerminalAccess {
  private UUID opening;

  public TerminalEntity(BlockPos pos, BlockState state) {
    super(TerminalContent.ENTITY.get(), pos, state);
  }

  public ServerLevel serverLevel() {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread())
      throw new IllegalStateException("Terminal requires server thread");
    return server;
  }

  @Override
  public NodeRegistry.Position origin() {
    return Positions.of(serverLevel(), worldPosition);
  }

  public boolean current() {
    if (isRemoved() || !(level instanceof ServerLevel)) return false;
    var chunk = Loaded.chunk(serverLevel(), worldPosition);
    return chunk != null
        && chunk.getBlockEntity(worldPosition, LevelChunk.EntityCreationType.CHECK) == this;
  }

  @Override
  public boolean canUse(ServerPlayer player) {
    if (!player.isAlive()
        || !current()
        || player.serverLevel() != level
        || player.server.getPlayerList().getPlayer(player.getUUID()) != player
        || player.distanceToSqr(Vec3.atCenterOf(worldPosition)) > 64) return false;
    return WorldNetworks.get(player.server)
        .allows(Actors.of(player), origin(), NetworkPermissions.Permission.VIEW);
  }

  UUID sessionForOpen() {
    return opening;
  }

  @Override
  public Component getDisplayName() {
    return Component.translatable("block.factorycore.storage_terminal");
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    if (!(player instanceof ServerPlayer server) || !canUse(server)) return null;
    opening = UUID.randomUUID();
    return new TerminalMenu(id, inventory, this, opening);
  }

  @Override
  public void onLoad() {
    super.onLoad();
    WorldNetworks.changed(level, worldPosition);
  }

  @Override
  public void setRemoved() {
    WorldNetworks.changed(level, worldPosition);
    super.setRemoved();
  }
}
