//! Procedural macros for the Factory Core Rust SDK. Use them through `factorycore`
//! (`#[factorycore::program]`); this crate's output refers to `::factorycore` paths.
//!
//! The host limits checked here mirror `WasmProgram.register` and `ProgramIr` on the JVM side:
//! at most 64 rules, keys of 1..=64 UTF-16 units and 1..=128 bytes, intervals of 10..=72,000
//! ticks. The host still enforces them at deployment.

use proc_macro::TokenStream;
use proc_macro2::Span;
use quote::{format_ident, quote};
use syn::spanned::Spanned;
use syn::{Error, FnArg, Item, ItemFn, ItemMod, LitInt, LitStr, parse_macro_input};

const MAX_RULES: usize = 64;
const MIN_INTERVAL: u64 = 10;
const MAX_INTERVAL: u64 = 72_000;
const MAX_KEY_UNITS: usize = 64;
const MAX_KEY_BYTES: usize = 128;

/// Turns an inline module into a Factory Core program.
///
/// Every `fn name(cx: &Rule)` marked `#[rule(...)]` becomes a persistent rule, registered in
/// source order. Exactly one interval is required: `ticks = N`, `seconds = N` (20 ticks) or
/// `minutes = N` (1,200 ticks), within 10..=72,000 ticks. `key = "..."` overrides the rule key,
/// which defaults to the function name.
///
/// The module gains `pub const RULES: &[factorycore::RuleDef]` (for `factorycore::testing`) and,
/// on wasm32, the `factory_init`/`factory_rule` exports. A crate holds at most one program.
#[proc_macro_attribute]
pub fn program(attribute: TokenStream, item: TokenStream) -> TokenStream {
    if !attribute.is_empty() {
        return Error::new(
            proc_macro2::TokenStream::from(attribute).span(),
            "#[program] takes no arguments; put intervals on each #[rule(...)]",
        )
        .into_compile_error()
        .into();
    }
    let module = parse_macro_input!(item as ItemMod);
    match expand(module) {
        Ok(tokens) => tokens.into(),
        Err(error) => error.into_compile_error().into(),
    }
}

/// Marks a rule inside a `#[factorycore::program]` module; see [`macro@program`].
#[proc_macro_attribute]
pub fn rule(_attribute: TokenStream, item: TokenStream) -> TokenStream {
    let mut tokens = Error::new(
        Span::call_site(),
        "#[rule] only works on functions inside a #[factorycore::program] module",
    )
    .into_compile_error();
    tokens.extend(proc_macro2::TokenStream::from(item));
    tokens.into()
}

struct RuleSpec {
    function: syn::Ident,
    key: String,
    key_span: Span,
    interval: u64,
}

