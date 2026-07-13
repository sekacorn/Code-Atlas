# Code Atlas

**Offline-first software intelligence and static analysis platform.**

Code Atlas scans large, mixed-language repositories and builds a complete,
queryable structural model of the software — so engineers can understand legacy
systems, map architecture, find dead code, measure complexity, and see how
components depend on each other. It runs **entirely locally**, needs **no
administrator privileges**, and requires **no cloud and no AI** for any core
function. AI, if ever enabled, is an optional explanation layer only.

> Static analysis is the source of truth. Every finding is evidence-based and
> traceable to the source file and rule that produced it.

---

## Documentation

- [CURRENT_STATE.md](CURRENT_STATE.md) — factual assessment of what is implemented, build health, and technical risks.
- [EVIDENCE_MODEL.md](EVIDENCE_MODEL.md) — source evidence, resolution status, and analysis coverage.
- [STABLE_IDENTIFIERS.md](STABLE_IDENTIFIERS.md) — the deterministic id grammar, Ada spec/body merge, and collisions.
- [DATA_LINEAGE.md](DATA_LINEAGE.md) — Java data lineage: rules, confidence, CLI queries, JSON format, gaps.
- [PERSISTENCE.md](PERSISTENCE.md) / [INCREMENTAL_ANALYSIS.md](INCREMENTAL_ANALYSIS.md) — file-backed index, scan versioning, parse reuse.
- [AGENTS.md](AGENTS.md) — the read-only agent tool API, deterministic summaries, and the Orientation and Data-Lineage Investigator agents.
- [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) — what the tool does not (yet) do, and what it does not claim.

## Status — Milestone 1 (Java + Ada vertical slice)

This milestone proves the whole pipeline end to end:

```
scan → parse (Java + Ada) → unified model → link cross-references
     → local H2 index → analysis → HTML / JSON / CSV reports
```

It directly answers the platform's core questions on a real repository:
**what exists, how it connects, what depends on what, what is unused, what is
risky, how large the system is, and — at component level — where data comes from
and who consumes it.**

### What works today

| Capability | Detail |
|---|---|
| **Repository scanner** | Recursive walk, default exclusions (`.git`, `target`, `build`, `out`, `node_modules`, `bin`, `obj`, …), language/type detection, SHA-256 hashing, parallel processing |
| **Java parser** | Packages, classes, interfaces, enums, records, methods, constructors, fields, imports, inheritance/implements, method calls, instantiations, type references, cyclomatic complexity, exposure heuristics (annotations, `main`, visibility) — built on [JavaParser] |
| **Configuration parser** | XML (Spring/JEE beans, XXE-safe), `.properties` and YAML → `CONFIGURATION` entities and `CONFIGURES` references to the classes they wire up; framework-wired classes are no longer flagged as dead code; likely secrets are masked and never stored |
| **Ada / SPARK parser** | `.ads`/`.adb`: packages & child packages, procedures, functions, types (record/enum/access/derived), tasks, protected types, exceptions, `with` dependencies, renamings, generic instantiations, **SPARK Pre/Post contracts**, cyclomatic complexity — deterministic line-and-scope scanner, no native toolchain required |
| **Unified model** | Language-neutral entities & relationships with **deterministic, location-independent stable ids** (`java:type:…`, `ada:function:…(Integer)`), Ada spec/body merged into one identity, and source locations kept as evidence — see [STABLE_IDENTIFIERS.md](STABLE_IDENTIFIERS.md) |
| **Cross-reference linker** | Resolves symbolic call/type targets across files; conservative on ambiguity so dead-code is never overstated |
| **Analysis** | Repository metrics (files, LOC, comments, language distribution, entity counts), complexity hotspots with risk bands, **dead-code detection with an evidence + confidence model**, package coupling & circular-dependency detection |
| **Local index** | **File-backed H2 by default** (`~/.code-atlas/index/…`, never inside the analyzed repo) with scan versioning, atomic snapshot replacement (a failed scan never clobbers the last good one), and full relationship metadata — see [PERSISTENCE.md](PERSISTENCE.md) |
| **Incremental scanning** | Per-file SHA-256 change detection plus **conservative reuse of unchanged parser results** (same content + same parser version), verified byte-identical to re-parsing — see [INCREMENTAL_ANALYSIS.md](INCREMENTAL_ANALYSIS.md) |
| **Data lineage (Java + Ada)** | Deterministic tracing with a rule id, confidence and source evidence on every edge — Java: endpoint→controller→service→transformation→repository→**table**; Ada: **console input→procedure→transformation→package state→output**; ambiguity and unresolvable calls surface as explicit gaps; `atlas lineage` queries the persisted index up- or downstream — see [DATA_LINEAGE.md](DATA_LINEAGE.md) |
| **Analysis coverage** | Every scan reports files analyzed/skipped/failed and reference resolution rate, and is labelled **PARTIAL** when coverage is incomplete — incomplete analysis is never presented as complete |
| **Resolution status** | Relationships expose `DISCOVERED` / `RESOLVED` / `INFERRED` / `UNRESOLVED` so uncertainty is explicit (see [EVIDENCE_MODEL.md](EVIDENCE_MODEL.md)) |
| **Reports** | Self-contained **HTML** dashboard (offline, no CDN/scripts), plus **JSON** and **CSV** |
| **Graph exports** | `atlas graph --type <dependency\|call\|dead-code\|architecture> --format <dot\|svg>` — deterministic Graphviz DOT and self-contained SVG views over the persisted model (risk-coloured coupling, call graph, active-vs-dead, role layers) |
| **Agent tool API** | `atlas tool <operation>` / `AtlasToolApi`: a controlled, **database-level read-only** query boundary over the persisted index (callers, dependents, lineage, impact, dead code, summary) with stable ids, evidence and honest `supported=false` for missing capabilities — see [AGENTS.md](AGENTS.md) |
| **Deterministic agents** | `atlas orient` (where do I start / what's central / what couldn't be analyzed), `atlas summarize <id>` (method/component summaries) and `atlas investigate <id>` (where data originates, what transforms it, where it's stored, who consumes it, what's unresolved — with the numbered confirmed path) — **templates over the tool API, no LLM**, confirmed facts separated from labelled inferences, every statement citing stable ids and file:line evidence — see [AGENTS.md](AGENTS.md) |
| **CLI** | `atlas scan <repo>` — single runnable jar, no install |

