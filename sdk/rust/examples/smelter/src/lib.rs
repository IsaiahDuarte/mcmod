//! Example program equivalent to the friendly-language program in [`SMELTER`].
#![no_std]

use factorycore::prelude::*;

/// The friendly-language program this guest is equivalent to (checked by the JVM parity test).
pub const SMELTER: &str = r#"
every 10 ticks as "smelt" {
    for machine in machines("smelter_in") {
        keep machine stocked with 64 of item("minecraft:raw_iron") from storage
    }
    move item("minecraft:iron_ingot") from machines("smelter_out") to storage limit 64
}
every 5 seconds as "planks" {
    if stock(item("minecraft:oak_planks")) < 32 {
        craft 16 of item("minecraft:oak_planks")
        log "Low on planks"
    }
}
"#;

#[factorycore::program]
mod program {
    use super::*;

    /// Keeps every input smelter stocked with raw iron and collects the ingots.
    #[rule(ticks = 10)]
    fn smelt(cx: &Rule) {
        for machine in cx.machines("smelter_in") {
            machine.keep_stocked(item("raw_iron"), 64);
        }
        for machine in cx.machines("smelter_out") {
            machine.move_to_storage(item("iron_ingot"), 64);
        }
    }

    /// Crafts planks when storage runs low.
    #[rule(seconds = 5)]
    fn planks(cx: &Rule) {
        if cx.stock(item("oak_planks")) < 32 {
            cx.craft(item("oak_planks"), 16);
            cx.log("Low on planks");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::program::RULES;
    use factorycore::prelude::*;
    use factorycore::testing::{Command, MachineRef, World};

    #[test]
    fn smelt_stocks_every_input_and_drains_every_output() {
        let outcome = World::new()
            .machines("smelter_in", 2)
            .machines("smelter_out", 1)
            .run(RULES, "smelt");
        let input = |index| MachineRef {
            label: "smelter_in",
            index,
        };
        assert_eq!(
            outcome.commands(),
            [
                Command::KeepStocked {
                    machine: input(0),
                    resource: item("raw_iron"),
                    amount: 64
                },
                Command::KeepStocked {
                    machine: input(1),
                    resource: item("raw_iron"),
                    amount: 64
                },
                Command::MoveToStorage {
                    machine: MachineRef {
                        label: "smelter_out",
                        index: 0
                    },
                    resource: item("minecraft:iron_ingot"),
                    limit: 64
                },
            ]
        );
    }

    #[test]
    fn planks_are_crafted_only_below_32() {
        let low = World::new()
            .stock(item("oak_planks"), 31)
            .run(RULES, "planks");
        assert_eq!(low.crafts(), [(item("oak_planks"), 16)]);
        assert_eq!(low.logs(), ["Low on planks"]);

        let enough = World::new()
            .stock(item("oak_planks"), 32)
            .run(RULES, "planks");
        assert!(enough.commands().is_empty());
    }

    #[test]
    fn rules_register_in_friendly_language_order() {
        let rules: [_; 2] = core::array::from_fn(|i| (RULES[i].key, RULES[i].interval_ticks));
        assert_eq!(rules, [("smelt", 10), ("planks", 100)]);
        assert!(super::SMELTER.contains("as \"smelt\""));
    }
}