fn expand(mut module: ItemMod) -> syn::Result<proc_macro2::TokenStream> {
    let Some((_, items)) = module.content.as_mut() else {
        return Err(Error::new(
            module.span(),
            "#[program] needs an inline module: `mod program { ... }`",
        ));
    };
    let mut rules: Vec<RuleSpec> = Vec::new();
    let mut errors: Option<Error> = None;
    for item in items.iter_mut() {
        let Item::Fn(function) = item else { continue };
        let Some(position) = function
            .attrs
            .iter()
            .position(|a| a.path().is_ident("rule"))
        else {
            continue;
        };
        let attribute = function.attrs.remove(position);
        if let Some(extra) = function.attrs.iter().find(|a| a.path().is_ident("rule")) {
            push(
                &mut errors,
                Error::new(extra.path().span(), "a function can only be one rule"),
            );
            function.attrs.retain(|a| !a.path().is_ident("rule"));
        }
        match parse_rule(&attribute, function) {
            Ok(rule) => {
                if let Some(first) = rules.iter().find(|r| r.key == rule.key) {
                    push(
                        &mut errors,
                        Error::new(
                            rule.key_span,
                            format!(
                                "rule key \"{}\" is already used by `{}`",
                                rule.key, first.function
                            ),
                        ),
                    );
                } else if rules.len() == MAX_RULES {
                    push(
                        &mut errors,
                        Error::new(
                            rule.function.span(),
                            format!("a program can register at most {MAX_RULES} rules"),
                        ),
                    );
                } else {
                    rules.push(rule);
                }
            }
            Err(error) => push(&mut errors, error),
        }
    }
    if rules.is_empty() && errors.is_none() {
        push(
            &mut errors,
            Error::new(
                module.ident.span(),
                "a program needs at least one rule, e.g. `#[rule(seconds = 1)] fn tick(cx: &Rule) {}`",
            ),
        );
    }
    if let Some(errors) = errors {
        // Keep the module (with `#[rule]` removed) so only the real mistakes are reported.
        let errors = errors.into_compile_error();
        return Ok(quote! { #errors #module });
    }

    let entries = rules.iter().map(|rule| {
        let RuleSpec {
            function,
            key,
            interval,
            ..
        } = rule;
        let interval = interval_literal(*interval);
        quote! { ::factorycore::RuleDef { key: #key, interval_ticks: #interval, run: #function } }
    });
    // Spanned on each function name so a wrong signature is reported at the rule itself.
    let signature_checks = rules.iter().map(|rule| -> Item {
        let function = &rule.function;
        syn::parse_quote_spanned! { function.span() =>
            const _: fn(&::factorycore::Rule) = #function;
        }
    });
    let init = format_ident!("__factorycore_init");
    let dispatch = format_ident!("__factorycore_rule");
    items.extend([
        syn::parse_quote! {
            /// Rules in registration order (the index passed to `factory_rule`), generated by
            /// `#[factorycore::program]`.
            #[allow(dead_code)]
            pub const RULES: &[::factorycore::RuleDef] = &[#(#entries),*];
        },
        syn::parse_quote! {
            #[cfg(target_arch = "wasm32")]
            #[unsafe(export_name = "factory_init")]
            extern "C" fn #init() {
                ::factorycore::__private::init(RULES);
            }
        },
        syn::parse_quote! {
            #[cfg(target_arch = "wasm32")]
            #[unsafe(export_name = "factory_rule")]
            extern "C" fn #dispatch(index: i32) {
                ::factorycore::__private::dispatch(RULES, index);
            }
        },
    ]);
    items.extend(signature_checks);
    Ok(quote! { #module })
}

fn push(errors: &mut Option<Error>, error: Error) {
    match errors {
        Some(all) => all.combine(error),
        None => *errors = Some(error),
    }
}

fn interval_literal(ticks: u64) -> LitInt {
    LitInt::new(&format!("{ticks}u32"), Span::call_site())
}

fn parse_rule(attribute: &syn::Attribute, function: &ItemFn) -> syn::Result<RuleSpec> {
    check_signature(function)?;
    let mut interval: Option<(u64, Span)> = None;
    let mut key: Option<LitStr> = None;
    if matches!(attribute.meta, syn::Meta::Path(_)) {
        return Err(Error::new(
            attribute.path().span(),
            "add an interval: #[rule(ticks = N)], #[rule(seconds = N)] or #[rule(minutes = N)]",
        ));
    }
    attribute.parse_nested_meta(|meta| {
        let scale = if meta.path.is_ident("ticks") {
            Some(1)
        } else if meta.path.is_ident("seconds") {
            Some(20)
        } else if meta.path.is_ident("minutes") {
            Some(1_200)
        } else {
            None
        };
        if let Some(scale) = scale {
            let value: LitInt = meta.value()?.parse()?;
            if interval.is_some() {
                return Err(meta.error("a rule has exactly one interval"));
            }
            let ticks = value
                .base10_parse::<u64>()?
                .checked_mul(scale)
                .filter(|ticks| (MIN_INTERVAL..=MAX_INTERVAL).contains(ticks))
                .ok_or_else(|| {
                    Error::new(
                        value.span(),
                        format!(
                            "rule intervals must be {MIN_INTERVAL}..={MAX_INTERVAL} ticks \
                             (half a second to one hour)"
                        ),
                    )
                })?;
            interval = Some((ticks, value.span()));
            Ok(())
        } else if meta.path.is_ident("key") {
            if key.is_some() {
                return Err(meta.error("duplicate `key`"));
            }
            key = Some(meta.value()?.parse()?);
            Ok(())
        } else {
            Err(meta.error("expected `ticks = N`, `seconds = N`, `minutes = N` or `key = \"...\"`"))
        }
    })?;
    let Some((interval, _)) = interval else {
        return Err(Error::new(
            attribute.path().span(),
            "add an interval: `ticks = N`, `seconds = N` or `minutes = N`",
        ));
    };
    let (key, span) = match key {
        Some(literal) => (literal.value(), literal.span()),
        None => {
            let name = function.sig.ident.to_string();
            let name = name.strip_prefix("r#").unwrap_or(&name).to_owned();
            (name, function.sig.ident.span())
        }
    };
    if key.is_empty() || key.encode_utf16().count() > MAX_KEY_UNITS || key.len() > MAX_KEY_BYTES {
        return Err(Error::new(
            span,
            format!("rule keys must be 1..={MAX_KEY_UNITS} characters"),
        ));
    }
    Ok(RuleSpec {
        function: function.sig.ident.clone(),
        key,
        key_span: span,
        interval,
    })
}

fn check_signature(function: &ItemFn) -> syn::Result<()> {
    let signature = &function.sig;
    if let Some(asyncness) = signature.asyncness {
        return Err(Error::new(asyncness.span(), "rules cannot be async"));
    }
    if !signature.generics.params.is_empty() {
        return Err(Error::new(
            signature.generics.span(),
            "rules cannot be generic",
        ));
    }
    if signature.inputs.len() != 1 || matches!(signature.inputs.first(), Some(FnArg::Receiver(_))) {
        return Err(Error::new(
            signature.ident.span(),
            "rules take one argument: `fn name(cx: &Rule)`",
        ));
    }
    Ok(())
}
