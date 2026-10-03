# Minecraft Storage and Automation

A mod in design: unified item/fluid/energy storage, a crafting terminal, segmented factories, recipe-based autocrafting, and Rust/WebAssembly automation alongside a friendly language. Progression starts with wired storage and adds bounded wireless access/links and infinite item capacity.

Status: specification and contributor tooling only. Minecraft version, loader, build system, and Wasm runtime are not selected. No playable mod exists yet.

## Start here

- [Product specification](SPEC.md): authoritative release scope, behavior, and acceptance criteria.
- [Implementation handoff](docs/IMPLEMENTATION.md): ordered stages, decision gates, evidence tracking, and an instruction to give an implementing AI.
- [AI contributor instructions](AGENTS.md): required workflow and completion criteria.
- [Engineering rules](docs/ENGINEERING.md): architecture, documentation, and change discipline.
- [Verification requirements](docs/TESTING.md): current checks and required implementation gates.
- [Architecture decisions](docs/decisions/README.md): how to record significant choices.
- [Review findings](docs/REVIEW.md): contradictions corrected and remaining decision gates.
- [LogisticsNetworks comparison](docs/LOGISTICSNETWORKS.md): revision-specific overlap and design implications.

## Verify this repository

Requires Python 3.11 or later, using only its standard library:

```text
python scripts/verify.py
```

This canonical check validates documentation structure, local file links, acceptance-ID tracking, and tooling tests (failing on empty test collection). It rejects JVM/Rust implementation source until real build/test gates are configured. It does not validate a mod implementation or prove the design correct.

The [GitHub Actions workflow](.github/workflows/quality.yml) runs this command on Windows and Linux once this repository is hosted on GitHub. Hosting and required merge checks are not configured in this local workspace.
