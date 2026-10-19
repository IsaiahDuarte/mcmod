# Factory Core Rust SDK

Write Factory Core programs in Rust, compile them to WebAssembly outside the game, and
deploy the `.wasm` file to a Program Controller. Programs use the `factory_v1` host ABI
([ADR 0037](../../docs/decisions/0037-wasm-factory-v1.md),
[ADR 0054](../../docs/decisions/0054-rust-sdk-v2.md)); the SDK hides it completely.

## A program

```rust
#![no_std]
use factorycore::prelude::*;

#[factorycore::program]
mod program {
    use super::*;

    /// Keeps every input smelter stocked and collects the ingots.
    #[rule(ticks = 10)]
    fn smelt(cx: &Rule) {
        for machine in cx.machines("smelter_in") {
            machine.keep_stocked(item("raw_iron"), 64);
        }
        for machine in cx.machines("smelter_out") {
            machine.move_to_storage(item("iron_ingot"), 64);
        }
    }

    #[rule(seconds = 5, key = "planks")]
    fn low_planks(cx: &Rule) {
        let planks = cx.stock(item("oak_planks"));
        if planks < 32 {
            cx.craft(item("oak_planks"), 16);
            log!(cx, "Low on planks ({planks} left)");
        }
    }
}
```

- Each `#[rule(...)]` function runs on its own interval: exactly one of `ticks = N`,
  `seconds = N` or `minutes = N`, between 10 ticks (half a second) and one hour. The rule
  key defaults to the function name; `key = "..."` overrides it. Rules register in source
  order, at most 64. Mistakes are compile errors pointing at the attribute.
- Everything a rule can do goes through `cx: &Rule`: `machines(label)`, `stock(resource)`,
  `craft(item, amount)`, `log(text)` / `log!(cx, ...)`, `load_state` / `save_state`.
  Machine handles borrow `cx`, so keeping one past its rule does not compile.
- Resources: `item("raw_iron")`, `fluid("water")`, `item_tag("logs")`, `ENERGY`. IDs
  without a namespace use `minecraft:`.
- Calls only *stage* commands; they take effect after the rule returns. If a rule panics or
  exceeds a limit, its commands and state write are discarded and the program stops until
  resumed. The controller log shows the panic message and source line, for example
  `trap fail: factory_rule: guest panicked: src/lib.rs:47: bedrock in storage`.

The full API is documented in the crate: `cargo doc -p factorycore --open`.

## Starting a project

Copy `examples/smelter` and edit `src/lib.rs`. A standalone project needs three things
from this workspace:

- `Cargo.toml`: `crate-type = ["cdylib"]` and the release profile
  (`panic = "abort"`, `opt-level = "s"`, `lto = true`, `codegen-units = 1`, `strip = true`).
- `.cargo/config.toml`: the `wasm32v1-none` link arguments and the `cargo guest` alias.
- `rust-toolchain.toml`: Rust 1.95.0 with the `wasm32v1-none` target.

## Build

```text
cargo guest
```

That is `cargo build --release --target wasm32v1-none`; the artifact is
`target/wasm32v1-none/release/<crate>.wasm`. Copy it into `factorycore/programs` in your
game folder and press **Upload Wasm** in the Program Controller.

`wasm32v1-none` emits only WebAssembly 1.0 instructions, which is what the controller
accepts, so ordinary Rust such as `copy_from_slice` and `core::fmt` works. The artifact must
be at most 128 KiB, import only `factory_v1` functions and use at most 32 memory pages
(set by `.cargo/config.toml`). The examples are about 9–13 KiB.

## Testing

`factorycore::testing` runs rules natively with `cargo test`, against fake storage stock,
labeled machines and state. It enforces the same limits and trap rules as the server:

```rust
#[cfg(test)]
mod tests {
    use super::program::RULES;
    use factorycore::prelude::*;
    use factorycore::testing::World;

    #[test]
    fn crafts_planks_when_low() {
        let outcome = World::new().stock(item("oak_planks"), 10).run(RULES, "planks");
        assert_eq!(outcome.crafts(), [(item("oak_planks"), 16)]);
        assert_eq!(outcome.logs(), ["Low on planks (10 left)"]);
    }
}
```

`World::try_run` returns the trap instead of panicking, and a `World` keeps committed state
across runs (see `examples/counter`). The mock does not model the instruction budget, FE,
permissions or what the server later does with the commands.

## Persistent state

`cx.load_state(&mut buffer)` returns the committed state's length (or `None`), and
`cx.save_state(&bytes)` stages up to `factorycore::MAX_STATE` (4,096) bytes. The write
commits only if the rule finishes. State survives reloads and resumes, but a new deployment
starts empty. Version your own byte layout; see `examples/counter`.

## Limits per rule

65,536 Wasm instructions, 512 host calls, 256 staged commands, 64 machines per label, a
50 ms deadline, and 128 bytes per label, ID or log line (`log` truncates; other text over
the limit traps). Amounts are 1..=1,048,576, and moves at most 4,096 per call.
