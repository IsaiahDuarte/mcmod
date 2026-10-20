use factorycore::prelude::*;

#[factorycore::program]
mod empty {}

#[factorycore::program(seconds = 1)]
mod arguments {
    use super::*;

    #[rule(seconds = 1)]
    fn tick(_cx: &Rule) {}
}

#[factorycore::rule(seconds = 1)]
fn outside(_cx: &Rule) {}

fn main() {}
