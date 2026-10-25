//! Language-v2 example (ADR 0062): sides, slots, `has`, globs with `except`, machine-to-machine
//! moves and redstone, equivalent to the friendly-language program in [`FURNACE`].
#![no_std]

use factorycore::prelude::*;

/// The friendly-language program this guest is equivalent to (checked by the JVM parity test).
pub const FURNACE: &str = r#"
every 10 ticks as "furnace" {
    for f in machines("furnace") {
        if f has < 8 of item("minecraft:coal") {
            move item("minecraft:coal") from storage to f side north limit 4
        }
        move item("*_ingot") except item("iron_ingot") from f side down slots 0 to storage limit 64
    }
}
every 20 ticks as "pass" {
    move item("minecraft:cobblestone") from machines("left") to machines("right") limit 16
}
every 10 ticks as "relay" {
    if redstone(machines("button")) { emit redstone 15 on machines("alarm") }
    else { emit redstone 0 on machines("alarm") }
}
"#;

#[factorycore::program]
mod program {
    use super::*;

    /// Refuels each furnace from the side when it is low, and empties its output from below.
    #[rule(ticks = 10)]
    fn furnace(cx: &Rule) {
        for f in cx.machines("furnace") {
            if f.has(item("coal")) < 8 {
                f.side(Side::North).move_from_storage(item("coal"), 4);
            }
            f.side(Side::Down)
                .slots(0..=0)
                .move_to_storage(item("*_ingot except iron_ingot"), 64);
        }
    }

    /// Passes cobblestone between two machines without using storage.
    #[rule(ticks = 20)]
    fn pass(cx: &Rule) {
        for left in cx.machines("left") {
            for right in cx.machines("right") {
                left.move_to(right, item("cobblestone"), 16);
            }
        }
    }

    /// Mirrors a button's redstone onto Signal Interfaces labelled "alarm".
    #[rule(ticks = 10)]
    fn relay(cx: &Rule) {
        let pressed = cx.machines("button").any(|button| button.powered());
        cx.emit_redstone("alarm", if pressed { 15 } else { 0 });
    }
}

#[cfg(test)]
mod tests {
    use super::program::RULES;
    use factorycore::prelude::*;
    use factorycore::testing::{Command, MachineRef, Place, World};

    #[test]
    fn refuels_only_when_low_and_empties_the_output_slot() {
        let furnace = MachineRef {
            label: "furnace",
            index: 0,
        };
        let low = World::new()
            .machines("furnace", 1)
            .contents("furnace", 0, item("coal"), 3)
            .run(RULES, "furnace");
        assert_eq!(
            low.commands()[0],
            Command::Move {
                from: None,
                to: Some(Place {
                    machine: furnace,
                    side: Some(Side::North),
                    slots: None
                }),
                resource: item("coal"),
                limit: 4
            }
        );
        assert_eq!(
            low.commands()[1],
            Command::Move {
                from: Some(Place {
                    machine: furnace,
                    side: Some(Side::Down),
                    slots: Some((0, 0))
                }),
                to: None,
                resource: item("*_ingot except iron_ingot"),
                limit: 64
            }
        );
        let full = World::new()
            .machines("furnace", 1)
            .contents("furnace", 0, item("coal"), 8)
            .run(RULES, "furnace");
        assert_eq!(full.commands().len(), 1, "No refuel at 8 coal");
    }

    #[test]
    fn relays_the_button_and_passes_between_machines() {
        let pressed = World::new()
            .machines("button", 2)
            .powered("button", 1)
            .run(RULES, "relay");
        assert_eq!(
            pressed.commands(),
            [Command::Emit {
                label: "alarm",
                strength: 15
            }]
        );
        let idle = World::new().machines("button", 1).run(RULES, "relay");
        assert_eq!(
            idle.commands(),
            [Command::Emit {
                label: "alarm",
                strength: 0
            }]
        );
        let passed = World::new()
            .machines("left", 1)
            .machines("right", 1)
            .run(RULES, "pass");
        assert_eq!(passed.commands().len(), 1);
    }
}
