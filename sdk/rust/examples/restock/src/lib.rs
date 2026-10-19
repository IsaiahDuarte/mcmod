//! Example program with typed persistent state (the SDK's `serde` feature).
//!
//! `planks` crafts planks when storage runs low and keeps running totals in a [`Stats`]
//! struct that survives reloads; `reset` clears them when storage holds a structure void.
#![no_std]

use factorycore::prelude::*;
use serde::{Deserialize, Serialize};

/// Totals kept across runs and reloads of one deployment.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Stats {
    pub runs: u32,
    pub crafts: u32,
    pub lowest: Option<u64>,
}

#[factorycore::program]
mod program {
    use super::*;

    #[rule(seconds = 5)]
    fn planks(cx: &Rule) {
        let mut stats: Stats = cx.state().unwrap_or_default();
        let planks = cx.stock(item("oak_planks"));
        stats.runs += 1;
        stats.lowest = Some(stats.lowest.map_or(planks, |lowest| lowest.min(planks)));
        if planks < 32 {
            cx.craft(item("oak_planks"), 16);
            stats.crafts += 1;
        }
        cx.set_state(&stats);
        log!(cx, "run {}, {} crafts", stats.runs, stats.crafts);
    }

    #[rule(minutes = 60)]
    fn reset(cx: &Rule) {
        if cx.stock(item("structure_void")) > 0 {
            cx.set_state(&Stats::default());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::Stats;
    use super::program::RULES;
    use factorycore::prelude::*;
    use factorycore::testing::World;

    #[test]
    fn totals_accumulate_and_survive_a_reload() {
        let mut world = World::new().stock(item("oak_planks"), 10);
        assert_eq!(world.run(RULES, "planks").logs(), ["run 1, 1 crafts"]);
        let mut world = World::new()
            .stock(item("oak_planks"), 50)
            .state(world.saved_state().unwrap());
        let outcome = world.run(RULES, "planks");
        assert_eq!(outcome.logs(), ["run 2, 1 crafts"]);
        assert!(outcome.crafts().is_empty());
    }

    #[test]
    fn reset_only_clears_with_a_structure_void() {
        let mut world = World::new();
        world.run(RULES, "planks");
        assert!(!world.run(RULES, "reset").state_changed());
        let mut world = World::new()
            .stock(item("structure_void"), 1)
            .state(world.saved_state().unwrap());
        assert!(world.run(RULES, "reset").state_changed());
        assert_eq!(world.run(RULES, "planks").logs(), ["run 1, 1 crafts"]);
    }

    #[test]
    fn stats_default_to_zero() {
        assert_eq!(
            Stats::default(),
            Stats {
                runs: 0,
                crafts: 0,
                lowest: None
            }
        );
    }
}
