use factorycore::prelude::*;

#[factorycore::program]
mod program {
    use super::*;

    #[rule(seconds = 1)]
    fn wrong_type(_cx: u32) {}

    #[rule(seconds = 2)]
    fn owned(_cx: Rule) {}
}

fn main() {}
