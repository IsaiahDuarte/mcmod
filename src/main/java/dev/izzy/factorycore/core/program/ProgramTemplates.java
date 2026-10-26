package dev.izzy.factorycore.core.program;

import java.util.List;

/**
 * Built-in starter programs for the editor, covering import/export, stock maintenance, round-robin
 * distribution, fluids, energy, craft requests and the language v2 forms (machine to machine, sides
 * and slots, conditions, globs, redstone). Every template must compile.
 */
public final class ProgramTemplates {
  public record Template(String name, String source) {}

  public static final List<Template> ALL =
      List.of(
          new Template(
              "Import from machines",
              """
              # Pull finished items out of every machine labeled "output".
              every 20 ticks as "import" {
                  move item("minecraft:iron_ingot") from machines("output") to storage limit 64
              }
              """),
          new Template(
              "Export to machines",
              """
              # Send fuel from storage into every machine labeled "furnace".
              every 20 ticks as "export" {
                  move item("minecraft:coal") from storage to machines("furnace") limit 8
              }
              """),
          new Template(
              "Keep machines stocked",
              """
              # Keep each smelter's input at 64 raw iron, taking only the shortfall.
              every 10 ticks as "stock" {
                  for machine in machines("smelter_in") {
                      keep machine stocked with 64 of item("minecraft:raw_iron") from storage
                  }
              }
              """),
          new Template(
              "Round-robin distribution",
              """
              # Each pass gives every labeled machine a small share in stable order.
              every 20 ticks as "share" {
                  for machine in machines("workers") {
                      move item("minecraft:cobblestone") from storage to machine limit 16
                  }
              }
              """),
          new Template(
              "Fluids",
              """
              # Drain tanks labeled "pump" into fluid storage.
              every 20 ticks as "fluids" {
                  move fluid("minecraft:water") from machines("pump") to storage limit 4000
              }
              """),
          new Template(
              "Energy",
              """
              # Top up machines labeled "powered" with FE from energy storage.
              every 20 ticks as "energy" {
                  move energy from storage to machines("powered") limit 1000
              }
              """),
          new Template(
              "Craft when low",
              """
              # Request planks from autocrafting whenever storage runs low.
              every 5 seconds as "planks" {
                  if stock(item("minecraft:oak_planks")) < 64 {
                      craft 64 of item("minecraft:oak_planks")
                  }
              }
              """),
          new Template(
              "Machine to machine",
              """
              # One Interface on each furnace's west side: ore in from the top, ingots out below,
              # straight into the chest labeled "ingots" without passing through storage.
              every 20 ticks as "ore" {
                  move item("minecraft:raw_iron") from storage to machines("furnace") side up limit 8
              }
              every 20 ticks as "ingots" {
                  move item("*_ingot") from machines("furnace") side down slots 0 to machines("ingots") limit 64
              }
              """),
          new Template(
              "Auto smelter",
              """
              # Run a cable to each furnace and label it "furnace" with the Label Tool.
              # Ores go in the top, coal is kept at 8 on the side, ingots come out the bottom.
              every 20 ticks as "ores" {
                  move item("minecraft:raw_*") except item("minecraft:raw_*_block") from storage to machines("furnace") side up limit 8
              }
              every 20 ticks as "fuel" {
                  for f in machines("furnace") {
                      if f has < 8 of item("minecraft:coal") {
                          move item("minecraft:coal") from storage to f side north limit 8
                      }
                  }
              }
              every 20 ticks as "ingots" {
                  move everything from machines("furnace") side down to storage limit 64
              }
              """),
          new Template(
              "Empty containers",
              """
              # Move everything out of each container labeled "unload" into storage, except dirt.
              every 20 ticks as "unload" {
                  move everything except item("minecraft:dirt") from machines("unload") to storage limit 64
              }
              """),
          new Template(
              "Refuel when low",
              """
              # Top each furnace up with coal only while it holds fewer than 8.
              every 20 ticks as "refuel" {
                  for f in machines("furnace") {
                      if f has < 8 of item("minecraft:coal") {
                          move item("minecraft:coal") from storage to f side north limit 8
                      }
                  }
              }
              """),
          new Template(
              "Redstone-triggered export",
              """
              # While the Interface labeled "button" is powered, send cobblestone out and light
              # the Signal Interface labeled "busy".
              every 10 ticks as "export" {
                  if redstone(machines("button")) {
                      move item("minecraft:cobblestone") from storage to machines("out") limit 64
                      emit redstone 15 on machines("busy")
                  } else {
                      emit redstone 0 on machines("busy")
                  }
              }
              """));

  private ProgramTemplates() {}
}
