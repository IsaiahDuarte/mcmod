# ADR 0044 — Persistent guest state

Status: selected; JVM and native evidence with a real Rust guest. Amends ADR 0037, which
deferred persistent state.

## Decision

`factory_v1` gains two **additive** imports, so existing guests that do not import them are
unchanged and `WasmPreflight.IMPORTS` simply allows them:

| Import | Signature | Effect |
| --- | --- | --- |
| `state_read(buf, cap)` | `(i32,i32)->i32` | copies up to `cap` bytes of the current state; returns its full length, or -1 when unset |
| `state_write(ptr, len)` | `(i32,i32)->i32` | rule callbacks only; stages the whole blob, at most 4,096 bytes |

- **Quota:** one blob of at most `WasmProgram.MAX_STATE` = 4,096 bytes per deployment.
  A larger write, or a write during `factory_init`, traps.
- **Transactions:** a write commits only when its callback returns without a trap, at the
  same moment its commands are published. Reads inside that callback see the staged
  value. A trap discards the staged write, and the committed state stays.
- **Persistence:** committed state is the controller's `GuestState` (block-entity schema
  3). It is restored into each fresh instance (load, resume after a trap) before
  `factory_init`, which may read it.
- **Lifetime:** state belongs to the deployment. Any deploy, including the same artifact
  again, and any stop/undeploy clears it.
- **Versioning:** guests version their own blob layout. The host never interprets it.
- **Concurrency:** server thread only.

The SDK exposes `factorycore::state::{load, save, MAX}` and allocation-free `format_u64`.
The `examples/counter` guest persists a count and has a conditional over-quota rule. Guests
must avoid slice copies such as `copy_from_slice`: the prebuilt Rust `core` lowers them to
bulk-memory opcodes, which the MVP preflight rejects (`unsupported opcode 252`).

## Alternatives

- **Key/value state:** adds parsing, per-key quotas and enumeration for no v1 need. One blob
  is enough and easy to reason about.
- **Committing writes immediately:** would let a trapping callback leave a half-updated
  state.
- **Keeping state across redeploys:** would bind a new program to an old layout without the
  guest's consent.

## Consequences

State commits with the callback, not with later command execution. A crash after commit
but before the commands run therefore keeps the new state with fewer effects; commands
are never replayed. Old controllers (schema 1–2) load with no state. A schema-3 tag with
`GuestState` but no `Artifact`, or with more than 4,096 bytes, is retained unchanged as
unsupported data.
