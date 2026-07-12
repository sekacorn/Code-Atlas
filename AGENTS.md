# Code Atlas — Agent Tool API and Agent Plans

**Status: the read-only agent tool API, deterministic summaries, the Repository
Orientation Agent and the Data-Lineage Investigator Agent are all implemented in
deterministic mode** — every numbered milestone of the enhancement addendum is
complete. Nothing in the core platform depends on agents or AI; everything below
works without either, and no LLM is involved anywhere.

## The tool boundary

Agents (and scripts) query Code Atlas exclusively through `AtlasToolApi`
(module `atlas-tools`), or its CLI transport `atlas tool <operation>`. The
boundary enforces, by construction:

- **Read-only storage.** The index is opened with database-level read-only access
  (`ACCESS_MODE_DATA=r`); the engine itself rejects any write. The API exposes no
  mutating operation and never hands out the mutable model.
- **No repository access.** The API is constructed from an *index path* only. It
  never learns where the analyzed repository lives, so it cannot read or modify
  source files. Evidence is locations and hashes, not source text.
- **Pinned scan.** Every session serves the latest *completed* scan and stamps its
  content-derived scan id on every result. A failed or in-progress scan is never
  visible through the API.
- **Deterministic.** Identical index content yields byte-identical results,
  including ordering (verified by tests).

## Result envelope

Every operation returns:

```json
{ "operation": "...", "scanId": "scan-…", "supported": true,
  "truncated": false, "totalMatches": 7, "note": "", "value": … }
```

Three states are explicit so an agent can never misread them: `supported=false`
means the platform cannot answer this kind of question yet (with the reason in
`note`); an empty `value` with `supported=true` means "the answer is nothing";
`truncated=true` means limits cut the result and `totalMatches` says how much
exists. Entities carry stable ids, locations and attributes; edges carry rule id,
confidence, resolution status, inferred/ambiguous flags and file:line evidence.

## Operations

| Operation | Answer | Status |
|---|---|---|
| `find_entity` | stable id / `"POST /path"` / unique name suffix → one entity (ambiguity lists candidates, never picks) | ✅ |
| `get_entity`, `search_entities` | entity views, filtered and paginated | ✅ |
| `get_source_evidence` | canonical + Ada spec/body locations, file hash | ✅ |
| `get_callers`, `get_callees` | call-graph neighbors (evidence-bearing lineage edge preferred per pair) | ✅ |
| `get_dependencies`, `get_dependents` | all resolved usage edges in/out | ✅ |
| `get_data_sources`, `get_data_sinks` | DATA_SOURCE / DATA_SINK entities | ✅ |
| `get_database_references` | edges touching database tables | ✅ |
| `trace_data_lineage` | full lineage traversal (direction, depth, filters, gaps) | ✅ |
| `calculate_change_impact` | direct/transitive dependents, database impact, downstream lineage, unresolved risks, stated blind spots | ✅ |
| `find_dead_code_candidates`, `get_complexity` | analysis findings with evidence | ✅ |
| `get_repository_summary` | headline facts for orientation | ✅ |
| `get_unresolved_references`, `get_diagnostics` | honest gaps and scan-time diagnostics | ✅ |
| `get_build_membership` | — | ⛔ `supported=false` until build-file parsing exists |
| `get_configuration_references` | — | ⛔ `supported=false` until config parsing exists |

Dead-code and complexity views are computed over the persisted model with the
default thresholds (complexity 10, dead-code confidence 60); a scan run with
custom thresholds may show different counts in its own report.

## CLI transport

```
atlas tool get_repository_summary --repo /path/to/repo
atlas tool get_callers --id "java:method:com.example.CustomerService#createCustomer(CustomerRequest)"
atlas tool calculate_change_impact --id sql:table:customer
atlas tool trace_data_lineage --id java:endpoint:POST:/customers --direction downstream
atlas tool get_build_membership --id …        # returns supported=false + reason
```

## Contract for the coming agents

Agents may explain, investigate, navigate, organize findings, generate
documentation and recommend review steps. Agents may **not** invent entities,
relationships, call paths or data flows; hide uncertainty; edit or delete code;
modernize code automatically; or commit/push changes. Every agent answer must be
built from tool results and cite their stable ids and evidence, in the structure:
answer, confirmed facts, inferred findings, evidence, confidence, unresolved
questions, known limitations, suggested next investigation.

## Implemented agents (deterministic mode)

### Repository Orientation Agent — `atlas orient`

Answers the eight orientation questions (where to start, main modules, likely
entry points, most central components, data stores, external systems, what to
read first, what could not be analyzed) using only tool-API results: endpoints
and input sources as entry points, package member counts for module size,
resolved fan-in for centrality, tables + Ada package state as stores, and
unresolved qualified targets as *inferred* external systems. Every answer uses
the structure above; handlers and other facts are read from graph edges, never
from naming alone; candidate scans are capped (100 per kind) and the cap is a
documented heuristic.

### Deterministic summaries — `atlas summarize <stable-id>`

Method/subprogram summaries state parameters, returns, complexity, calls, reads,
writes (side effects), SPARK contracts and lineage role; component summaries
state members, dependencies, consumers, touched data stores, peak member
complexity and dead-code risks. Structural facts are confirmed and cited; the
responsibility line is always labelled **inferred**.

### Data-Lineage Investigator Agent — `atlas investigate <entity>`

Answers, for one entity: **where did this data originate, what transforms it,
where is it stored, where does it go and who consumes it, and which parts of the
path are unresolved** — plus an overview with the numbered confirmed path
(origin → … → target), each step backed by per-edge evidence and the path
confidence taken from its weakest edge. Origins include input sources read by
the entity's writers (one evidence-backed hop); transformation steps cite their
`ATLAS-LINEAGE-*` rule; external consumers and unresolved references appear as
first-class answers. Accepts a stable id, `"POST /path"` shorthand, or a unique
name suffix.

```
atlas orient --repo /path/to/repo [--format json]
atlas summarize java:method:com.example.CustomerService#createCustomer(CustomerRequest) --repo …
atlas investigate sql:table:customer --repo …
atlas investigate ada:variable:Mission_Data.Current_Route --repo …
```

All are deterministic: identical index content yields byte-identical output.

## Runtime modes

In order of delivery: **deterministic** (implemented — graph traversal, rules and
templates over this API, no LLM), then optional **local AI** that receives only
structured tool results (never the repository), then an optional AgentForge
adapter. The core product remains fully useful with AI disabled.
