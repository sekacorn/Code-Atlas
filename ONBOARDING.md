# Code Atlas — Guided Repository Onboarding

`atlas onboard <repository>` runs a guided, deterministic investigation that helps
a developer understand an unfamiliar mixed **Java + Ada** system, and produces an
evidence-backed onboarding package. It combines the existing read-only tool API and
deterministic agents into one practical sequence — **no AI, no network, no changes
to the analyzed repository.**

> Code Atlas onboarding provides **evidence-backed software understanding, not
> operational authorization, certification, or accreditation**. It does not claim
> complete understanding of any system. Every finding is traceable to source evidence.

## Purpose

A developer joining an unfamiliar project should be able to run one command and get
grounded answers to: *Did analysis succeed? Where do I start? What are the Java and
Ada entry points? What are the major modules? Which components are central? How do
Java and Ada interact? Where does important data originate, what transforms it, where
is it stored, and who consumes it? What is unresolved? Which files should I read
first? Which questions should I take to an experienced engineer?*

## Intended users

Engineers picking up high-assurance or legacy Java/Ada systems in **controlled,
offline environments** — disconnected development networks, managed workstations,
and restricted repositories — where documentation is often incomplete and cloud
tools are unavailable.

## CLI usage

```bash
atlas onboard /approved/workspace/mission-system
```

Options:

| Option | Default | Meaning |
|---|---|---|
| `--scan` | | Force a fresh scan even if a completed scan exists |
| `--reuse-latest` | on | Reuse the latest completed scan when compatible |
| `--full-rebuild` | | Discard the cached index and reparse everything |
| `--max-components <n>` | 10 | Central components to review |
| `--max-paths <n>` | 5 | Representative lineage paths to sample |
| `--include-inferred` | off | Include inferred lineage edges in sampled paths |
| `--min-confidence <0-100>` | 40 | Minimum lineage edge confidence for sampled paths |
| `--format text\|json\|html` | text | What to print to stdout |
| `-o, --output <dir>` | `./atlas-onboarding-report` | Report output directory (must be **outside** the repo) — see below |

By default onboarding **reuses the latest completed scan** when the index is present
and compatible, and scans automatically when it is not. Because scan ids are
content-derived, reusing a scan of unchanged content yields an identical report.

## Workflow stages

The workflow runs twelve explicit stages. Each records its name, status
(`COMPLETE` / `PARTIAL` / `UNAVAILABLE` / `FAILED`), inputs, output, evidence,
warnings and duration. **A failure in one stage never aborts the workflow** — the
stage is recorded `FAILED` and the rest continue.

1. **Repository Intake** — key, name, sanitized location, branch, scan id, storage
   mode, tool/schema version, languages, build systems, file count.
2. **Scan Health** — files discovered/analyzed/reused/skipped/failed, references
   resolved/unresolved/ambiguous, and a classification (below).
3. **System Inventory** — counts and examples: Java packages/classes/endpoints, Ada
   packages/subprograms/tasks/state, tables, data sources/sinks, config files,
   unresolved artifacts.
4. **Entry-Point Discovery** — build-declared mains (strongest), Java `main(String[])`
   methods and REST endpoints, Ada library-level main procedures and tasks. Never
   classified on naming alone.
5. **Architecture Orientation** — reuses the Repository Orientation Agent: major
   modules, most-connected components, data-access and external-facing components,
   and inferred layers (labelled as inferences).
6. **Java/Ada Boundary Discovery** — see below.
7. **Data-Lineage Sampling** — a small, ranked, representative set of data paths.
8. **Central Component Review** — the components to study first, multi-signal ranked,
   each with a deterministic structured summary.
9. **Risk and Gap Review** — parser failures, unresolved references, external
   dependencies, high-complexity and weak-evidence components, and standing analysis
   limitations — each categorized.
10. **Suggested Reading Order** — an ordered reading path adapted to the evidence.
11. **Questions for Subject-Matter Experts** — grounded questions per expert role.
12. **Final Onboarding Summary** — compact answers with facts, resolved
    relationships, inferred architecture, unresolved questions and limitations kept
    in separate buckets.

## Scan-health interpretation

Deterministic thresholds:

- **FAILED** — no files were analyzed.
- **POOR** — reference-resolution rate below **60%**, or (with exact counts) more
  than a quarter of files failed to parse.
- **PARTIAL** — anything was skipped or failed, or any reference is unresolved or
  ambiguous.
- **HEALTHY** — everything analyzed resolved.

Exact file buckets (analyzed/reused/skipped/failed) are only available when
onboarding ran the scan itself; when **reusing** a persisted scan, file counts and
the resolution rate are **derived from the model** and labelled as derived. Incomplete
analysis is never hidden.

## Entry-point discovery

