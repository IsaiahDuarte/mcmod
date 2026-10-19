package dev.izzy.factorycore.platform.program;

import dev.izzy.factorycore.core.network.NetworkAuthority;
import dev.izzy.factorycore.core.network.NetworkPermissions;
import dev.izzy.factorycore.core.program.FriendlyCompiler;
import dev.izzy.factorycore.platform.network.WorldNetworks;
import dev.izzy.factorycore.platform.storage.WorldPrograms;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One editor session. Viewing needs VIEW; saving the draft needs EDIT; deploy, pause, resume and
 * stop need DEPLOY (checked by {@link WorldPrograms}). The client never names a controller.
 */
public final class ProgramMenu extends AbstractContainerMenu {
  private final Player viewer;
  private final ProgramControllerEntity controller;
  private final UUID session;
  private final BlockPos position;
  private final StringBuilder upload = new StringBuilder();
  private final java.io.ByteArrayOutputStream binary = new java.io.ByteArrayOutputStream();
  private int binaryExpected = -1;
  private int expected = -1;
  private List<String> diagnostics = List.of();
  private long revision = 1;
  private int lastAction = Integer.MIN_VALUE, lastPoll = Integer.MIN_VALUE;
  private boolean draftSent;
  private int lastAuthor = Integer.MIN_VALUE;
  private ProgramPackets.AuthorView authorView;
  private ProgramPackets.View view;
  private String draft = "";
  private boolean draftReceived;

