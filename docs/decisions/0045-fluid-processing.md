# ADR 0045 — Fluid processing patterns

Status: selected; native headless evidence with a test-only item+fluid machine. Amends ADR
0032's "item only in version one". U4 adds fluid-kind projection and mB amounts to the crafting panel;
current graphical verification is tracked in the UX/implementation plans.

## Decision

`ProcessingContents` adds optional `fluid_inputs` and `fluid_outputs` (`FluidStack`,
1–16,000 mB each).

- **Bounds:** items and fluids together have 1–9 inputs and 1–3 outputs.
- **Primary output:** the first item output, or the first fluid output when a pattern has
  no item outputs.
- **Compatibility:** all four lists are optional fields, so item-only patterns encoded
  earlier decode unchanged.
- **Portable form:** the `Pattern` simply carries fluid `ResourceKey`s, so planning,
  reservation, staging and delivery need no change.

The Crafting Interface exposes `fluidMachine()`: the faced block's unsided fluid handler,
otherwise its faced-side handler.

- **Dispatch:** dispatch requires every handler kind the pattern's keys need
  (`machine_missing` otherwise; energy keys are never processable). The clean-output
  check also inspects tanks.
- **Insertion:** each pass fills exactly what the machine accepts of each staged fluid
  input. The rest stays staged.
- **Collection:** a pass drains only the expected output fluid, up to the remaining
  amount, into staging. A handler that returns a different fluid or more than requested
  fails the pass.
- **Cleanup:** cleanup after cancel collects fluids too.

Crafting requests and keep-stock rules now accept fluid keys; energy remains
unsupported. In terminal processing mode, fluid rows may fill cells. A fluid cell's count
is in **250 mB units**, so counts 1–64 mean 250–16,000 mB. Leaving processing mode drops
fluid cells.

## Alternatives

- **Bucket units:** would make common 250 mB machine steps impossible.
- **Raw mB counts:** need a numeric editor that the grid does not have.
- **Separate fluid patterns:** would duplicate the lease, dispatch and cleanup path.

## Consequences

Machines whose fluid handler mixes input and output tanks must still only drain the
declared output fluid; the drain is by fluid identity and amount. Exclusive use of the
machine during a job still applies.


## U4 display units (2026-10-20)

The wire/encoder count remains 1–64 in 250 mB steps, preserving existing patterns
and processing behavior. Player-facing selected amounts and hover use actual mB;
+ / − changes 250 mB per step and the maximum remains 16,000 mB. The grid view carries
fluid kind explicitly so it can render a bucket icon and apply fluid units even
when the resource browser has switched to Items. No ownership or schema change.
