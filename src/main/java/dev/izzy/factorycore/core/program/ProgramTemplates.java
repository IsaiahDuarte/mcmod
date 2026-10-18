package dev.izzy.factorycore.core.program;

import java.util.List;

/**
 * Built-in starter programs for the editor, covering import/export, stock maintenance, round-robin
 * distribution, fluids, energy and craft requests. Every template must compile.
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
              """));

  private ProgramTemplates() {}
}
