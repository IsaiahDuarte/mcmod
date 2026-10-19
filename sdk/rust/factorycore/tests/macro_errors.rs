//! Compile-time diagnostics of `#[factorycore::program]`. Regenerate expected output with
//! `TRYBUILD=overwrite cargo test -p factorycore --test macro_errors` and review the diff.

#[test]
fn program_macro_diagnostics() {
    let cases = trybuild::TestCases::new();
    cases.pass("tests/ui/pass/*.rs");
    cases.compile_fail("tests/ui/fail/*.rs");
}
