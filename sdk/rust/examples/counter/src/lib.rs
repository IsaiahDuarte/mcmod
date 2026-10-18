//! Example guest with persistent state: counts its own runs across reloads.
//!
//! Rule 0 ("count", every 20 ticks) loads an 8-byte little-endian counter, increments it,
//! saves it and logs the new count. Rule 1 ("oversize", every hour) tries to save more than the
//! quota when storage holds a barrier; that traps and must leave the committed counter unchanged.
#![cfg_attr(all(not(test), target_arch = "wasm32"), no_std)]

/// Decodes the counter blob (version 1: eight little-endian bytes; anything else is zero).
pub fn decode(blob: &[u8]) -> u64 {
    match <[u8; 8]>::try_from(blob) {
        Ok(bytes) => u64::from_le_bytes(bytes),
        Err(_) => 0,
    }
}

#[cfg(all(not(test), target_arch = "wasm32"))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! {
    core::arch::wasm32::unreachable()
}

#[cfg(target_arch = "wasm32")]
mod guest {
    use factorycore::{format_u64, log, register, state, stock, Resource};

    #[no_mangle]
    pub extern "C" fn factory_init() {
        register("count", 20);
        register("oversize", 72_000);
    }

    #[no_mangle]
    pub extern "C" fn factory_rule(index: i32) {
        if index == 1 {
            // Demonstrates the quota: with a barrier in storage this write traps, and the
            // committed counter must stay unchanged.
            static TOO_BIG: [u8; state::MAX + 1] = [0; state::MAX + 1];
            if stock(Resource::Item("minecraft:barrier")) > 0 {
                state::save(&TOO_BIG);
            }
            return;
        }
        let mut blob = [0u8; 8];
        let count = match state::load(&mut blob) {
            Some(8) => super::decode(&blob),
            _ => 0,
        } + 1;
        state::save(&count.to_le_bytes());
        // Logs only the digits: slice copies would emit bulk-memory opcodes, which the
        // factory_v1 preflight rejects. The controller prefixes the rule key ("count: N").
        let mut digits = [0; 20];
        log(format_u64(count, &mut digits));
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn decodes_version_one_blobs_and_treats_others_as_zero() {
        assert_eq!(super::decode(&7u64.to_le_bytes()), 7);
        assert_eq!(super::decode(&[1, 2, 3]), 0);
        assert_eq!(super::decode(&[]), 0);
    }
}
