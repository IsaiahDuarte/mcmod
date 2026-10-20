//! Rust SDK for Factory Core programs (`factory_v1`, ADR 0037 and 0054).
//!
//! A program is an inline module marked [`#[program]`](macro@program); each `fn(cx: &Rule)`
//! inside marked `#[rule(...)]` runs on its own interval:
//!
//! ```
//! use factorycore::prelude::*;
//!
//! #[factorycore::program]
//! mod program {
//!     use super::*;
//!
//!     #[rule(ticks = 10)]
//!     fn smelt(cx: &Rule) {
//!         for machine in cx.machines("smelter_in") {
//!             machine.keep_stocked(item("raw_iron"), 64);
//!         }
//!     }
//! }
//! # fn main() {}
//! ```
//!
//! Program crates are `#![no_std]` `cdylib`s built with `cargo guest` (see the SDK README).
//!
//! Calls on [`Rule`] and [`Machine`] *stage* commands; the server publishes them after the rule
//! returns. If the rule panics or exceeds a limit, the rule's commands and state write are
//! discarded and the controller stops the program until it is resumed. A panic's message and
//! location appear in the controller log.
//!
//! Limits per rule: 65,536 Wasm instructions, 512 host calls, 256 staged commands, 64 machines
//! per label, 128-byte text, 4,096 bytes of state. Test rules natively with [`testing`].
#![no_std]

#[cfg(not(target_arch = "wasm32"))]
extern crate std;

mod resource;
mod rule;
mod sys;
mod text;

#[cfg(not(target_arch = "wasm32"))]
pub mod testing;

pub use factorycore_macros::{program, rule};
pub use resource::{ENERGY, Resource, ResourceKind, fluid, item, item_tag};
pub use rule::{Machine, Machines, Rule, RuleDef};
pub use text::MAX_TEXT;

/// Maximum persistent state in bytes.
pub const MAX_STATE: usize = 4_096;

/// Everything a program usually needs: `use factorycore::prelude::*;`.
pub mod prelude {
    pub use crate::{ENERGY, Machine, Resource, Rule, fluid, item, item_tag, log, program};
}

/// Formats a line into the controller log without allocating, cut to 128 bytes:
/// `log!(cx, "stocked {} of {}", count, total)`.
#[macro_export]
macro_rules! log {
    ($cx:expr, $($format:tt)+) => {
        $crate::__private::log($cx, ::core::format_args!($($format)+))
    };
}

#[doc(hidden)]
pub mod __private {
    use crate::{Rule, RuleDef, sys, text};

    /// `factory_init`: registers each rule; its host index must equal its position.
    pub fn init(rules: &[RuleDef]) {
        for (index, rule) in rules.iter().enumerate() {
            if sys::register_rule(rule.key, rule.interval_ticks) as usize != index {
                sys::panic("host assigned an unexpected rule index");
            }
        }
    }

    /// `factory_rule(index)`: runs one registered rule; unknown indexes do nothing.
    pub fn dispatch(rules: &[RuleDef], index: i32) {
        if let Some(rule) = usize::try_from(index).ok().and_then(|i| rules.get(i)) {
            (rule.run)(&Rule::new());
        }
    }

    pub fn log(cx: &Rule, arguments: core::fmt::Arguments<'_>) {
        match arguments.as_str() {
            Some(literal) => cx.log(literal),
            None => {
                let mut line = text::Line::new();
                let _ = core::fmt::write(&mut line, arguments);
                cx.log(line.as_str());
            }
        }
    }
}

#[cfg(all(target_arch = "wasm32", feature = "panic-handler", not(test)))]
#[panic_handler]
fn panic(info: &core::panic::PanicInfo<'_>) -> ! {
    use core::fmt::Write;
    let mut line = text::Line::new();
    if let Some(location) = info.location() {
        let _ = write!(line, "{}:{}: ", location.file(), location.line());
    }
    let _ = write!(line, "{}", info.message());
    sys::panic(line.as_str())
}
