//! Rust SDK for Factory Core `factory_v1` programs (ADR 0037).
//!
//! A program exports `factory_init`, which registers persistent rules with [`register`], and
//! `factory_rule(index)`, which runs one rule. Inside a rule, calls such as
//! [`Machine::keep_stocked`] only *stage* commands: the server publishes them after the callback
//! returns and discards them all if the callback traps or exceeds a limit.
#![cfg_attr(all(not(test), target_arch = "wasm32"), no_std)]

/// A resource selector: registry IDs default to the `minecraft:` namespace.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Resource<'a> {
    /// Items with this registry ID (any components).
    Item(&'a str),
    /// Fluids with this registry ID.
    Fluid(&'a str),
    /// Items in this item tag.
    Tag(&'a str),
    /// FE.
    Energy,
}

impl Resource<'_> {
    /// `(kind, id, tag)` as encoded by the `factory_v1` ABI.
    pub fn encode(&self) -> (i32, &str, i32) {
        match self {
            Resource::Item(id) => (0, id, 0),
            Resource::Fluid(id) => (1, id, 0),
            Resource::Tag(id) => (0, id, 1),
            Resource::Energy => (2, "", 0),
        }
    }
}

/// Splits the packed `(base << 32) | count` result of `machines`.
pub fn unpack_selection(packed: i64) -> (i32, i32) {
    ((packed >> 32) as i32, (packed & 0xffff_ffff) as i32)
}

#[cfg(target_arch = "wasm32")]
mod host {
    #[link(wasm_import_module = "factory_v1")]
    extern "C" {
        pub fn register_rule(key: *const u8, key_len: i32, interval: i32) -> i32;
        pub fn machines(label: *const u8, label_len: i32) -> i64;
        pub fn keep(
            handle: i32,
            kind: i32,
            id: *const u8,
            id_len: i32,
            tag: i32,
            amount: i64,
        ) -> i32;
        pub fn move_from(
            handle: i32,
            kind: i32,
            id: *const u8,
            id_len: i32,
            tag: i32,
            limit: i64,
        ) -> i32;
        pub fn move_to(
            handle: i32,
            kind: i32,
            id: *const u8,
            id_len: i32,
            tag: i32,
            limit: i64,
        ) -> i32;
        pub fn craft(id: *const u8, id_len: i32, amount: i64) -> i32;
        pub fn stock(kind: i32, id: *const u8, id_len: i32, tag: i32) -> i64;
        pub fn log(text: *const u8, text_len: i32) -> i32;
    }
}

/// Registers a persistent rule (only during `factory_init`); returns its index.
#[cfg(target_arch = "wasm32")]
pub fn register(key: &str, interval_ticks: i32) -> i32 {
    unsafe { host::register_rule(key.as_ptr(), key.len() as i32, interval_ticks) }
}

/// One machine interface selected by label during this rule invocation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Machine(pub i32);

/// Machines with a local label, in stable order (at most 64).
#[cfg(target_arch = "wasm32")]
pub fn machines(label: &str) -> impl Iterator<Item = Machine> {
    let (base, count) =
        unpack_selection(unsafe { host::machines(label.as_ptr(), label.len() as i32) });
    (base..base + count).map(Machine)
}

#[cfg(target_arch = "wasm32")]
impl Machine {
    /// Requests the deficit so this machine holds `amount` matching units.
    pub fn keep_stocked(self, resource: Resource<'_>, amount: i64) {
        let (kind, id, tag) = resource.encode();
        unsafe { host::keep(self.0, kind, id.as_ptr(), id.len() as i32, tag, amount) };
    }

    /// Moves at most `limit` matching units from this machine to storage.
    pub fn move_to_storage(self, resource: Resource<'_>, limit: i64) {
        let (kind, id, tag) = resource.encode();
        unsafe { host::move_from(self.0, kind, id.as_ptr(), id.len() as i32, tag, limit) };
    }

    /// Moves at most `limit` matching units from storage to this machine.
    pub fn move_from_storage(self, resource: Resource<'_>, limit: i64) {
        let (kind, id, tag) = resource.encode();
        unsafe { host::move_to(self.0, kind, id.as_ptr(), id.len() as i32, tag, limit) };
    }
}

/// Requests crafting of `amount` units of an item (deduplicated per rule by the server).
#[cfg(target_arch = "wasm32")]
pub fn craft(item: &str, amount: i64) {
    unsafe { host::craft(item.as_ptr(), item.len() as i32, amount) };
}

/// Available matching units in storage (requires the deployer's View permission).
#[cfg(target_arch = "wasm32")]
pub fn stock(resource: Resource<'_>) -> i64 {
    let (kind, id, tag) = resource.encode();
    unsafe { host::stock(kind, id.as_ptr(), id.len() as i32, tag) }
}

/// Appends a bounded log line (at most 128 bytes).
#[cfg(target_arch = "wasm32")]
pub fn log(text: &str) {
    unsafe { host::log(text.as_ptr(), text.len() as i32) };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resources_encode_to_the_abi_kinds() {
        assert_eq!(Resource::Item("raw_iron").encode(), (0, "raw_iron", 0));
        assert_eq!(Resource::Fluid("water").encode(), (1, "water", 0));
        assert_eq!(Resource::Tag("logs").encode(), (0, "logs", 1));
        assert_eq!(Resource::Energy.encode(), (2, "", 0));
    }

    #[test]
    fn selections_unpack_base_and_count() {
        assert_eq!(unpack_selection((3_i64 << 32) | 2), (3, 2));
        assert_eq!(unpack_selection(0), (0, 0));
    }
}
