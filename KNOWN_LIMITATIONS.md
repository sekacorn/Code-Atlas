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

- Entity ids are deterministic and **location-independent** (they survive line
  movement and rescans), and Ada spec/body declarations merge into one entity.
  Residual limitations: parameter types in ids are **source-spelled**, not
  fully-qualified (no classpath symbol solver), so `String` and
  `java.lang.String` would yield different ids; Ada **return-type-only overloads**
  are not distinguished; and the `PROJECT` id is the repository folder name (a
  label, not a cross-machine-stable identity). Duplicate qualified names across
  files are reported as `STABLE_ID_COLLISION` diagnostics rather than resolved.
  See [STABLE_IDENTIFIERS.md](STABLE_IDENTIFIERS.md).

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
- **Data lineage covers two vertical slices: Java/Spring and Ada.** Java:
  endpoint → … → table; Ada: console input → procedure → transformation → package
  state → output, each with per-edge evidence (see [DATA_LINEAGE.md](DATA_LINEAGE.md)).
  Java limits: JAX-RS unsupported; receiver resolution does not follow local
  variables or chained calls; JDBC/literal-SQL extraction not implemented;
  single-parameter transformations; default table naming is an inference. Ada
  limits: package-level state only; unqualified cross-package reads and inner-block
  shadowing are blind spots; overloads stay ambiguous (no argument-type matching);
  no Ada database bindings. Runtime reflection, framework proxies, dynamic SQL and
  external configuration remain blind spots that can add paths Code Atlas cannot see.
- Unused-package/namespace detection is disabled (it needs import-graph resolution)
  to avoid false positives.
- Complexity is standard cyclomatic complexity; it does not model cognitive
  complexity or data-flow complexity.

## Storage & performance

- The CLI now defaults to a **file-backed H2 index** under `~/.code-atlas/index/`
  with scan versioning and conservative parse-result reuse (see
  [PERSISTENCE.md](PERSISTENCE.md) and [INCREMENTAL_ANALYSIS.md](INCREMENTAL_ANALYSIS.md)).
  Remaining limitations: only the **latest completed scan's model snapshot** is
  retained (older scans keep metadata only, so cross-scan model diffing is not yet
  possible); the model is re-merged in memory each scan (reuse avoids re-parsing,
  not re-merging); and entity locations persisted in the model snapshot drop
  column numbers (the parse cache keeps them).

## Agent tool API

- `get_build_membership` and `get_configuration_references` return
  `supported=false` until build-file and configuration parsers exist.
- Dead-code/complexity served through the tool API use the default analysis
  thresholds; a scan run with custom thresholds may report different counts.
- Change impact states its blind spots explicitly (no build/config membership;
  reflection/DI/dynamic paths invisible).

## Not provided

- No agents / AI layer yet. The read-only tool API they will use exists
  (see AGENTS.md); the agents themselves are the next milestones.
- No interactive UI (CLI + static HTML by design).

## What Code Atlas does **not** claim

- It does not claim complete understanding of any repository.
- It does not claim FedRAMP authorization, government certification, security
  accreditation, or vulnerability-free status. It is **designed for** offline and
  restricted environments; it is not certified.