The strongest evidence wins. When a build file **declares** an entry point (a GNAT
project's `for Main use (...)`), that unit is reported as a `DISCOVERED`
build-declared main and the shape heuristic is skipped entirely. Otherwise: Java
entry points are `main(String[])` methods (by JVM signature, not name) and REST
endpoints (HTTP-verb mapping annotations); an Ada entry point falls back to the
**top-level, parameterless library procedure** shape (the GNAT main-unit shape —
`INFERRED`, and labelled as such). Ada tasks are reported too.

Scheduled jobs and message listeners are **not** detected (their annotations are not
persisted) and are listed as a limitation.

## Java/Ada boundary discovery

A direct Java→Ada call rarely exists in source, so boundaries are graded by the
strength of the **crossing evidence**, and **name similarity alone never creates a
boundary**:

| Type | Evidence |
|---|---|
| `DIRECT_BOUNDARY` | a Java `native` (JNI) method |
| `PROCESS_BOUNDARY` | a Java reference to a process-launch API (`ProcessBuilder`, `Runtime.exec`) |
| `MESSAGE_BOUNDARY` | a Java reference to a messaging API (`send`, `publish`, `JmsTemplate`…) |
| `NETWORK_BOUNDARY` | a Java reference to a socket/HTTP API |
| `SHARED_DATA_BOUNDARY` | a Java and an Ada entity that touch the **same** data-store identity |
| `INFERRED_BOUNDARY` | a **real** unresolved Java reference whose name matches a declared Ada subprogram |
| `UNRESOLVED_BOUNDARY` | a native/process boundary whose far side is outside the analyzed sources |

The Ada counterpart of a native method is reached by a conservative normalized-name
match (`calculateRoute` ↔ `Calculate_Route`); such a counterpart is always reported
as **INFERRED**, with its **missing information** stated, because Code Atlas does not
resolve symbols across languages.

## Representative lineage selection

Candidate starts are the system's real inputs (endpoints, data sources, Ada mains).
Each is traced downstream; the strongest path from each start is kept and the sample
is ranked deterministically, favouring high confidence, paths that cross a boundary
or a data store, and Java/Ada coverage. The default sample size is **5**. Paths with
unresolved segments are labelled **partial**. A sample is never presented as the whole
system.

## Suggested reading order

A deterministic template adapted to the evidence: build files → entry points → public
interfaces → controllers → services → the Java/Ada boundary → core Ada packages →
persistence/state → output → tests. Each recommendation states why it matters, the
question it answers, prerequisites and evidence; nothing is recommended twice.

## Expert-question generation

Grounded questions for a technical lead, Java and Ada specialists, a systems engineer,
a database engineer, a cybersecurity engineer and a test engineer. A question is only
generated for a role the evidence supports (no Ada-specialist question without Ada, no
question about a component that does not exist). Resolved facts are stated as facts;
only the genuinely unknown part is asked.

## Report formats

`onboarding-report.json`, `onboarding-report.html` and `onboarding-report.txt` are
written to `--output`, always **outside** the analyzed repository:

- an explicit `--output` inside the repository is **refused**;
- the default is `./atlas-onboarding-report`, but when that would fall inside the
  repository (e.g. `atlas onboard .` from the repository root) it falls back to
  `~/.code-atlas/onboarding/<repo>-<hash>/` and says so. The HTML is **self-contained**: inline CSS only, no external scripts,
fonts, styles or images, and no network access.

## Determinism

Given the same repository state, completed scan, tool version, rule versions and
options, the report is **byte-for-byte reproducible**, excluding explicitly volatile
metadata (per-stage durations and the generation timestamp, which are omitted from the
JSON body). Ranking ties break on stable id, then qualified name, then source path.

## Read-only guarantees

The onboarding stages are constructed from a **database-level read-only** index and
never learn where the repository lives, so they cannot touch its files. Onboarding
**may** scan, query, rank and write reports outside the repository; it **may not**
modify source, generate patches, delete code, commit, execute repository code or build
scripts, upload data, or call any cloud API. Likely secrets are masked by the
underlying reporting, and reports cite source ranges rather than embedding whole files.

## Known limitations

- Representative lineage paths are a small deterministic sample, not the whole system.
- Java↔Ada counterparts reached by name match are inferred, not resolved.
- Schedulers, message listeners and build-configured mains are not detected as entry
  points.
- Reflection, dynamic dispatch, dynamic SQL, dependency-injection wiring and external
  configuration are blind spots that may add paths not shown.
- Entity scanning caps at 1000 per kind.
- No build-file (Maven/Gradle/`.gpr`) parsing yet, so build membership does not inform
  the workflow.

## Use in controlled environments

Onboarding runs locally, needs no cloud services and no administrator privileges,
stores reports locally, does not write into the analyzed repository, does not execute
the analyzed code or arbitrary builds, and makes no external network calls. It is
**designed for** offline and restricted environments; it is **not** certified,
accredited, or authorized for any system. It provides software understanding to
support human judgement — it does not replace it.
