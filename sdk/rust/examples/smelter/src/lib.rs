//! Example guest equivalent to the friendly-language program in `SMELTER`.
#![cfg_attr(all(not(test), target_arch = "wasm32"), no_std)]

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

#[cfg(all(not(test), target_arch = "wasm32"))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! {
    core::arch::wasm32::unreachable()
}

#[cfg(target_arch = "wasm32")]
mod guest {
    use factorycore::{craft, log, machines, register, stock, Resource};

    #[no_mangle]
    pub extern "C" fn factory_init() {
        register("smelt", 10);
        register("planks", 100);
    }

    #[no_mangle]
    pub extern "C" fn factory_rule(index: i32) {
        match index {
            0 => {
                for machine in machines("smelter_in") {
                    machine.keep_stocked(Resource::Item("minecraft:raw_iron"), 64);
                }
                for machine in machines("smelter_out") {
                    machine.move_to_storage(Resource::Item("minecraft:iron_ingot"), 64);
                }
            }
            1 if stock(Resource::Item("minecraft:oak_planks")) < 32 => {
                craft("minecraft:oak_planks", 16);
                log("Low on planks");
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn friendly_equivalent_is_present() {
        assert!(super::SMELTER.contains("as \"smelt\""));
    }
}
