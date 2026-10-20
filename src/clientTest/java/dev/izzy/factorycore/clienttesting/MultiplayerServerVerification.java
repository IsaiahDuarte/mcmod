package dev.izzy.factorycore.clienttesting;

import static dev.izzy.factorycore.clienttesting.MultiplayerSync.*;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.izzy.factorycore.core.network.NetworkPermissions.Permission;
import dev.izzy.factorycore.core.network.NetworkTopology;
import dev.izzy.factorycore.core.resource.ResourceKey;
import dev.izzy.factorycore.core.storage.CellTier;
import dev.izzy.factorycore.core.storage.DeviceRegistry;
import dev.izzy.factorycore.platform.Actors;
import dev.izzy.factorycore.platform.Positions;
import dev.izzy.factorycore.platform.network.NetworkContent;
import dev.izzy.factorycore.platform.network.NetworkNodeEntity;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.resource.PlatformResourceCodec;
import dev.izzy.factorycore.platform.storage.CellHandleData;
import dev.izzy.factorycore.platform.storage.DeviceSavedData;
import dev.izzy.factorycore.platform.storage.StorageContent;
import dev.izzy.factorycore.platform.storage.StorageDriveEntity;
import dev.izzy.factorycore.platform.storage.TerminalContent;
import dev.izzy.factorycore.platform.storage.TerminalMenu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Dedicated-server conductor and authority-side verifier for the multiplayer check. It builds an
 * owner's network, publishes each step, waits for both real clients to acknowledge it and then
 * checks canonical grants, policy, open menus and exact stock/inventory counts on the server.
 */
final class MultiplayerServerVerification {
  private static final int STEP_DEADLINE = 1800;
  private final MultiplayerSync sync =
      new MultiplayerSync(Path.of(System.getProperty("factorycore.multiplayer.dir")));
  private final Path report = sync.directory().resolve("server/result.txt");
  private final StringBuilder evidence = new StringBuilder();
  private int step, entered, policy, ownerIron, strangerIron, joinedAt = -1;
  private boolean finished;
  private DeviceRegistry.Handle cell;
  private ResourceKey iron;
  private UUID network;
  private int[] bothTakeTicks = {-1, -1};

