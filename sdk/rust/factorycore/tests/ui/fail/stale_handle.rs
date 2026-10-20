use factorycore::prelude::*;

static mut KEPT: Option<Machine<'static>> = None;

#[factorycore::program]
mod program {
    use super::*;

    #[rule(seconds = 1)]
    fn leak(cx: &Rule) {
        let machine = cx.machines("in").next().unwrap();
        unsafe { KEPT = Some(machine) };
    }
}

fn main() {}
