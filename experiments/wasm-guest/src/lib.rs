#![cfg_attr(all(not(test), target_arch = "wasm32"), no_std)]

/// The probe's exact signed i64 quantity contract; negative quantities are rejected.
pub fn checked_quantity(amount: i64) -> Option<i64> {
    (amount >= 0).then_some(amount)
}

#[cfg(all(not(test), target_arch = "wasm32"))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! {
    core::arch::wasm32::unreachable()
}

#[cfg(target_arch = "wasm32")]
mod guest {
    #[link(wasm_import_module = "factory_probe")]
    extern "C" {
        fn record(amount: i64) -> i64;
    }

    #[no_mangle]
    pub extern "C" fn probe_echo(amount: i64) -> i64 {
        match super::checked_quantity(amount) {
            Some(exact) => unsafe { record(exact) },
            None => -1,
        }
    }

    #[no_mangle]
    pub extern "C" fn probe_loop() {
        loop {
            core::hint::black_box(1_i32);
        }
    }

    #[no_mangle]
    pub extern "C" fn probe_host_loop() {
        loop {
            unsafe { record(1) };
        }
    }

    #[no_mangle]
    pub extern "C" fn probe_grow(pages: usize) -> usize {
        core::arch::wasm32::memory_grow::<0>(pages)
    }

    #[no_mangle]
    pub extern "C" fn probe_trap_after_record() {
        unsafe { record(42) };
        core::arch::wasm32::unreachable()
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn quantities_preserve_boundaries_and_reject_negatives() {
        for amount in [0, 1, 9_007_199_254_740_993, i64::MAX] {
            assert_eq!(super::checked_quantity(amount), Some(amount));
        }
        for amount in [-1, i64::MIN] {
            assert_eq!(super::checked_quantity(amount), None);
        }
    }
}