  void register() {
    NeoForge.EVENT_BUS.addListener(this::started);
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::joined);
    NeoForge.EVENT_BUS.addListener(this::left);
  }

  private void started(ServerStartedEvent event) {
    var server = event.getServer();
    var level = server.overworld();
    level.setChunkForced(0, 0, true);
    level.setDayTime(6000);
    server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
    server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
    server.setDifficulty(Difficulty.PEACEFUL, true);
    for (int x = -3; x < 8; x++)
      for (int z = -2; z < 8; z++)
        level.setBlock(new BlockPos(x, 64, z), Blocks.STONE.defaultBlockState(), 3);
    level.setBlock(ROOT, NetworkContent.CONTROLLER.get().defaultBlockState(), 3);
    ((NetworkNodeEntity) level.getBlockEntity(ROOT)).commission(OWNER_ID);
    level.setBlock(DRIVE, StorageContent.DRIVE.get().defaultBlockState(), 3);
    var drive = (StorageDriveEntity) level.getBlockEntity(DRIVE);
    drive.installCell(new ItemStack(StorageContent.cell(CellTier.STARTER_ITEM)));
    cell = CellHandleData.read(drive.cell(0));
    level.setBlock(TERMINAL, TerminalContent.BLOCK.get().defaultBlockState(), 3);
    level.setBlock(
        GATEWAY,
        NetworkContent.GATEWAY
            .get()
            .defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.WEST),
        3);
    ((NetworkNodeEntity) level.getBlockEntity(GATEWAY)).commission(null);
    level.setBlock(BRANCH, NetworkContent.CABLE.get().defaultBlockState(), 3);
    iron =
        new PlatformResourceCodec(level.registryAccess()).itemKey(new ItemStack(Items.IRON_INGOT));
    network = ((NetworkNodeEntity) level.getBlockEntity(ROOT)).handle().id();
    sync.publish(0);
    try {
      Files.createDirectories(report.getParent());
      // Clients wait for this marker before connecting.
      Files.writeString(sync.directory().resolve("server.ready"), "ready\n");
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
    System.out.println("FACTORYCORE_MULTIPLAYER_SERVER_READY port=" + server.getPort());
  }

  private void joined(PlayerEvent.PlayerLoggedInEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    player.getInventory().clearContent();
    player.setGameMode(GameType.SURVIVAL);
    boolean owner = player.getUUID().equals(OWNER_ID);
    // Both stand on the platform within reach of the terminal and gateway, facing them.
    player.teleportTo(player.serverLevel(), owner ? 4.5 : -1.5, 65, 4.5, owner ? 124 : -124, 20);
    evidence
        .append("joined ")
        .append(player.getGameProfile().getName())
        .append(" uuid=")
        .append(player.getUUID())
        .append(" operator=")
        .append(player.hasPermissions(Actors.OPERATOR_LEVEL))
        .append(" address=")
        .append(player.connection.getRemoteAddress())
        .append('\n');
  }

  private void left(PlayerEvent.PlayerLoggedOutEvent event) {
    if (!finished && step < FINISH)
      fail(
          event.getEntity().getServer(),
          "Unexpected logout of " + event.getEntity().getName().getString() + " at step " + step);
  }

  private void tick(ServerTickEvent.Post event) {
    var server = event.getServer();
    if (finished || cell == null) return;
    try {
      int now = server.getTickCount();
      if (step > 0 && now - entered > STEP_DEADLINE)
        throw new IllegalStateException(
            "Step "
                + step
                + " deadline; acknowledgements owner="
                + sync.acknowledged("owner")
                + " stranger="
                + sync.acknowledged("stranger"));
      var players = server.getPlayerList();
      var owner = players.getPlayer(OWNER_ID);
      var stranger = players.getPlayer(STRANGER_ID);
      if (step == 0) {
        if (owner == null || stranger == null || !valid(server, TERMINAL) || !valid(server, BRANCH))
          return;
        if (joinedAt < 0) {
          joinedAt = now;
          require(
              !owner.hasPermissions(Actors.OPERATOR_LEVEL)
                  && !stranger.hasPermissions(Actors.OPERATOR_LEVEL),
              "No player is an operator");
          require(
              DeviceSavedData.get(server).registry().insert(cell, iron, SEEDED_IRON).amount()
                  == SEEDED_IRON,
              "Seed stock");
          policy = policyMask(server);
        }
        if (now - joinedAt < 60) return;
        advance(server);
        return;
      }
      if (step == BOTH_TAKE) observeConcurrent(server, owner, stranger);
      if (step == FINISH) {
        if (owner == null
            && stranger == null
            && sync.acknowledged("owner") == FINISH
            && sync.acknowledged("stranger") == FINISH) pass(server);
        return;
      }
      if (sync.acknowledged("owner") < step || sync.acknowledged("stranger") < step) return;
      require(owner != null && stranger != null, "Both players remain connected");
      verify(server, owner, stranger);
      advance(server);
    } catch (RuntimeException error) {
      fail(server, error.toString());
    }
  }

  private void advance(MinecraftServer server) {
    step++;
    entered = server.getTickCount();
    if (step == OPERATOR_GRANT) {
      // The operator path: the server console (permission level 4) grants View again.
      int result;
      try {
        result =
            server
                .getCommands()
                .getDispatcher()
                .execute(
                    "factorycore access grant " + TERMINAL_ARGS + " " + STRANGER + " view",
                    server.createCommandSourceStack());
      } catch (CommandSyntaxException error) {
        throw new IllegalStateException(error);
      }
      require(result == 1, "Operator console grant succeeds");
    }
    sync.publish(step);
    System.out.println("FACTORYCORE_MULTIPLAYER_STEP " + step);
  }

  /** Server-side truth after both clients acknowledged {@link #step}. */
  private void verify(MinecraftServer server, ServerPlayer owner, ServerPlayer stranger) {
    long stock = DeviceSavedData.get(server).registry().stock(cell, iron).total();
    int ownerNow = owner.getInventory().countItem(Items.IRON_INGOT);
    int strangerNow = stranger.getInventory().countItem(Items.IRON_INGOT);
    var grants = grants(server);
    switch (step) {
      case STRANGER_DENIED -> {
        require(grants.isEmpty(), "A refused self-grant changes nothing: " + grants);
        require(!(stranger.containerMenu instanceof TerminalMenu), "No terminal menu for stranger");
      }
      case OWNER_POLICY ->
          require(policyMask(server) == (policy ^ 1), "Owner toggled exactly the first bit");
      case STRANGER_POLICY ->
          require(policyMask(server) == (policy ^ 1), "Stranger's forged click changed nothing");
      case GRANT_VIEW, VIEW_ONLY, OPERATOR_GRANT ->
          require(grants.equals(EnumSet.of(Permission.VIEW)), "Exactly View: " + grants);
      case GRANT_MOVE ->
          require(
              grants.equals(EnumSet.of(Permission.VIEW, Permission.DEPOSIT, Permission.WITHDRAW)),
              "View, deposit and withdraw: " + grants);
      case BOTH_OPEN ->
          require(
              owner.containerMenu instanceof TerminalMenu
                  && stranger.containerMenu instanceof TerminalMenu
                  && ((TerminalMenu) owner.containerMenu).position().equals(TERMINAL)
                  && ((TerminalMenu) stranger.containerMenu).position().equals(TERMINAL),
              "Both players hold open sessions on the same terminal");
      case REVOKE -> {
        require(grants.isEmpty(), "Revocation removed every grant: " + grants);
        require(!(stranger.containerMenu instanceof TerminalMenu), "Revocation closed the session");
        require(owner.containerMenu instanceof TerminalMenu, "The owner's session stays open");
      }
      default -> {}
    }
    int[] expected =
        switch (step) {
          case STRANGER_TAKES -> new int[] {63, 0, 1};
          case OWNER_TAKES -> new int[] {62, 1, 1};
          case STRANGER_DEPOSITS -> new int[] {63, 1, 0};
          case BOTH_TAKE, REVOKE, OPERATOR_GRANT -> new int[] {61, 2, 1};
          default -> new int[] {64, 0, 0};
        };
    require(
        stock == expected[0] && ownerNow == expected[1] && strangerNow == expected[2],
        "Step "
            + step
            + " counts stock/owner/stranger="
            + stock
            + "/"
            + ownerNow
            + "/"
            + strangerNow);
    require(stock + ownerNow + strangerNow == SEEDED_IRON, "Exact conservation");
    var level = server.overworld();
    require(
        level.getEntitiesOfClass(ItemEntity.class, new AABB(-16, 0, -16, 24, 128, 24)).isEmpty(),
        "No dropped items");
    ownerIron = ownerNow;
    strangerIron = strangerNow;
    evidence
        .append("step ")
        .append(step)
        .append(" verified: stock=")
        .append(stock)
        .append(" owner=")
        .append(ownerNow)
        .append(" stranger=")
        .append(strangerNow)
        .append(" grants=")
        .append(grants)
        .append(" policyMask=")
        .append(Integer.toHexString(policyMask(server)))
        .append('\n');
  }

  /** Records the server tick at which each concurrent withdrawal landed. */
  private void observeConcurrent(
      MinecraftServer server, ServerPlayer owner, ServerPlayer stranger) {
    if (owner != null
        && bothTakeTicks[0] < 0
        && owner.getInventory().countItem(Items.IRON_INGOT) > ownerIron)
      bothTakeTicks[0] = server.getTickCount() - entered;
    if (stranger != null
        && bothTakeTicks[1] < 0
        && stranger.getInventory().countItem(Items.IRON_INGOT) > strangerIron)
      bothTakeTicks[1] = server.getTickCount() - entered;
    if (bothTakeTicks[0] >= 0 && bothTakeTicks[1] >= 0 && evidence.indexOf("concurrent") < 0)
      evidence
          .append("concurrent Take 1: owner landed at +")
          .append(bothTakeTicks[0])
          .append(" ticks, stranger at +")
          .append(bothTakeTicks[1])
          .append(" ticks after the step was published\n");
  }

  private Set<Permission> grants(MinecraftServer server) {
    var state = DeviceSavedData.get(server).network(network).snapshot();
    require(state.owner().equals(OWNER_ID), "Owner unchanged");
    var set = state.grants().getOrDefault(STRANGER_ID, Set.of());
    return set.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(set);
  }

  private int policyMask(MinecraftServer server) {
    var gateway = (NetworkNodeEntity) server.overworld().getBlockEntity(GATEWAY);
    return DeviceSavedData.get(server).nodes().definition(gateway.handle()).policy().mask();
  }

  private static boolean valid(MinecraftServer server, BlockPos position) {
    ServerLevel level = server.overworld();
    return WorldNetworks.get(server).membership(Positions.of(level, position)).status()
        == NetworkTopology.Status.VALID;
  }

  private void pass(MinecraftServer server) {
    write(
        "PASS\n"
            + evidence
            + "Both clients disconnected only after the final step; no unexpected logout.\n");
    System.out.println("FACTORYCORE_MULTIPLAYER_SERVER_PASS");
    finished = true;
    server.halt(false);
  }

  private void fail(MinecraftServer server, String message) {
    if (finished) return;
    finished = true;
    write("FAIL\n" + message + "\n" + evidence);
    System.out.println("FACTORYCORE_MULTIPLAYER_SERVER_FAIL " + message);
    server.halt(false);
  }

  private void write(String text) {
    try {
      Files.createDirectories(report.getParent());
      Files.writeString(report, text);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
