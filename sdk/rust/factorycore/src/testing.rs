//! A deterministic in-process host for testing programs with `cargo test` (native targets only).
//!
//! [`World`] holds fake storage stock, labeled machines and persistent state. [`World::run`]
//! registers the program's rules like `factory_init` and runs one rule like `factory_rule`,
//! returning the staged [`Command`]s. The mock enforces the same per-rule limits and trap
//! conditions as the server (text length, host calls, staged commands, machines per label,
//! amounts, registry IDs, state size, phase rules); a violation becomes a [`Trap`] that
//! discards the rule's commands and state write.
//!
//! Not modeled: the instruction budget and FE cost, permissions, and what the server later
//! does with the commands (transfers, crafting). The mock leaks the short strings it records so
//! commands can be compared with [`Resource`] values directly.
//!
//! ```
//! use factorycore::prelude::*;
//! use factorycore::testing::World;
//!
//! #[factorycore::program]
//! mod program {
//!     use super::*;
//!
//!     #[rule(seconds = 5)]
//!     fn planks(cx: &Rule) {
//!         if cx.stock(item("oak_planks")) < 32 {
//!             cx.craft(item("oak_planks"), 16);
//!         }
//!     }
//! }
//!
//! # fn main() {
//! let outcome = World::new().stock(item("oak_planks"), 10).run(program::RULES, "planks");
//! assert_eq!(outcome.crafts(), [(item("oak_planks"), 16)]);
//! # }
//! ```

use core::fmt;
use std::boxed::Box;
use std::cell::RefCell;
use std::format;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::string::{String, ToString};
use std::vec::Vec;

use crate::{MAX_STATE, MAX_TEXT, Resource, ResourceKind, RuleDef};

const MAX_HOST_CALLS: u32 = 512;
const MAX_COMMANDS: usize = 256;
const MAX_MACHINES_PER_LABEL: usize = 64;
const MAX_HANDLES: usize = 1_024;
const MAX_RULES: usize = 64;
const MAX_AMOUNT: u32 = 1 << 20;
const MAX_MOVE: u32 = 4_096;

/// A machine selected by label: the `index`-th machine with that label.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct MachineRef {
    pub label: &'static str,
    pub index: usize,
}

/// A command staged by a rule, as the server would receive it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Command {
    KeepStocked {
        machine: MachineRef,
        resource: Resource<'static>,
        amount: u32,
    },
    MoveToStorage {
        machine: MachineRef,
        resource: Resource<'static>,
        limit: u32,
    },
    MoveFromStorage {
        machine: MachineRef,
        resource: Resource<'static>,
        limit: u32,
    },
    Craft {
        item: Resource<'static>,
        amount: u32,
    },
    Log(&'static str),
}

/// The result of one successful rule run.
#[derive(Clone, Debug)]
pub struct Outcome {
    commands: Vec<Command>,
    state_changed: bool,
}

impl Outcome {
    /// Every staged command, in order.
    pub fn commands(&self) -> &[Command] {
        &self.commands
    }

    /// Staged crafts as `(item, amount)`.
    pub fn crafts(&self) -> Vec<(Resource<'static>, u32)> {
        self.commands
            .iter()
            .filter_map(|command| match *command {
                Command::Craft { item, amount } => Some((item, amount)),
                _ => None,
            })
            .collect()
    }

    /// Staged log lines.
    pub fn logs(&self) -> Vec<&'static str> {
        self.commands
            .iter()
            .filter_map(|command| match *command {
                Command::Log(text) => Some(text),
                _ => None,
            })
            .collect()
    }

    /// Whether the rule saved state (now committed in the [`World`]).
    pub fn state_changed(&self) -> bool {
        self.state_changed
    }
}

/// Why a rule (or the program's initialization) trapped.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Trap(String);

