# Program helper (MCP)

The program helper lets an AI assistant write Factory Core programs with you. It is a small
[MCP](https://modelcontextprotocol.io) server that runs on your computer. It needs no game, no
network and no world. It knows the [program language](PROGRAM_LANGUAGE.md) and every item name,
and it checks programs with the same compiler the Program Controller uses. The design is in
[ADR 0068](decisions/0068-sentence-programs.md).

## Build it

```sh
./gradlew programHelperJar
```

This writes `build/libs/factorycore-program-helper.jar`. It needs Java 21 and nothing else.

## Connect it

**Claude Code:**

```sh
claude mcp add factorycore-programs -- java -jar /full/path/to/factorycore-program-helper.jar
```

**Claude Desktop or another MCP client:** add it to the client's server list:

```json
{
  "mcpServers": {
    "factorycore-programs": {
      "command": "java",
      "args": ["-jar", "/full/path/to/factorycore-program-helper.jar"]
    }
  }
}
```

Then ask, for example: *"Write a Factory Core program that keeps my furnaces labelled `furnace`
fuelled with coal and moves their ingots to storage."* Paste the result into the Program
Controller's editor and press **Check**.

## Tools

| Tool | Gives |
| --- | --- |
| `language_reference` | The language guide ([PROGRAM_LANGUAGE.md](PROGRAM_LANGUAGE.md)). |
| `list_examples` | The editor's templates. |
| `check_program` | `ok`, or each problem with its line, column, hint and an example. When ok, it also gives the rules and the program with every name as its ID. |
| `find_names` | How a program names items, item groups or fluids that match some text, closest first. |

## Modpacks

The jar knows Minecraft's and Factory Core's names. To use the items of every mod in your game:

1. In the game, as an operator, run `/factorycore programs vocabulary`. It writes
   `factorycore/program_vocabulary.txt` in the game folder and says where.
2. Point the helper at that file:

   ```sh
   claude mcp add factorycore-programs -e FACTORYCORE_VOCABULARY=/path/to/game/factorycore/program_vocabulary.txt -- java -jar /full/path/to/factorycore-program-helper.jar
   ```

   In a JSON config, add `"env": {"FACTORYCORE_VOCABULARY": "…"}`.

## What it does not do

- **No world access:** it cannot see your world, your machine labels or what is stored. Tell
  it your labels.
- **No deploying:** it cannot deploy programs; you paste and deploy them in the game.

A live link to a running world is a separate, later decision.
