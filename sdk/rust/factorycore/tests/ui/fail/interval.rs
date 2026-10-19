use factorycore::prelude::*;

#[factorycore::program]
mod program {
    use super::*;

    #[rule(ticks = 5)]
    fn too_fast(_cx: &Rule) {}

    #[rule(minutes = 61)]
    fn too_slow(_cx: &Rule) {}

    #[rule(key = "none")]
    fn missing(_cx: &Rule) {}

    #[rule]
    fn bare(_cx: &Rule) {}

    #[rule(ticks = 20, seconds = 1)]
    fn two(_cx: &Rule) {}

    #[rule(hours = 1)]
    fn unknown(_cx: &Rule) {}
}

fn main() {}
