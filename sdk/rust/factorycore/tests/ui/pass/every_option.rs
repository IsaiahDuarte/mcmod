use factorycore::prelude::*;

#[factorycore::program]
mod program {
    use super::*;

    #[rule(ticks = 10)]
    fn fastest(_cx: &Rule) {}

    #[rule(minutes = 60, key = "slowest rule")]
    fn r#slowest(cx: &Rule) {
        log!(cx, "{}", cx.stock(ENERGY));
    }

    #[rule(seconds = 2)]
    fn r#type(_cx: &Rule) {}

    pub fn helper() {}
}

fn main() {
    let keys: Vec<_> = program::RULES.iter().map(|r| (r.key, r.interval_ticks)).collect();
    assert_eq!(keys, [("fastest", 10), ("slowest rule", 72_000), ("type", 40)]);
    program::helper();
}
