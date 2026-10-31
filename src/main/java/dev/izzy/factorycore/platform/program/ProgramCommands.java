package dev.izzy.factorycore.platform.program;

import dev.izzy.factorycore.platform.Actors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /factorycore programs vocabulary} (operators): writes this game's program vocabulary,
 * every installed mod's items, fluids, item tags and English names, to {@code
 * factorycore/program_vocabulary.txt} in the game folder, for the offline program helper's {@code
 * FACTORYCORE_VOCABULARY} (ADR 0068).
 */
public final class ProgramCommands {
  private ProgramCommands() {}

  public static void register(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("factorycore")
                .then(
                    Commands.literal("programs")
                        .then(
                            Commands.literal("vocabulary")
                                .requires(source -> source.hasPermission(Actors.OPERATOR_LEVEL))
                                .executes(context -> export(context.getSource())))));
  }

  private static int export(CommandSourceStack source) {
    try {
      var path = export(source.getServer());
      var vocabulary = GameVocabulary.get();
      source.sendSuccess(
          () ->
              Component.translatable(
                  "message.factorycore.programs.vocabulary",
                  vocabulary.items().size(),
                  vocabulary.fluids().size(),
                  vocabulary.itemTags().size(),
                  path.toString()),
          false);
      return 1;
    } catch (IOException failure) {
      source.sendFailure(
          Component.translatable(
              "message.factorycore.programs.vocabulary_failed", failure.getMessage()));
      return 0;
    }
  }

  /** Writes the vocabulary file and returns where it went. */
  public static Path export(MinecraftServer server) throws IOException {
    var path = server.getServerDirectory().resolve("factorycore").resolve("program_vocabulary.txt");
    Files.createDirectories(path.getParent());
    Files.writeString(
        path,
        "# Exported by /factorycore programs vocabulary (ADR 0068).\n"
            + GameVocabulary.get().write(),
        StandardCharsets.UTF_8);
    return path;
  }
}
