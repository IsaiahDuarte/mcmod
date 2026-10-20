use factorycore::prelude::*;

#[factorycore::program]
mod program {
    use super::*;

    #[rule(seconds = 1)]
    fn no_context() {}

    #[rule(seconds = 1)]
    async fn waits(_cx: &Rule) {}

    #[rule(seconds = 1)]
    #[rule(seconds = 2)]
    fn twice(_cx: &Rule) {}
}

fn main() {}
