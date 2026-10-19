use factorycore::prelude::*;

#[factorycore::program]
mod program {
    use super::*;

    #[rule(seconds = 1)]
    fn tick(_cx: &Rule) {}

    #[rule(seconds = 2, key = "tick")]
    fn again(_cx: &Rule) {}

    #[rule(seconds = 3, key = "")]
    fn empty(_cx: &Rule) {}
}

fn main() {}
