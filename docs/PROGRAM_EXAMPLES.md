# Program examples

Programs for the Program Controller, from one line to a whole factory. Each one compiles: a test
(`ProgramExamplesTest`) checks every example on this page and in
[PROGRAM_LANGUAGE.md](PROGRAM_LANGUAGE.md). The program helper serves this page too
([PROGRAM_HELPER.md](PROGRAM_HELPER.md)).

Words such as `furnace`, `smelter` or `lamp` are **labels**. Set them on machines with the Label
Tool, or change the program to use your own labels.

## Simple

### Empty a machine into storage

```text
every second: move iron ingots from smelter to storage
```

### Empty a chest completely

```text
every 2 seconds: move everything from "drop chest" to storage
```

### Keep a machine stocked

```text
every second: keep 32 bone meal in planter
```

### Craft when stock runs low

```text
# Make planks when storage runs low, and say so.
every 5 seconds:
    if storage has less than 64 oak planks:
        craft 64 oak planks
        say "Making planks"
```

### Feed and empty furnaces

```text
# Ores in the top, coal in the side, everything out of the bottom.
every second:
    move 8 raw materials from storage to furnace top
    keep 8 coal in furnace side
    move everything from furnace bottom to storage
```

## Medium

### A lever switches a line on and off

```text
# A lever on the "gate" machine runs the export; a lamp shows it.
when gate is powered called export:
    move 16 cobblestone from storage to crusher
    turn on lamp
when gate is unpowered called idle:
    turn off lamp
```

### Sort by item group

```text
# Ores, ingots and gems each go to their own chest; the rest stays in storage.
every 2 seconds called sort:
    move all ores from intake to "ore chest"
    move all ingots from intake to "ingot chest"
    move all gems from intake to "gem chest"
    move everything from intake to storage
```

### Share work between machines

```text
# Each crusher gets cobblestone only when it is running low.
every second:
    for each crusher in crushers:
        if crusher has less than 16 cobblestone:
            move 16 cobblestone from storage to crusher top
```

### Fluids

```text
# Fill the boiler with water and send lava to the generator.
every 5 seconds called water:
    if boiler has less than 4000 fluid water:
        move 4000 fluid water from storage to boiler
every second called lava:
    keep 1000 fluid lava in generator
```

### A stock gauge on a lamp

```text
# The lamp glows brighter as coal runs out.
every 10 seconds:
    if storage has no coal:
        set gauge redstone to 15
    otherwise if storage has less than 64 coal:
        set gauge redstone to 7
    otherwise:
        set gauge redstone to 0
```

## Complex

### Ore processing line

```text
# Smelt raw ores, fuel by need, sort the output, and warn when supplies run low.
every second called ores:
    move 8 raw materials from storage to furnace top
every second called fuel:
    for each f in furnace:
        if f has less than 4 coal and storage has at least 16 coal:
            move 8 coal from storage to f side
        otherwise if f has no coal and storage has no coal:
            say "Furnaces are out of fuel"
every second called output:
    move *_ingot except gold ingot from furnace bottom to storage
    move gold ingots from furnace bottom to "gold vault"
every 30 seconds called alarm:
    if storage has less than 32 coal or storage has less than 8 raw materials:
        turn on warning
    otherwise:
        turn off warning
```

### Farm

```text
# Empty the harvest, share bone meal, top up the water tank.
every 2 seconds called harvest:
    move everything except bone meal from harvest to storage
every 10 seconds called fertilise:
    for each planter in planters:
        keep 16 bone meal in planter
every 5 seconds called water:
    if tank has less than 4000 fluid water:
        move 4000 fluid water from storage to tank
```

### Building supplies on demand

```text
# Keep a stock of building blocks, crafting what runs low, one item per rule
# so a slow craft never holds up the others.
every 10 seconds called planks:
    if storage has less than 256 oak planks and storage has at least 16 oak logs:
        craft 64 oak planks
every 10 seconds called sticks:
    if storage has less than 64 sticks:
        craft 64 sticks
every 10 seconds called glass:
    if storage has less than 128 glass:
        move 32 sand from storage to kiln top
    move everything from kiln bottom to storage
every minute called report:
    if storage has less than 64 oak planks or storage has less than 64 glass:
        say "Building supplies are low"
```

### Night shift

```text
# A daylight sensor on "sun" is powered by day. By night, the mob farm's drops are
# sorted and the furnaces get any spare logs to burn as fuel.
when sun is unpowered called night:
    move bones from "mob farm" to storage
    move all arrows from "mob farm" to "arrow chest"
    move everything except rotten flesh from "mob farm" to storage
    for each f in furnace:
        if f has no coal and (storage has at least 64 logs and not storage has any coal):
            move 4 logs from storage to f side
when sun is powered called day:
    move all rotten flesh from "mob farm" to trash
```