  public ProgramMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(id, inventory, null, buffer.readBlockPos(), buffer.readUUID());
  }

  ProgramMenu(int id, Inventory inventory, ProgramControllerEntity controller, UUID session) {
    this(id, inventory, controller, controller.getBlockPos(), session);
  }

  private ProgramMenu(
      int id,
      Inventory inventory,
      ProgramControllerEntity controller,
      BlockPos position,
      UUID session) {
    super(ProgramContent.MENU.get(), id);
    viewer = inventory.player;
    this.controller = controller;
    this.position = position;
    this.session = session;
    view =
        new ProgramPackets.View(id, session, 0, "stopped", "", 0, "", false, List.of(), List.of());
  }

  public UUID session() {
    return session;
  }

  public BlockPos position() {
    return position;
  }

  public ProgramPackets.View view() {
    return view;
  }

  /** Client copy of the draft delivered when the editor opened. */
  public String draft() {
    return draft;
  }

  public void accept(ProgramPackets.View packet) {
    if (controller == null
        && packet.menu() == containerId
        && packet.session().equals(session)
        && packet.revision() >= view.revision()) view = packet;
  }

  public void accept(ProgramPackets.Draft packet) {
    if (controller == null && packet.menu() == containerId && packet.session().equals(session)) {
      draft = packet.text();
      draftReceived = true;
    }
  }

  public boolean draftReceived() {
    return draftReceived;
  }

  public ProgramPackets.AuthorView authorView() {
    return authorView;
  }

  public void accept(ProgramPackets.AuthorView packet) {
    if (controller == null && packet.menu() == containerId && packet.session().equals(session))
      authorView = packet;
  }

  /** Current-scope authoring snapshot; VIEW is rechecked, and no runtime commands are published. */
  public void author(ServerPlayer sender, ProgramPackets.AuthorRequest request) {
    if (sender != viewer
        || request.menu() != containerId
        || !request.session().equals(session)
        || !authorized()) return;
    int tick = sender.server.getTickCount();
    if (lastAuthor != Integer.MIN_VALUE && tick - lastAuthor < 5) return;
    lastAuthor = tick;
    var networks = WorldNetworks.get(sender.server);
    var member = networks.membership(controller.origin());
    var scope = member.scope().network() + "/" + member.scope().segment();
    var labels = networks.labels(actor(), controller.origin(), request.after(), 16);
    var preview =
        request.preview()
            ? WorldPrograms.get(sender.server).preview(actor(), controller, request.rule())
            : new dev.izzy.factorycore.core.program.ProgramPreview.Result(0, 0, List.of());
    authorView =
        new ProgramPackets.AuthorView(
            containerId,
            session,
            scope,
            labels.labels(),
            labels.more(),
            preview.rule(),
            preview.total(),
            preview.lines());
    PacketDistributor.sendToPlayer(sender, authorView);
  }

  private ServerPlayer player() {
    return (ServerPlayer) viewer;
  }

  private NetworkPermissions.Actor actor() {
    return new NetworkPermissions.Actor(viewer.getUUID(), player().hasPermissions(2));
  }

  /** Opens an editor session for an authorized viewer; false (and nothing opens) otherwise. */
  public static boolean open(ServerPlayer player, ProgramControllerEntity controller) {
    if (!canView(player, controller)) return false;
    var session = UUID.randomUUID();
    player.openMenu(
        new net.minecraft.world.SimpleMenuProvider(
            (id, inventory, who) -> new ProgramMenu(id, inventory, controller, session),
            net.minecraft.network.chat.Component.translatable(
                "block.factorycore.program_controller")),
        buffer -> {
          buffer.writeBlockPos(controller.getBlockPos());
          buffer.writeUUID(session);
        });
    return player.containerMenu instanceof ProgramMenu;
  }

  static boolean canView(ServerPlayer player, ProgramControllerEntity controller) {
    return player.isAlive()
        && controller.current()
        && player.level() == controller.getLevel()
        && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(controller.getBlockPos()))
            <= 64
        && WorldNetworks.get(player.server)
                .authorize(
                    new NetworkPermissions.Actor(player.getUUID(), player.hasPermissions(2)),
                    controller.origin(),
                    NetworkPermissions.Permission.VIEW)
            == NetworkAuthority.Decision.ALLOWED;
  }

  private boolean authorized() {
    return controller != null && player().containerMenu == this && canView(player(), controller);
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer && (controller == null || canView(player(), controller));
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public void broadcastChanges() {
    super.broadcastChanges();
    if (controller == null || player().containerMenu != this) return;
    if (!authorized()) {
      player().closeContainer();
      return;
    }
    if (!draftSent) {
      draftSent = true;
      PacketDistributor.sendToPlayer(
          player(), new ProgramPackets.Draft(containerId, session, controller.draft()));
    }
    int tick = player().server.getTickCount();
    if (lastPoll != Integer.MIN_VALUE && tick - lastPoll < 5) return;
    lastPoll = tick;
    publish();
  }

  private void diagnose(List<FriendlyCompiler.Diagnostic> found) {
    var lines = new ArrayList<String>();
    for (var diagnostic : found) {
      if (lines.size() == ProgramPackets.LINES) break;
      var line = diagnostic.line() + ":" + diagnostic.column() + " " + diagnostic.message();
      lines.add(line.length() > 192 ? line.substring(0, 192) : line);
    }
    diagnostics = List.copyOf(lines);
    revision++;
  }

  public void command(ServerPlayer sender, ProgramPackets.Command command) {
    if (sender != viewer
        || command.menu() != containerId
        || !command.session().equals(session)
        || !authorized()) return;
    if (command.action() == ProgramPackets.CHUNK) {
      if (command.index() == 0) {
        upload.setLength(0);
        expected = command.count();
      } else if (expected != command.count()) return;
      upload.append(command.text());
      if (upload.length() > ProgramControllerEntity.MAX_DRAFT) {
        upload.setLength(0);
        expected = -1;
        diagnose(
            List.of(
                new FriendlyCompiler.Diagnostic(1, 1, "Program is longer than 16,384 characters")));
        publish();
        return;
      }
      if (command.index() == command.count() - 1) {
        expected = -1;
        if (WorldNetworks.get(sender.server)
                .authorize(actor(), controller.origin(), NetworkPermissions.Permission.EDIT)
            != NetworkAuthority.Decision.ALLOWED) {
          diagnose(
              List.of(
                  new FriendlyCompiler.Diagnostic(
                      1, 1, "You need Edit permission to save the draft")));
        } else {
          controller.draft(upload.toString());
          diagnose(FriendlyCompiler.compile(controller.draft()).diagnostics());
        }
        upload.setLength(0);
        publish();
      }
      return;
    }
    int tick = sender.server.getTickCount();
    if (lastAction != Integer.MIN_VALUE && tick - lastAction < 2) return;
    lastAction = tick;
    var programs = WorldPrograms.get(sender.server);
    switch (command.action()) {
      case ProgramPackets.CHECK ->
          diagnose(FriendlyCompiler.compile(controller.draft()).diagnostics());
      case ProgramPackets.DEPLOY -> diagnose(programs.deploy(actor(), controller));
      case ProgramPackets.PAUSE ->
          programs.control(actor(), controller, ProgramControllerEntity.State.PAUSED);
      case ProgramPackets.RESUME ->
          programs.control(actor(), controller, ProgramControllerEntity.State.RUNNING);
      case ProgramPackets.STOP ->
          programs.control(actor(), controller, ProgramControllerEntity.State.STOPPED);
      default -> {
        return;
      }
    }
    revision++;
    publish();
  }

  /** Assembles a chunked Wasm artifact; the final chunk deploys it under the viewer's grants. */
  public void artifact(ServerPlayer sender, ProgramPackets.Artifact chunk) {
    if (sender != viewer
        || chunk.menu() != containerId
        || !chunk.session().equals(session)
        || !authorized()) return;
    if (chunk.index() == 0) {
      binary.reset();
      binaryExpected = chunk.count();
    } else if (binaryExpected != chunk.count()
        || binary.size() != chunk.index() * ProgramPackets.Artifact.CHUNK_BYTES) {
      binary.reset();
      binaryExpected = -1;
      return;
    }
    binary.writeBytes(chunk.bytes());
    if (chunk.index() < chunk.count() - 1) return;
    binaryExpected = -1;
    var bytes = binary.toByteArray();
    binary.reset();
    diagnose(WorldPrograms.get(sender.server).deployWasm(actor(), controller, bytes));
    publish();
  }

  private void publish() {
    var logs = controller.logs();
    var recent = logs.subList(Math.max(0, logs.size() - ProgramPackets.LINES), logs.size());
    var message = controller.message();
    var packet =
        new ProgramPackets.View(
            containerId,
            session,
            revision,
            controller.state().name().toLowerCase(Locale.ROOT),
            message.length() > 256 ? message.substring(0, 256) : message,
            controller.energy(),
            WorldPrograms.get(player().server).status(controller),
            controller.deployed(),
            diagnostics,
            recent.stream()
                .map(line -> line.length() > 192 ? line.substring(0, 192) : line)
                .toList());
    if (!packet.equals(view)) {
      view = packet;
      PacketDistributor.sendToPlayer(player(), packet);
    }
  }
}
