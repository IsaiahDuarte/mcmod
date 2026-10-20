//! Example program with persistent state: counts its own runs across reloads.
//!
//! `count` (every second) loads an 8-byte little-endian counter, increments it, saves it and logs
//! the new count. Two hourly rules demonstrate failure handling for tests: `oversize` saves more
//! than the state quota when storage holds a barrier, and `fail` panics when storage holds
//! bedrock. Both trap and leave the committed counter unchanged.
#![no_std]

use factorycore::prelude::*;

/// Decodes the counter blob (version 1: eight little-endian bytes; anything else is zero).
pub fn decode(blob: &[u8]) -> u64 {
    match <[u8; 8]>::try_from(blob) {
        Ok(bytes) => u64::from_le_bytes(bytes),
        Err(_) => 0,
    }
}

#[factorycore::program]
mod program {
    use super::*;

    #[rule(seconds = 1)]
    fn count(cx: &Rule) {
        let mut blob = [0; 8];
        let count = match cx.load_state(&mut blob) {
            Some(8) => decode(&blob),
            _ => 0,
        } + 1;
        cx.save_state(&count.to_le_bytes());
        // The controller prefixes the rule key: "count: 3".
        log!(cx, "{count}");
    }

    #[rule(minutes = 60)]
    fn oversize(cx: &Rule) {
        static TOO_BIG: [u8; factorycore::MAX_STATE + 1] = [0; factorycore::MAX_STATE + 1];
        if cx.stock(item("barrier")) > 0 {
            cx.save_state(&TOO_BIG);
        }
    }

    #[rule(minutes = 60)]
    fn fail(cx: &Rule) {
        if cx.stock(item("bedrock")) > 0 {
            panic!("bedrock in storage");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::program::RULES;
    use factorycore::prelude::*;
    use factorycore::testing::World;

    #[test]
    fn counts_across_runs_and_restores_after_reload() {
        let mut world = World::new();
        assert_eq!(world.run(RULES, "count").logs(), ["1"]);
        assert_eq!(world.run(RULES, "count").logs(), ["2"]);
        let saved = world.saved_state().unwrap().to_vec();
        assert_eq!(super::decode(&saved), 2);

        let mut reloaded = World::new().state(&saved);
        assert_eq!(reloaded.run(RULES, "count").logs(), ["3"]);
    }

    #[test]
    fn traps_leave_the_committed_counter_unchanged() {
        let mut world = World::new()
            .stock(item("barrier"), 1)
            .stock(item("bedrock"), 1);
        world.run(RULES, "count");
        let oversize = world.try_run(RULES, "oversize").unwrap_err();
        assert_eq!(oversize.message(), "state over 4096 bytes");
        let fail = world.try_run(RULES, "fail").unwrap_err();
        assert_eq!(fail.message(), "bedrock in storage");
        assert_eq!(world.run(RULES, "count").logs(), ["2"]);
    }

    #[test]
    fn decodes_version_one_blobs_and_treats_others_as_zero() {
        assert_eq!(super::decode(&7u64.to_le_bytes()), 7);
        assert_eq!(super::decode(&[1, 2, 3]), 0);
        assert_eq!(super::decode(&[]), 0);
    }
}
