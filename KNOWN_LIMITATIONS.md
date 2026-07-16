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
- **Configuration** (Spring/JEE XML, `.properties`, YAML) is parsed for code
  references, which sharpen dead-code (wired classes are not dead). Limits: JSON
  config is not parsed; only dotted class-name *values* become references (SpEL,
  placeholder-indirected and annotation-scanned beans are not followed); the YAML
  reader is minimal (no anchors/flow collections); config values are not yet lineage
  data sources.
- **Build files** (Maven `pom.xml`, Gradle `build.gradle[.kts]`/`settings.gradle[.kts]`,
  GNAT `.gpr`) are parsed into modules, declared dependencies and declared main units,
  and every file is assigned to the module that owns it. They are read **literally**:
  no Maven/Gradle resolution or script evaluation ever runs. So property interpolation
  (`${...}`), `<dependencyManagement>`/parent-inherited dependency versions, profiles,
  Gradle version catalogs (`libs.foo`), `ext` properties and plugin-injected
  dependencies are **not** resolved; GNAT variables, scenario variables
  (`external(...)`) and `package` blocks are not evaluated. Only literal declarations
  are recorded. **No database (SQL/DDL) parsing** yet.

## Analysis

- **Dead code is probabilistic.** Findings are candidates with confidence scores,
  not certainty. Reflection, DI, external invocation, and dynamic configuration are
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

- Every operation is now implemented: `get_configuration_references` (configuration
  parsing) and `get_build_membership` (build-file parsing) both answer. A repository
  that declares no build files gets an explicit "no build files were found" note
  rather than a silent empty answer.
- An entity in a file that no module directory covers is reported **unowned**, not
  unknown; membership is by directory nesting, so a file outside every module's
  directory has no owner.
- Dead-code/complexity served through the tool API use the default analysis
  thresholds; a scan run with custom thresholds may report different counts.
- Change impact states its blind spots explicitly (no build/config membership;
  reflection/DI/dynamic paths invisible).

## Not provided

- Agents run in **deterministic mode only** (templates and graph rules); the
  optional local-AI explanation mode and AgentForge adapter are not implemented.
  A dedicated Impact agent remains future (the tool operation exists).
- The investigator's origin detection follows lineage paths plus one
  evidence-backed hop from writers to their input sources; longer indirect
  origin chains may be missed and are reported as absent, not guessed.
- Orientation heuristics are bounded: candidate scans cap at 100 entities per
  kind, "responsibility" lines are structural inferences, and reading-order
  advice is heuristic — all labelled as such in the output.
- Graph **SVG** export uses a simple deterministic layered layout, capped at 200
  nodes; for large or dense graphs, export **DOT** and render with Graphviz for
  better routing. There is no interactive graph viewer (CLI + static HTML/SVG by
  design).
- No interactive UI (CLI + static HTML by design).

## Guided onboarding (`atlas onboard`)

- **It is a guided view over existing analysis, not new analysis.** The onboarding
  coordinator organizes results from the read-only tool API and the deterministic
  agents; it never creates new facts, and it inherits every limitation above.
- **Java↔Ada boundaries are graded by evidence and never invented from names.** A
  boundary requires a real crossing signal (a `native`/JNI method, a process/message/
  network API reference, an actual unresolved cross-language reference, or a shared
  data-store identity). A native method's Ada counterpart is reached by a conservative
  normalized-name match and is always reported **INFERRED** with its missing
  information stated — Code Atlas does not resolve symbols across languages. Process/
  message/network boundaries name the far side only when evidence supports it.
- **Entry points** cover Java `main(String[])` and REST endpoints, and Ada top-level
  parameterless library procedures (the GNAT main shape, inferred) and tasks.
  Schedulers, message listeners and build-configured mains are **not** detected.
- **Representative lineage paths are a small deterministic sample** (default 5), never
  the whole system; partial paths are labelled partial.
- **Scan health for a reused scan is derived from the persisted model** (file buckets
  and resolution rate are approximate and labelled as derived); exact counts require a
  fresh scan.
- **Entry points** are strongest when a build file declares them (a GNAT
  `for Main use (...)` yields a DISCOVERED main); without one, an Ada main falls back
  to the top-level-parameterless-procedure shape and is labelled INFERRED.
- **"External dependency" is only claimed where it was checked**: a declared build
  coordinate that matched no module in this repository. Unresolved *code* references
  are reported as an analysis gap instead, because Code Atlas cannot tell a
  third-party call from a reference it simply failed to link.
- Entity scanning caps at **1000 per kind**. Onboarding writes reports **outside** the
  analyzed repository and never modifies, patches, deletes, commits, executes or
  uploads anything. See [ONBOARDING.md](ONBOARDING.md).

## What Code Atlas does **not** claim

- It does not claim complete understanding of any repository.
- It does not claim formal authorization, security accreditation, or
  vulnerability-free status. It is designed for offline and controlled
  environments; it is not certified.