### Dead-code philosophy

Findings are **probable, never absolute**. Each candidate lists the evidence
that produced it and a confidence score (capped below 100%), and always
recommends review before removal — matching the spec's requirement to never make
absolute claims.

---

## Build

Requires **JDK 21** and **Maven 3.9+**.

```bash
mvn clean install
```

This produces a self-contained runnable jar at `atlas-cli/target/atlas.jar`.

## Run

```bash
java -jar atlas-cli/target/atlas.jar scan /path/to/repo --out ./atlas-report
```

Then open `atlas-report/report.html` in any browser (works offline). The scan
persists a file-backed index under `~/.code-atlas/index/`, which lineage queries
read without rescanning:

```bash
java -jar atlas-cli/target/atlas.jar lineage "POST /customers" --downstream --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar lineage sql:table:customer --upstream --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar lineage ada:variable:Mission_Data.Current_Route --upstream --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar tool get_repository_summary --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar tool calculate_change_impact --id sql:table:customer --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar orient --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar summarize sql:table:customer --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar investigate sql:table:customer --repo /path/to/repo
java -jar atlas-cli/target/atlas.jar graph --type architecture --format svg --repo /path/to/repo -o arch.svg
```

### Options

| Option | Default | Description |
|---|---|---|
| `--out <dir>` | `atlas-report` | Report output directory |
| `--index <path>` | in-memory | Persistent H2 index (enables incremental runs) |
| `--complexity-threshold <n>` | `10` | Cyclomatic complexity flag threshold |
| `--min-confidence <0-100>` | `60` | Minimum dead-code confidence to report |
| `--threads <n>` | CPU count | Parallel workers |

---

## Architecture

```
Repository → Scanner → Parser framework (plugins) → Unified model
          → Linker → Local index (H2)
          → Analysis engine → Reports        (→ optional local AI layer)
```

### Module map

| Module | Responsibility |
|---|---|
| `atlas-model` | Language-neutral entities, relationships, source locations (zero dependencies) |
| `atlas-parser-api` | `RepositoryParser` SPI, `ParseRequest`/`ParseResult` — the plugin contract |
| `atlas-scanner` | Recursive scan, language detection, hashing, parallel walk |
| `atlas-parser-java` | Java extraction (JavaParser) |
| `atlas-parser-ada` | Ada / SPARK extraction |
| `atlas-index` | H2-backed store + incremental change detection |
| `atlas-analysis` | Metrics, complexity, dead-code, dependency analysis |
| `atlas-reporting` | HTML / JSON / CSV generation |
| `atlas-core` | Pipeline orchestration + cross-reference linker |
| `atlas-cli` | Command-line driver |

### Extensibility

Parsers are discovered at runtime via `java.util.ServiceLoader`. A new language
or proprietary format is added by implementing `RepositoryParser` and declaring
it in `META-INF/services/com.codeatlas.parser.api.RepositoryParser` — **no core
changes, no rebuild of the platform.**

---

## Roadmap (per the platform spec)

Planned modules that drop into the existing, language-neutral core:

- **Parsers:** configuration (XML/YAML/JSON/properties), database (SQL/DDL),
  Ada `.gpr` build files, custom proprietary formats (`.workflow`, `.mapping`,
  `.rules`, …), and future languages (C/C++, Python, COBOL, Fortran).
- **`atlas-graph`:** call / dependency / architecture / dead-code graph exports.
- **`atlas-summary`:** deterministic project/component/method summaries.
- **`atlas-ui`:** interactive dashboard, explorer, graph viewer.
- **`atlas-ai`:** optional local explanation layer (consumes structured context
  only; never scans source directly).
- **Analysis:** impact analysis, unused-package detection, architecture
  compliance rules, PDF reports, git-history analysis, security mapping.

---

## Design principles

1. Static analysis is the source of truth (AI optional).
2. Language-independent core; Java and Ada are first-class.
3. Fully offline; no administrator privileges.
4. Extensible parser plugin architecture.
5. Evidence-based, auditable results.
6. The knowledge graph is the product.

Built for controlled environments where local execution, auditability, and
offline operation matter.

[JavaParser]: https://javaparser.org/
