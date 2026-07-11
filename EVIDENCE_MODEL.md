# Code Atlas — Evidence & Resolution Model

Code Atlas is an evidence-first platform: it distinguishes what it **found** from
what it **resolved**, **inferred**, or **could not resolve**, and it never presents
a guess as a confirmed fact. This document describes the model as it exists today
and the direction it is evolving toward.

## Source evidence

Every code entity (and many relationships) carries a `SourceLocation`:

| Field | Meaning |
|---|---|
| `filePath` | repository-relative path (forward slashes) |
| `startLine`, `endLine` | 1-based line range (`0` = unknown) |
| `startColumn`, `endColumn` | 1-based columns (`0` = unknown) |

Characterization tests assert that every code entity (excluding the synthetic
`PROJECT` root and the logical `PACKAGE` aggregate) carries a location with a
non-blank file path, so every finding is traceable back to source.

Additional provenance already captured:

- **File content hash** (SHA-256) on `FILE` entities and in the index, enabling
  incremental change detection.
- **Parser id** on each `ParseResult` (`parserId()`), identifying which parser
  produced an extraction.

## Resolution status

`ResolutionStatus` makes uncertainty explicit on every relationship:

| Status | Meaning |
|---|---|
| `DISCOVERED` | Explicitly present in source/config/build — a fact (e.g. structural `CONTAINS`). |
| `RESOLVED` | Connected through deterministic symbol resolution (the Linker matched a concrete target). |
| `INFERRED` | Suggested by naming/convention/architecture rules (reserved for future inference passes). |
| `UNRESOLVED` | A reference was detected but its target could not be confidently identified. |

For backward compatibility, status is **derived** from the existing `resolved`
flag and relationship kind when not set explicitly:

- unresolved edge → `UNRESOLVED`
- resolved `CONTAINS` → `DISCOVERED`
- any other resolved edge → `RESOLVED`

Parsers emit cross-file references as `UNRESOLVED` with a symbolic target name plus
attributes (`callName`, `argCount`, `typeName`). The **Linker** runs after all
files are parsed and, where it finds concrete targets, adds `RESOLVED` edges. It is
deliberately conservative: ambiguous names (too many candidates) are left
unresolved rather than guessed, and unresolved references are **kept, not
discarded**.

## Analysis coverage

Because resolution is best-effort on arbitrary offline repositories, every scan
reports how much it actually understood (`AnalysisCoverage`):

- files discovered / analyzed / skipped (no parser) / failed / unreadable,
- distinct unsupported file types,
- references resolved / unresolved / ambiguous, and a resolution-rate percentage.

A scan is labelled **PARTIAL** whenever anything was skipped or failed, or any
reference remained unresolved or ambiguous. This label appears in the CLI summary,
a banner at the top of the HTML report, and as `scanStatus` in the JSON. Users are
never allowed to mistake incomplete analysis for complete coverage.

## Dead-code confidence

Dead-code findings are **probable, never absolute**. Each candidate lists the
evidence checks that passed and a confidence score derived from explicit rules
(visibility, exposure, presence of a same-named unresolved call), capped below
100%, and always recommending review before removal.

## Roadmap for evidence

Planned enrichment (not yet implemented) per the enhancement addendum:

- Attach structured evidence (parser id + version, analysis rule id, file hash,
  scan version, confidence, resolution status) to every important finding.
- Add an `INFERRED` inference pass (naming/convention/architecture rules), clearly
  labelled and never conflated with resolved facts.
- Stable, location-independent identifiers so evidence survives incremental scans.
