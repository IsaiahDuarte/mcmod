# Factory Core Rust SDK

Write Factory Core programs in Rust, compile them to WebAssembly outside the game, and
deploy the `.wasm` artifact to a Program Controller. The ABI is `factory_v1`
([ADR 0037](../../docs/decisions/0037-wasm-factory-v1.md)).

## Starter project

Copy `examples/smelter` and edit `src/lib.rs`. A program exports two functions:

```rust
use factorycore::{machines, register, Resource};

#[no_mangle]
pub extern "C" fn factory_init() {
    register("smelt", 10); // rule 0 runs every 10 ticks
}

#[no_mangle]
pub extern "C" fn factory_rule(index: i32) {
    if index == 0 {
        for machine in machines("smelter_in") {
            machine.keep_stocked(Resource::Item("minecraft:raw_iron"), 64);
        }
    }
}
```

Calls inside a rule only stage commands; they take effect after the rule returns.

## Build

Requires Rust 1.95.0 with the `wasm32-unknown-unknown` target:

```text
cargo +1.95.0 rustc --release -p factorycore-smelter --target wasm32-unknown-unknown \
  --crate-type cdylib -- -C link-arg=--max-memory=2097152 -C link-arg=-zstack-size=65536 \
  -C target-feature=-reference-types,-multivalue,-bulk-memory,-simd128,-nontrapping-fptoint
```

The artifact must be at most 128 KiB, import only `factory_v1` functions and use at
most 32 memory pages. Each rule run may use 65,536 instructions and 512 host calls.


## Persistent state

`factorycore::state::load(&mut buf)` returns the committed state's length (or `None`), and
`factorycore::state::save(&bytes)` stages up to `state::MAX` (4,096) bytes, committed only if
the rule callback finishes. State survives reloads and resumes, but a new deployment starts
empty. See `examples/counter`. Avoid `copy_from_slice` and similar slice copies in guests:
the prebuilt `core` emits bulk-memory instructions that Factory Core rejects. Write bytes
individually instead, or pass slices directly to host calls.
