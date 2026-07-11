# Code Atlas — Known Limitations

Code Atlas reports honestly. This document lists what the current implementation
does **not** do, or does only approximately, so results are read with the right
expectations. It is updated as limitations are addressed.

## Symbol resolution

- Resolution is **name-based**, not a full type-aware symbol solver. Method calls
  and type references are matched by simple name (and, for Java, argument count).
  Overloads and same-named symbols across packages may over- or under-link.
- Ambiguous references (too many candidates) are intentionally left **unresolved**
  rather than guessed. The scan's coverage numbers reflect this.
- External / library references (e.g. `System.out.println`, Ada `with Interfaces`)
  are correctly reported as **unresolved** — they are not part of the scanned repo.

## Identifiers

- Entity ids currently embed the source location, so they are **not stable** across
  edits that shift line numbers. This also causes Ada spec/body declarations to
  appear as separate entities (inflating package/subprogram counts). Stable,
  location-independent identifiers are the next planned milestone.

## Language coverage

- First-class parsers: **Java** and **Ada/SPARK** only. Other languages are
  detected (for the language-distribution stats) but **not parsed**; their files
  are counted as skipped in coverage.
- The Ada parser is a deterministic line-and-scope scanner, not a full grammar. It
  can miss constructs with unusual formatting or advanced generics, and its call
  graph is heuristic. SPARK contract capture handles common `Pre`/`Post` aspect
  forms.
- No configuration (XML/YAML/JSON/properties), database (SQL/DDL), or build-file
  (`.gpr`, Maven/Gradle) parsing yet. Build membership therefore does not inform
  dead-code or entry-point detection, which can cause false positives for code
  invoked via frameworks, reflection, DI, or serialization.

## Analysis

- **Dead code is probabilistic.** Findings are candidates with confidence scores,
  not proof. Reflection, DI, external invocation, and dynamic configuration are
  known blind spots and should be assumed possible.
- **Data lineage is not implemented.** The "data flow & consumers" report is
  component-coupling only; it does not trace data source → transform → store →
  consumer with per-edge evidence.
- Unused-package/namespace detection is disabled (it needs import-graph resolution)
  to avoid false positives.
- Complexity is standard cyclomatic complexity; it does not model cognitive
  complexity or data-flow complexity.

## Storage & performance

- The index defaults to **in-memory H2**; nothing persists across runs unless
  `--index <path>` is supplied. File-backed default and scan versioning are planned.
- Every file is re-parsed each run (no parse-result cache reuse yet), and the whole
  model is held in memory. Fine at current scale; revisit for very large repos.

## Not provided

- No agents / AI layer yet — deferred until the evidence graph and read-only tool
  API can support them.
- No interactive UI (CLI + static HTML by design).

## What Code Atlas does **not** claim

- It does not claim complete understanding of any repository.
- It does not claim FedRAMP authorization, government certification, security
  accreditation, or vulnerability-free status. It is **designed for** offline and
  restricted environments; it is not certified.
