# Code Atlas — Current-State Assessment

_Assessment date: 2026-07-11. Reflects the repository as inspected, not the
original prompt. Scope: Milestone 1 codebase plus the analysis-coverage and
stable-identifier addendum enhancements._

This document is the factual baseline the enhancement addendum requires before any
architectural change. It records what exists, how healthy the build is, and the
concrete technical risks that later milestones must address.

---

## Implemented (works today)

- **Repository scanner** (`atlas-scanner`): recursive walk, default directory
  exclusions (`.git`, `target`, `build`, `out`, `node_modules`, `bin`, `obj`, …),
  extension-based language/category detection, SHA-256 content hashing, parallel
  hashing, size cap, symlink-skip by default. Verified: excluded dirs are skipped;
  identical bytes → identical hash.
- **Java parser** (`atlas-parser-java`, JavaParser): packages, classes, interfaces,
  enums, records, methods, constructors, fields, imports, inheritance/implements,
  method calls, instantiations, **type-reference edges** (field/param/return),
  cyclomatic complexity, exposure heuristics (public/`main`/framework annotations).
- **Ada/SPARK parser** (`atlas-parser-ada`): deterministic line-and-scope scanner
  for `.ads`/`.adb` — packages & child packages, procedures, functions, types
  (record/enum/access/derived), tasks, protected types, exceptions, `with`
  dependencies, renamings, generic instantiations, **SPARK Pre/Post contracts**,
  cyclomatic complexity, spec-vs-body exposure.
- **Unified model** (`atlas-model`): language-neutral `Entity` / `Relationship` /
  `SourceLocation` with deterministic ids and adjacency indexes. Zero dependencies.
- **Cross-reference linker** (`atlas-core`): resolves symbolic call/type targets
  after all files are parsed; conservative on ambiguity (caps candidates, keeps
  originals). Now returns **link statistics** (resolved / unresolved / ambiguous).
- **Stable identifiers** (`atlas-model`): deterministic, location-independent ids
  (`java:type:…`, `ada:function:…(Integer)`, `file:…`) that survive line movement
  and rescans; exposed on findings in JSON/CSV. See [STABLE_IDENTIFIERS.md](STABLE_IDENTIFIERS.md).
- **Ada spec/body merge** (`atlas-model` + Ada parser): a package/subprogram's
  `.ads` spec and `.adb` body share one identity, retaining both source evidences
  (`hasSpec`/`hasBody`/`specLocation`/`bodyLocation`); overloads stay distinct.
- **Collision diagnostics**: two distinct declarations that share an id are recorded
  (never silently overwritten) and surfaced in the report and CLI.
- **Analysis** (`atlas-analysis`): repository metrics, complexity hotspots with
  risk bands, dead-code detection with **evidence list + confidence (capped < 100%)**
  and exposed-name propagation, package coupling + circular-dependency detection.
- **Local index** (`atlas-index`): H2 store for entities/attributes/relationships/
  file-hashes; **incremental change detection** (added/changed/removed/unchanged).
- **Reporting** (`atlas-reporting`): self-contained HTML dashboard (offline, no
  CDN/scripts), JSON, CSV. Now includes an **Analysis Coverage** section.
- **CLI** (`atlas-cli`): `atlas scan <repo>` as a single shaded runnable jar;
  options for output dir, persistent index, thresholds, threads.

## Partially implemented

- **Evidence model.** Entities/relationships carry source locations and a
  resolved/unresolved distinction; the addendum's richer evidence (parser id +
  version, rule id, file hash, four-state resolution status) is **now introduced as
  `ResolutionStatus`** and surfaced in coverage, but is not yet attached to every
  entity/finding.
- ~~Incremental scanning.~~ **Now implemented:** persistent file-backed H2 default,
  scan versioning with failed-scan protection, and conservative reuse of unchanged
  parser results (see PERSISTENCE.md / INCREMENTAL_ANALYSIS.md). Remaining gap:
  only the latest completed scan's model snapshot is retained.
- **Dependency/lineage view.** Package coupling and a component-level "data flow &
  consumers" report exist; true data lineage (source→transform→store→consumer with
  per-edge evidence) is **not** implemented.

## Scaffolded only

- None. The reactor contains only modules with real, tested functionality; there
  are no empty placeholder modules or stub classes (by design — the addendum
  forbids placeholder modules).

## Missing (addendum requirements not yet implemented)

- ~~Java data lineage~~ **Now implemented** (endpoint→…→table with per-edge rule ids,
  confidence, resolution status, gaps, CLI/JSON/HTML — see DATA_LINEAGE.md).
  Still missing: the **Ada** lineage vertical slice; config/SQL parser input to lineage.
- Persistent file-backed H2 as the **default** for ordinary runs.
- Config (XML/YAML/JSON/properties), database (SQL/DDL), build (`.gpr`, Maven/
  Gradle) and custom-format parsers.
