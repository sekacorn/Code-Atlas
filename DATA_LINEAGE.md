# Code Atlas — Java Data Lineage

Code Atlas traces where data enters a Java application, what transforms it, where
it is stored, and what consumes it — deterministically, offline, with evidence on
every edge, and with unresolved segments shown rather than papered over. No AI is
involved anywhere in lineage construction.

**Scope today:** one complete Spring-style vertical slice —
`HTTP endpoint → controller → service → transformation/validation → Spring Data
repository → JPA entity → database table → response DTO`. Ada lineage, JAX-RS,
JDBC/SQL extraction and message queues are not yet implemented (see
[Known limitations](#known-limitations)).

## Example

```
$ atlas lineage "POST /customers" --downstream

Path 1 (confidence 0.90):
  ENDPOINT  POST:/customers
    -[invokes 1.00 discovered]-> CustomerController#createCustomer(CustomerRequest)
       evidence: .../CustomerController.java:10  rule: ATLAS-LINEAGE-ENDPOINT-001
    -[invokes 0.95 resolved]->  CustomerService#createCustomer(CustomerRequest)
    -[invokes 0.95 resolved]->  CustomerMapper#toEntity(CustomerRequest)
    -[produces 0.90 resolved]-> CustomerEntity
    -[maps_to 1.00 resolved]->  table: customer

Unresolved:
  - [UNRESOLVED_TARGET] Reference to 'AnalyticsClient#push' could not be resolved
  - [EXTERNAL_CONSUMER] The consumer of 'CustomerResponse' is external and is not
    represented in this repository
```

## What is detected

| Step | Mechanism | Classification |
|---|---|---|
| HTTP endpoint | `@RestController`/`@Controller` + `@RequestMapping`/`@GetMapping`/`@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping`, class+method path composition | DISCOVERED |
| Request/response DTOs | `@RequestBody` parameter type; return type with `ResponseEntity`/`Optional`/`List`/`Set`/`Collection`/`Iterable` wrappers unwrapped | RESOLVED |
| Controller → service → … calls | declared **field types** of the receiver (`customerService.create(…)` → field `customerService` → type `CustomerService`), arity-checked method lookup | RESOLVED |
| Dependency injection via interface | `IMPLEMENTS` graph: exactly one implementation → resolved; several → **all candidates kept, marked ambiguous** — never chosen arbitrarily | RESOLVED / INFERRED |
| Transformation | single-parameter method whose input and output are both project types **and** which instantiates its output type (type flow); naming (`to…`/`map…`/`convert…`/`from…`/`build…`) alone is only an inference | RESOLVED / INFERRED |
| Validation | `@Valid`/`@Validated` recorded as endpoint evidence; explicit `validate*(Dto)` calls become `validated_by` edges | DISCOVERED / RESOLVED |
| Repository → entity | interface extending `JpaRepository`/`CrudRepository`/… : first type argument is the managed entity | RESOLVED |
| Entity → table | `@Table(name=…)` string literal; without it, the lower-cased simple class name is used as a **documented inference** (real physical naming is configuration-dependent) | RESOLVED / INFERRED |
| Read vs write | repository method prefixes — write: `save/insert/update/delete/remove/persist/merge`; read: `find/get/read/query/count/exists/search/stream`; anything else becomes a conservative `uses` edge, never a fabricated write | RESOLVED |
| Unresolvable references | a call through a declared field whose type is not in the repository becomes an explicit **UNRESOLVED** edge and a first-class gap | UNRESOLVED |

## Relationship types

`exposes` (controller→endpoint), `invokes`, `consumes`, `produces`, `reads_from`,
`writes_to`, `maps_to` (entity→table), `persists_to` (repository→table),
`validated_by`, `manages` (repository→entity). Every lineage edge carries:

```
ruleId, ruleVersion, analyzerId, confidence (0..1), resolution status
(DISCOVERED | RESOLVED | INFERRED | UNRESOLVED), inferred/ambiguous flags,
source location (file:line)
```

The edges are stored with the scan snapshot, so lineage queries run from the
persisted index without rescanning.

## Rule catalog and confidence

Confidence is **fixed per rule** — never produced by a model.

| Rule | Detects | Confidence |
|---|---|---|
| `ATLAS-LINEAGE-ENDPOINT-001` | endpoint from mapping annotations | 1.00 |
| `ATLAS-LINEAGE-ENDPOINT-002` | endpoint request/response DTO connection | 0.95 |
| `ATLAS-LINEAGE-CALL-001` | call resolved via declared receiver type / same class | 0.95 |
| `ATLAS-LINEAGE-DI-001` | interface call with unique implementation | 0.90 |
| `ATLAS-LINEAGE-DI-002` | interface call with several implementations (each kept) | 0.50, ambiguous |
| `ATLAS-LINEAGE-MAP-001` | transformation via type flow + output instantiation | 0.90 |
| `ATLAS-LINEAGE-MAP-002` | transformation via naming convention only | 0.60, inferred |
| `ATLAS-LINEAGE-VALIDATION-001` | DTO validated by explicit validator call | 0.90 |
| `ATLAS-LINEAGE-JPA-TABLE-001` | explicit `@Table` mapping | 1.00 |
| `ATLAS-LINEAGE-JPA-TABLE-002` | default table-name inference | 0.60, inferred |
| `ATLAS-LINEAGE-REPOSITORY-001` | repository manages entity (type argument) | 1.00 |
| `ATLAS-LINEAGE-REPOSITORY-002` | repository persists to the mapped table | ≤0.95 |
| `ATLAS-LINEAGE-READ-001` / `WRITE-001` | classified repository operation | 0.90 × table confidence |
| `ATLAS-LINEAGE-REPOSITORY-003` | unclassified repository operation (`uses`) | 0.70 × table confidence |
| `ATLAS-LINEAGE-UNRESOLVED-001` | detected reference with unidentifiable target | 0.40, unresolved |

Edges below 0.40 are not emitted. Traversal defaults exclude inferred edges
(`--include-inferred` adds them) and apply a 0.40 confidence floor
(`--min-confidence` raises it).

## CLI

```
atlas lineage "POST /customers" --downstream
atlas lineage sql:table:customer --upstream
atlas lineage java:type:com.example.CustomerResponse --upstream --include-inferred
atlas lineage CustomerResponse --both --max-depth 6 --format json
```

Options: `--repo <path>` (default index of that repository), `--index <path>`,
`--upstream | --downstream | --both`, `--max-depth`, `--include-inferred`,
`--min-confidence`, `--scan <scan-id>`, `--format text|json`. The command is
read-only and requires a prior `atlas scan`.

## JSON output

Deterministic (no timestamps; content-derived `scanId`; stable ordering):

```json
{
  "scanId": "scan-634712c91f16",
  "query": {"start": "java:endpoint:POST:/customers", "direction": "DOWNSTREAM",
             "maxDepth": 8, "includeInferred": false, "minConfidence": 0.40},
  "nodes": [ {"id": "...", "kind": "ENDPOINT", "label": "...", "location": "..."} ],
  "paths": [ {"nodes": ["..."], "edges": [ {"from": "...", "to": "...", "kind": "INVOKES",
               "ruleId": "ATLAS-LINEAGE-CALL-001", "confidence": 0.95,
               "status": "RESOLVED", "inferred": false, "ambiguous": false,
               "evidence": "file.java:12"} ], "confidence": 0.90} ],
  "unresolvedGaps": [ {"at": "...", "kind": "UNRESOLVED_TARGET", "description": "..."} ],
  "cyclesDetected": false,
  "truncated": false
}
```

The scan report (`report.json`) additionally carries a `lineage` section with
endpoints, data stores, representative traces and coverage counters; the HTML
report renders the same as a "Data lineage (Java)" section.

## Incremental behavior

Lineage facts follow the persistent-scan architecture: parser-extracted evidence
is cached per file and reused when content and parser version are unchanged
(verified byte-identical); lineage rules re-run on every scan over the merged
model; changed files replace stale facts; deleted files remove their paths; a
failed scan never replaces the last completed lineage graph; results are
queryable after restart without rescanning.

## Wording and honesty

A path is only ever "complete **within analyzed evidence**". Traversal reports
unresolved targets, ambiguous implementations and external consumers as
first-class gaps. Known blind spots that can add paths Code Atlas cannot see:
runtime reflection, framework proxies, dynamic SQL, externally supplied
configuration, and dependencies outside the repository.

## Security and privacy

Lineage adds no network access, executes no repository code or build scripts,
modifies nothing in the analyzed repository, and stores evidence as file/line
references and hashes — not full source text. Reports contain code identifiers
and paths only; connection strings or credentials are not extracted.

## Known limitations

- Spring annotations only; **JAX-RS is not yet supported**. `@RequestMapping`
  without a verb-specific annotation does not produce an endpoint.
- Receiver resolution covers declared fields, `this.field` and static type names;
  local variables and chained calls (`a.b().c()`) are not followed.
- JDBC / literal SQL extraction is not implemented; dynamic SQL remains out of
  scope by design.
- Transformation detection covers single-parameter methods.
- `--scan` can only address the latest completed scan (older snapshots keep
  metadata only).
- Default table naming is an inference; real physical naming strategies are
  configuration-dependent.
- MapStruct `@Mapper` interfaces are tagged but implementations are not generated,
  so mapper-interface calls resolve only when an implementation exists in source.