impl Trap {
    pub fn message(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for Trap {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// Fake network storage, labeled machines and persistent state for running rules.
#[derive(Clone, Debug, Default)]
pub struct World {
    stock: Vec<(Resource<'static>, u64)>,
    labels: Vec<(&'static str, usize)>,
    state: Option<Vec<u8>>,
}

impl World {
    /// An empty world: no stock, no machines, no state.
    pub fn new() -> Self {
        Self::default()
    }

    /// Sets the available amount of `resource` (replacing an earlier amount).
    pub fn stock(mut self, resource: Resource<'_>, amount: u64) -> Self {
        let resource = leak_resource(resource);
        self.stock.retain(|(known, _)| *known != resource);
        self.stock.push((resource, amount));
        self
    }

    /// Adds `count` machines with this label (replacing an earlier count).
    pub fn machines(mut self, label: &str, count: usize) -> Self {
        self.labels.retain(|(known, _)| *known != label);
        self.labels.push((leak(label), count));
        self
    }

    /// Sets the committed persistent state, as if restored after a reload.
    pub fn state(mut self, bytes: &[u8]) -> Self {
        self.state = Some(bytes.to_vec());
        self
    }

    /// The committed persistent state, or `None` if none was ever saved.
    pub fn saved_state(&self) -> Option<&[u8]> {
        self.state.as_deref()
    }

    /// Initializes the program and runs the rule with `key`.
    ///
    /// # Panics
    /// If the rule traps (use [`World::try_run`] to test traps) or no rule has that key.
    pub fn run(&mut self, rules: &[RuleDef], key: &str) -> Outcome {
        self.try_run(rules, key)
            .unwrap_or_else(|trap| panic!("rule \"{key}\" trapped: {trap}"))
    }

    /// Initializes the program and runs the rule with `key`. A trap discards the rule's
    /// commands and leaves the committed state unchanged.
    ///
    /// # Panics
    /// If no rule has that key.
    pub fn try_run(&mut self, rules: &[RuleDef], key: &str) -> Result<Outcome, Trap> {
        let Some(index) = rules.iter().position(|rule| rule.key == key) else {
            let keys: Vec<_> = rules.iter().map(|rule| rule.key).collect();
            panic!("no rule \"{key}\"; the program has {keys:?}");
        };
        let session = Session {
            phase: Phase::Init,
            stock: self.stock.clone(),
            labels: self.labels.clone(),
            committed: self.state.clone(),
            pending: None,
            registered: Vec::new(),
            handles: Vec::new(),
            commands: Vec::new(),
            host_calls: 0,
        };
        HOST.with(|host| *host.borrow_mut() = Some(session));
        let result = guarded(|| crate::__private::init(rules)).and_then(|()| {
            with_session(|session| {
                if session.registered.is_empty() {
                    trap("guest registered no rules");
                }
                session.phase = Phase::Rule;
                session.host_calls = 0;
            });
            guarded(|| crate::__private::dispatch(rules, index as i32))
        });
        let session = HOST
            .with(|host| host.borrow_mut().take())
            .expect("the session outlives the run");
        result.map(|()| {
            let state_changed = session.pending.is_some();
            if let Some(pending) = session.pending {
                self.state = Some(pending);
            }
            Outcome {
                commands: session.commands,
                state_changed,
            }
        })
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    Init,
    Rule,
}

struct Session {
    phase: Phase,
    stock: Vec<(Resource<'static>, u64)>,
    labels: Vec<(&'static str, usize)>,
    committed: Option<Vec<u8>>,
    pending: Option<Vec<u8>>,
    registered: Vec<&'static str>,
    handles: Vec<MachineRef>,
    commands: Vec<Command>,
    host_calls: u32,
}

std::thread_local! {
    static HOST: RefCell<Option<Session>> = const { RefCell::new(None) };
}

fn guarded(body: impl FnOnce()) -> Result<(), Trap> {
    catch_unwind(AssertUnwindSafe(body)).map_err(|payload| {
        Trap(
            payload
                .downcast_ref::<String>()
                .cloned()
                .or_else(|| payload.downcast_ref::<&str>().map(|s| s.to_string()))
                .unwrap_or_else(|| "guest panicked".to_string()),
        )
    })
}

fn trap(message: impl fmt::Display) -> ! {
    panic!("{message}")
}

fn leak(text: &str) -> &'static str {
    Box::leak(text.to_string().into_boxed_str())
}

fn leak_resource(resource: Resource<'_>) -> Resource<'static> {
    let id = leak(resource.id());
    match resource.kind() {
        ResourceKind::Item => crate::item(id),
        ResourceKind::Fluid => crate::fluid(id),
        ResourceKind::ItemTag => crate::item_tag(id),
        ResourceKind::Energy => crate::ENERGY,
    }
}

/// Runs `body` on the active session, counting one host call.
fn with_session<R>(body: impl FnOnce(&mut Session) -> R) -> R {
    HOST.with(|host| {
        let mut host = host.borrow_mut();
        let session = host
            .as_mut()
            .expect("factorycore host calls only work inside testing::World::run");
        session.host_calls += 1;
        if session.host_calls > MAX_HOST_CALLS {
            trap(format!("more than {MAX_HOST_CALLS} host calls"));
        }
        body(session)
    })
}

impl Session {
    fn require_rule(&self) {
        if self.phase != Phase::Rule {
            trap("host call outside a rule callback");
        }
    }

    fn stage(&mut self, command: Command) {
        if self.commands.len() == MAX_COMMANDS {
            trap(format!("more than {MAX_COMMANDS} commands"));
        }
        self.commands.push(command);
    }

    fn machine(&self, handle: i32) -> MachineRef {
        usize::try_from(handle)
            .ok()
            .and_then(|handle| self.handles.get(handle).copied())
            .unwrap_or_else(|| trap("unknown machine handle"))
    }
}

fn text(text: &str) -> &str {
    if text.len() > MAX_TEXT {
        trap(format!("text longer than {MAX_TEXT} bytes"));
    }
    text
}

/// Checks a registry ID like Minecraft's resource locations (default namespace `minecraft`).
fn resource(resource: Resource<'_>) -> Resource<'static> {
    text(resource.id());
    if resource.kind() != ResourceKind::Energy {
        let (namespace, path) = resource.namespaced();
        let valid = |part: &str, slash: bool| {
            !part.is_empty()
                && part.bytes().all(|b| {
                    b.is_ascii_lowercase()
                        || b.is_ascii_digit()
                        || matches!(b, b'_' | b'-' | b'.')
                        || (slash && b == b'/')
                })
        };
        if !valid(namespace, false) || !valid(path, true) {
            trap(format!("invalid registry ID {}", resource.id()));
        }
    }
    leak_resource(resource)
}

fn amount(amount: u32, max: u32) -> u32 {
    if amount == 0 || amount > max {
        trap("amount out of range");
    }
    amount
}

/// Mock implementations of the functions in `crate::sys`.
pub(crate) mod host {
    use super::*;

    pub fn register_rule(key: &str, interval: u32) -> i32 {
        with_session(|session| {
            if session.phase != Phase::Init {
                trap("register_rule outside factory_init");
            }
            let key = text(key);
            if key.is_empty()
                || key.encode_utf16().count() > 64
                || session.registered.contains(&key)
            {
                trap("invalid or duplicate rule key");
            }
            if !(10..=72_000).contains(&interval) {
                trap("rule interval outside 10..72000 ticks");
            }
            if session.registered.len() == MAX_RULES {
                trap(format!("more than {MAX_RULES} rules"));
            }
            session.registered.push(leak(key));
            session.registered.len() as i32 - 1
        })
    }

    pub fn machines(label: &str) -> (i32, i32) {
        with_session(|session| {
            session.require_rule();
            let label = text(label);
            let count = session
                .labels
                .iter()
                .find(|(known, _)| *known == label)
                .map_or(0, |(_, count)| *count);
            if count > MAX_MACHINES_PER_LABEL {
                trap(format!(
                    "more than {MAX_MACHINES_PER_LABEL} machines labeled {label}"
                ));
            }
            if session.handles.len() + count > MAX_HANDLES {
                trap("too many machine handles");
            }
            let base = session.handles.len();
            let label = leak(label);
            session
                .handles
                .extend((0..count).map(|index| MachineRef { label, index }));
            (base as i32, count as i32)
        })
    }

    pub fn keep_stocked(machine: i32, target: Resource<'_>, amount: u32) {
        with_session(|session| {
            session.require_rule();
            let machine = session.machine(machine);
            let resource = resource(target);
            let amount = super::amount(amount, MAX_AMOUNT);
            session.stage(Command::KeepStocked {
                machine,
                resource,
                amount,
            });
        })
    }

    pub fn move_to_storage(machine: i32, target: Resource<'_>, limit: u32) {
        with_session(|session| {
            session.require_rule();
            let machine = session.machine(machine);
            let resource = resource(target);
            let limit = amount(limit, MAX_MOVE);
            session.stage(Command::MoveToStorage {
                machine,
                resource,
                limit,
            });
        })
    }

    pub fn move_from_storage(machine: i32, target: Resource<'_>, limit: u32) {
        with_session(|session| {
            session.require_rule();
            let machine = session.machine(machine);
            let resource = resource(target);
            let limit = amount(limit, MAX_MOVE);
            session.stage(Command::MoveFromStorage {
                machine,
                resource,
                limit,
            });
        })
    }

    pub fn craft(item: &str, amount: u32) {
        with_session(|session| {
            session.require_rule();
            let item = resource(crate::item(item));
            let amount = super::amount(amount, MAX_AMOUNT);
            session.stage(Command::Craft { item, amount });
        })
    }

    pub fn stock(target: Resource<'_>) -> u64 {
        with_session(|session| {
            session.require_rule();
            let target = resource(target);
            session
                .stock
                .iter()
                .filter(|(known, _)| *known == target)
                .map(|(_, amount)| *amount)
                .sum()
        })
    }

    pub fn log(line: &str) {
        with_session(|session| {
            session.require_rule();
            let line = leak(text(line));
            session.stage(Command::Log(line));
        })
    }

    pub fn state_read(buffer: &mut [u8]) -> Option<usize> {
        with_session(|session| {
            let current = session.pending.as_ref().or(session.committed.as_ref())?;
            let copied = buffer.len().min(current.len());
            buffer[..copied].copy_from_slice(&current[..copied]);
            Some(current.len())
        })
    }

    pub fn state_write(data: &[u8]) {
        with_session(|session| {
            session.require_rule();
            if data.len() > MAX_STATE {
                trap(format!("state over {MAX_STATE} bytes"));
            }
            session.pending = Some(data.to_vec());
        })
    }

    pub fn panic(message: &str) -> ! {
        trap(format!("guest panicked: {message}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Rule, item};

    fn rules(run: fn(&Rule)) -> [RuleDef; 1] {
        [RuleDef {
            key: "rule",
            interval_ticks: 20,
            run,
        }]
    }

    #[test]
    fn machines_get_per_label_handles_in_order() {
        let outcome = World::new().machines("in", 2).machines("out", 1).run(
            &rules(|cx| {
                for machine in cx.machines("in").chain(cx.machines("out")) {
                    machine.move_from_storage(item("coal"), 8);
                }
                assert_eq!(cx.machines("missing").len(), 0);
            }),
            "rule",
        );
        let targets: Vec<_> = outcome
            .commands()
            .iter()
            .map(|command| match command {
                Command::MoveFromStorage { machine, .. } => (machine.label, machine.index),
                other => panic!("unexpected {other:?}"),
            })
            .collect();
        assert_eq!(targets, [("in", 0), ("in", 1), ("out", 0)]);
    }

    #[test]
    fn a_trap_discards_commands_and_the_state_write() {
        let mut world = World::new().state(b"old");
        let trap = world
            .try_run(
                &rules(|cx| {
                    cx.log("staged");
                    cx.save_state(b"new");
                    cx.craft(item("NOT VALID"), 1);
                }),
                "rule",
            )
            .unwrap_err();
        assert_eq!(trap.message(), "invalid registry ID NOT VALID");
        assert_eq!(world.saved_state(), Some(&b"old"[..]));
    }

    #[test]
    fn limits_match_the_server() {
        let mut world = World::new().machines("many", 65);
        type Case = (fn(&Rule), &'static str);
        let cases: [Case; 6] = [
            (
                |cx| {
                    cx.machines("many");
                },
                "more than 64 machines labeled many",
            ),
            (
                |cx| cx.save_state(&[0; MAX_STATE + 1]),
                "state over 4096 bytes",
            ),
            (
                |cx| {
                    cx.stock(item(&"x".repeat(129)));
                },
                "text longer than 128 bytes",
            ),
            (
                |cx| (0..257).for_each(|_| cx.log("x")),
                "more than 256 commands",
            ),
            (
                |cx| {
                    (0..513).for_each(|_| {
                        cx.stock(item("dirt"));
                    })
                },
                "more than 512 host calls",
            ),
            (|cx| cx.craft(item("dirt"), 0), "amount out of range"),
        ];
        for (run, message) in cases {
            assert_eq!(
                world.try_run(&rules(run), "rule").unwrap_err().message(),
                message
            );
        }
    }

    #[test]
    fn state_is_read_back_within_a_rule_and_committed_after_it() {
        let mut world = World::new();
        let outcome = world.run(
            &rules(|cx| {
                assert_eq!(cx.load_state(&mut [0; 4]), None);
                cx.save_state(&[1, 2, 3]);
                let mut buffer = [0; 2];
                assert_eq!(cx.load_state(&mut buffer), Some(3));
                assert_eq!(buffer, [1, 2]);
            }),
            "rule",
        );
        assert!(outcome.state_changed());
        assert_eq!(world.saved_state(), Some(&[1, 2, 3][..]));
    }

    #[test]
    fn invalid_registrations_trap_at_initialization() {
        let duplicate = [rules(|_| {})[0], rules(|_| {})[0]];
        let trap = World::new().try_run(&duplicate, "rule").unwrap_err();
        assert_eq!(trap.message(), "invalid or duplicate rule key");
        let mut fast = rules(|_| {});
        fast[0].interval_ticks = 5;
        let trap = World::new().try_run(&fast, "rule").unwrap_err();
        assert_eq!(trap.message(), "rule interval outside 10..72000 ticks");
    }
}