- Build membership feeding dead-code / entry-point / impact analysis.
- Scan versioning; storage of findings/diagnostics/parser-and-rule versions.
- Deterministic summaries (repository/component/method).
- Read-only agent tool API and the agents (Orientation, Lineage, Impact).
- Impact analysis; unused-package detection; PDF reports.

---

## Current architecture

**Module structure** (11 Maven modules, Java 21):
`atlas-model` → `atlas-parser-api` → `atlas-scanner` → `atlas-parser-java` /
`atlas-parser-ada` → `atlas-index` → `atlas-analysis` → `atlas-reporting` →
`atlas-core` → `atlas-cli`.

**Main execution path** (`CodeAtlasPipeline.run`):
`scan → read+parse each file in parallel → merge into one SoftwareModel →
Linker resolves cross-refs → persist to H2 → AnalysisEngine → assemble ReportData`.

- **Parser architecture:** `RepositoryParser` SPI discovered via `ServiceLoader`.
  Parsers are stateless; each `parse(ParseRequest)` returns an immutable
  `ParseResult` (no mutable per-instance extraction state). Cross-file targets are
  emitted as unresolved edges for the Linker.
- **Unified model:** single `SoftwareModel` (no competing second model). Entities
  keyed by deterministic string id; relationships are directed with a `resolved`
  flag and optional location.
- **Storage:** H2 via `AtlasStore`, **in-memory by default**, file-backed optional.
  Narrow key/value attribute table keeps it queryable. SQL is confined to
  `atlas-index` (not leaked into parsers/analysis).
- **Graph model:** adjacency indexes on `SoftwareModel` (outgoing/incoming); no
  separate graph database. Dependency/cycle analysis runs over these.
- **Analysis engine:** deterministic; `AnalysisEngine` composes metrics, complexity,
  dead-code, dependency analyzers. No AI anywhere.
- **CLI:** picocli; single `scan` subcommand.
- **UI:** none (CLI + static HTML by design decision).
- **AI / agents:** none yet (correctly deferred until the evidence graph supports them).

## Current build health

- **Build:** `mvn clean install` → **BUILD SUCCESS** (11 modules).
- **Test:** `mvn test` → **14 tests, 0 failures, 0 errors** (scanner 2, java 3,
  ada 3, index 2, analysis 3, core 1) plus the new coverage tests added in this
  milestone.
- **Warnings:** benign SLF4J "no providers" notices during test runs (no logging
  binding on the test classpath); the CLI ships `slf4j-simple` at runtime.
- **Determinism:** verified — two scans of the same repo produce byte-identical
  JSON (excluding the timestamp field).
- **Repository immutability:** verified — file hashes are identical before and
  after a scan; analysis never writes into the analyzed repo.
- **Environment:** JDK 21, Maven 3.9+. Dependency download requires network at
  build time only; the built tool runs fully offline.

## Technical risks

1. ✅ **Unstable identifiers — RESOLVED.** Entities now use deterministic,
   location-independent stable ids; Ada spec/body declarations merge into one
   logical entity. Line movement and rescans no longer change identity (proven by
   tests). Residual: parameter types are source-spelled (no symbol solver), so two
   spellings of the same type produce different ids; and duplicate qualified names
   across files are reported as collisions rather than resolved.
2. **Incomplete symbol resolution.** Call/type resolution is name-based (no full
   classpath symbol solver), so cross-references are approximate. Handled honestly
   via the confidence model and the new coverage metric, but callers must not treat
   resolved edges as ground truth.
3. **Incomplete lineage.** No data-source/sink/store modelling yet; the "data flow"
   report is component-coupling only.
4. ✅ **Storage default — RESOLVED.** The CLI defaults to a file-backed index with
   scan records; a failed scan never replaces the last completed snapshot.
5. **Missing evidence richness.** No parser/rule versioning or file-hash provenance
   attached to findings yet.
6. **Build-context blindness.** Build files are not parsed, so dead-code and
   entry-point detection cannot use build membership → possible false positives for
   framework/reflection/DI-invoked code.
7. **Unsupported constructs.** Ada scanner is line-based and will miss constructs
   spanning unusual formatting; unsupported languages are detected but not parsed.
8. **Performance.** Whole-file re-parse each run; entire model held in memory.
   Acceptable at current scale; revisit for very large repos.
9. **Security posture (good, to keep):** offline, no admin, no code execution, no
   build-script execution, repo read-only, symlinks not followed by default. No
   loopback server yet (no UI). No certification claims are made.

---

## Recommended next steps (smallest-first)

1. ✅ **Analysis-coverage reporting + `ResolutionStatus`** (honest uncertainty). _Done._
2. ✅ **Stable identifiers + Ada spec/body merge** (resolved risk #1). _Done._
3. ✅ **Persistent file-backed H2 default + scan versioning + parse reuse** (resolved risk #4). _Done._
4. ✅ **Java data-lineage vertical slice** (endpoint→…→table, evidence-backed). _Done._
5. **Ada data-lineage vertical slice** (input → procedure → transformation → state/output).
6. **Read-only agent tool API**, then the Orientation and Lineage agents.
