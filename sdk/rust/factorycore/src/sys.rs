//! Safe wrappers over the `factory_v1` host imports (ADR 0037/0054). On wasm32 they call the
//! real imports; elsewhere they call the deterministic mock in [`crate::testing`]. Everything
//! above this module is ABI-agnostic.
//!
//! Staging imports return 0 and trap on any error, so their results are ignored.

#[cfg(target_arch = "wasm32")]
mod imports {
    #[link(wasm_import_module = "factory_v1")]
    unsafe extern "C" {
        pub fn register_rule(key: *const u8, key_len: i32, interval: i32) -> i32;
        pub fn machines(label: *const u8, label_len: i32) -> i64;
        pub fn keep_stocked(
            machine: i32,
            kind: i32,
            id: *const u8,
            id_len: i32,
            amount: i64,
        ) -> i32;
        pub fn move_to_storage(
            machine: i32,
            kind: i32,
            id: *const u8,
            id_len: i32,
            limit: i64,
        ) -> i32;
        pub fn move_from_storage(
            machine: i32,
            kind: i32,
            id: *const u8,
            id_len: i32,
            limit: i64,
        ) -> i32;
        pub fn craft(id: *const u8, id_len: i32, amount: i64) -> i32;
        pub fn stock(kind: i32, id: *const u8, id_len: i32) -> i64;
        pub fn log(text: *const u8, text_len: i32) -> i32;
        pub fn state_read(buffer: *mut u8, capacity: i32) -> i32;
        pub fn state_write(data: *const u8, len: i32) -> i32;
        // Language v2 (ADR 0062), additive.
        pub fn with_access(machine: i32, side: i32, first: i32, last: i32) -> i32;
        pub fn move2(from: i32, to: i32, kind: i32, id: *const u8, id_len: i32, limit: i64) -> i32;
        pub fn has(machine: i32, kind: i32, id: *const u8, id_len: i32) -> i64;
        pub fn redstone_in(machine: i32) -> i32;
        pub fn emit(label: *const u8, label_len: i32, strength: i32) -> i32;
        pub fn panic(text: *const u8, text_len: i32);
    }
}

#[cfg(target_arch = "wasm32")]
mod real {
    use super::imports as host;
    use crate::Resource;

    // Lengths are bounded by the host (128-byte text, 4,096-byte state); a longer slice traps
    // there, and wasm32 slices never exceed i32::MAX.
    fn len(bytes: &[u8]) -> i32 {
        bytes.len() as i32
    }

    pub fn register_rule(key: &str, interval: u32) -> i32 {
        unsafe { host::register_rule(key.as_ptr(), len(key.as_bytes()), interval as i32) }
    }

    pub fn machines(label: &str) -> (i32, i32) {
        let packed = unsafe { host::machines(label.as_ptr(), len(label.as_bytes())) };
        ((packed >> 32) as i32, packed as i32)
    }

    pub fn keep_stocked(machine: i32, resource: Resource<'_>, amount: u32) {
        let id = resource.id();
        let kind = resource.kind() as i32;
        unsafe {
            host::keep_stocked(
                machine,
                kind,
                id.as_ptr(),
                len(id.as_bytes()),
                amount.into(),
            )
        };
    }

    pub fn move_to_storage(machine: i32, resource: Resource<'_>, limit: u32) {
        let id = resource.id();
        let kind = resource.kind() as i32;
        unsafe {
            host::move_to_storage(machine, kind, id.as_ptr(), len(id.as_bytes()), limit.into())
        };
    }

    pub fn move_from_storage(machine: i32, resource: Resource<'_>, limit: u32) {
        let id = resource.id();
        let kind = resource.kind() as i32;
        unsafe {
            host::move_from_storage(machine, kind, id.as_ptr(), len(id.as_bytes()), limit.into())
        };
    }

    pub fn craft(item: &str, amount: u32) {
        unsafe { host::craft(item.as_ptr(), len(item.as_bytes()), amount.into()) };
    }

    pub fn stock(resource: Resource<'_>) -> u64 {
        let id = resource.id();
        let available =
            unsafe { host::stock(resource.kind() as i32, id.as_ptr(), len(id.as_bytes())) };
        available.max(0) as u64
    }

    pub fn log(text: &str) {
        unsafe { host::log(text.as_ptr(), len(text.as_bytes())) };
    }

    pub fn state_read(buffer: &mut [u8]) -> Option<usize> {
        let full = unsafe { host::state_read(buffer.as_mut_ptr(), len(buffer)) };
        usize::try_from(full).ok()
    }

    pub fn state_write(data: &[u8]) {
        unsafe { host::state_write(data.as_ptr(), len(data)) };
    }

    pub fn with_access(machine: i32, side: i32, first: i32, last: i32) -> i32 {
        unsafe { host::with_access(machine, side, first, last) }
    }

    pub fn move2(from: i32, to: i32, resource: Resource<'_>, limit: u32) {
        let id = resource.id();
        let kind = resource.kind() as i32;
        unsafe {
            host::move2(
                from,
                to,
                kind,
                id.as_ptr(),
                len(id.as_bytes()),
                limit.into(),
            )
        };
    }

    pub fn has(machine: i32, resource: Resource<'_>) -> u64 {
        let id = resource.id();
        let held = unsafe {
            host::has(
                machine,
                resource.kind() as i32,
                id.as_ptr(),
                len(id.as_bytes()),
            )
        };
        held.max(0) as u64
    }

    pub fn redstone_in(machine: i32) -> bool {
        unsafe { host::redstone_in(machine) != 0 }
    }

    pub fn emit(label: &str, strength: u8) {
        unsafe { host::emit(label.as_ptr(), len(label.as_bytes()), strength.into()) };
    }

    pub fn panic(message: &str) -> ! {
        unsafe { host::panic(message.as_ptr(), len(message.as_bytes())) };
        core::arch::wasm32::unreachable()
    }
}

#[cfg(target_arch = "wasm32")]
pub use real::*;

#[cfg(not(target_arch = "wasm32"))]
pub use crate::testing::host::*;
