package dev.izzy.factorycore.core.program;

import java.util.List;

/**
 * Built-in starter programs for the editor, written as sentences (ADR 0068), covering
 * import/export, stock maintenance, round-robin distribution, fluids, energy, craft requests and
 * the language v2 forms (machine to machine, sides and slots, conditions, globs, redstone). Every
 * template must compile.
 */
public final class ProgramTemplates {
  public record Template(String name, String source) {}

  public static final List<Template> ALL =
      List.of(
          new Template(
              "Import from machines",
              """
              # Pull finished items out of every machine labelled "output".
              every second called import:
                  move iron ingots from output to storage
              """),
          new Template(
              "Export to machines",
              """
              # Send fuel from storage into every machine labelled "furnace".
              every second called export:
                  move 8 coal from storage to furnace
              """),
          new Template(
              "Keep machines stocked",
              """
              # Keep each smelter's input at 64 raw iron, taking only what is missing.
              every 0.5 seconds called stock:
                  for each machine in smelter_in:
                      keep 64 raw iron in machine
              """),
          new Template(
              "Round-robin distribution",
              """
              # Each pass gives every labelled machine a small share in turn.
              every second called share:
                  for each machine in workers:
                      move 16 cobblestone from storage to machine
              """),
          new Template(
              "Fluids",
              """
              # Drain tanks labelled "pump" into fluid storage.
              every second called fluids:
                  move 4000 fluid water from pump to storage
              """),
          new Template(
              "Energy",
              """
              # Top up machines labelled "powered" with FE from energy storage.
              every second called energy:
                  move 1000 energy from storage to powered
              """),
          new Template(
              "Craft when low",
              """
              # Ask autocrafting for planks whenever storage runs low.
              every 5 seconds called planks:
                  if storage has less than 64 oak planks:
                      craft 64 oak planks
              """),
          new Template(
              "Machine to machine",
              """
              # One Interface on each furnace's west side: ore in the top, ingots out the bottom,
              # straight into the chest labelled "ingots" without passing through storage.
              every second called ore:
                  move 8 raw iron from storage to furnace top
              every second called ingots:
                  move *_ingot from furnace bottom slot 0 to ingots
              """),
          new Template(
              "Auto smelter",
              """
              # Run a cable to each furnace and label it "furnace" with the Label Tool.
              # Raw ores go in the top, coal is kept at 8 in the side, ingots come out the bottom.
              every second called ores:
                  move 8 raw materials from storage to furnace top
              every second called fuel:
                  keep 8 coal in furnace side
              every second called ingots:
                  move everything from furnace bottom to storage
              """),
          new Template(
              "Empty containers",
              """
              # Move everything out of each container labelled "unload" into storage, except dirt.
              every second called unload:
                  move everything except dirt from unload to storage
              """),
          new Template(
              "Refuel when low",
              """
              # Top each furnace up with coal only while it holds fewer than 8.
              every second called refuel:
                  for each f in furnace:
                      if f has less than 8 coal:
                          move 8 coal from storage to f side
              """),
          new Template(
              "Redstone-triggered export",
              """
              # While the machine labelled "button" is powered, send cobblestone out and light the
              # Signal Interface labelled "busy".
              every 0.5 seconds called export:
                  if button is powered:
                      move cobblestone from storage to out
                      turn on busy
                  otherwise:
                      turn off busy
              """));

  private ProgramTemplates() {}
}
