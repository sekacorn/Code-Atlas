# Code Atlas — Stable Identifiers

Every entity in the model has a **deterministic, location-independent** stable
identifier. It is the authoritative handle for external references — report links,
saved searches, suppressions, cross-scan comparison and (future) agent tool calls.
`Entity.id()` **is** the stable id; `Entity.stableId()` returns the same value.

## Why

Identifiers must survive edits. An id that embeds a line number changes whenever
code moves, breaking incremental updates, suppressions and comparisons. The stable
id depends only on language, kind and qualified name — never on line, column,
timestamp, database key or absolute path. Source locations are retained as
**evidence**, not identity.

## Grammar

```
stableId := "file:" <relativePath>
          | "project:" <name>
          | <lang> ":" <kindToken> ":" <qualifiedName>

lang       := "java" | "ada" | "code"        (code = language-neutral/unknown)
kindToken  := type | method | constructor | function | procedure
            | field | variable | package | namespace | task | exception | ...
```

`type` covers all type-like kinds (class, interface, enum, record, annotation,
Ada type). Files and the project root are special-cased and carry no language
token; the project id uses the repository **folder name** (a label), never its
absolute path.

## Examples

### Java
```
file:src/main/java/com/example/CustomerService.java
java:type:com.example.CustomerService
java:type:com.example.Outer.Inner                     (nested class)
java:method:com.example.CustomerService#findCustomer(String)
java:constructor:com.example.CustomerService#CustomerService()
java:field:com.example.CustomerService#repository
```

### Ada
```
ada:package:Navigation
ada:package:Navigation.Sensors                        (child package — distinct)
ada:type:Navigation.Route_Record
ada:procedure:Navigation.Reset                        (no parameters)
ada:function:Navigation.Find_Path(Integer)            (one parameter)
ada:function:Navigation.Find_Path(Integer,Integer)    (overload — distinct)
```

### Lineage entities
```
java:endpoint:POST:/api/customers/{id}/orders          (verb + normalized path)
java:endpoint:GET:{unresolved:Paths.BASE}               (non-literal path, kept unresolved)
sql:table:customer                                      (database table)
ada:variable:Mission_Data.Current_Route                 (package state)
ada:source:console_input                                (data source — Ada.Text_IO input)
ada:sink:console_output                                 (data sink — Ada.Text_IO output)
```
DTOs deliberately remain ordinary `java:type:…` entities with a `role` attribute
(`dto-request` / `dto-response`) — no duplicate logical entities are created.

## Normalization

- **Java method/constructor parameters** use the parameter type's source spelling
  with whitespace removed (e.g. `Map<String,Integer>`). Full type resolution to
  fully-qualified names is out of scope (no classpath symbol solver), so the
  profile is deterministic but reflects the source spelling.
- **Ada subprogram parameters** are normalized to a comma-separated type profile:
  defaults (`:= …`) and parameter modes (`in`, `out`, `aliased`, `constant`) are
  stripped, whitespace collapsed, and multi-name declarations (`A, B : Integer`)
  expanded per name. A parameterless subprogram has no parentheses.
- Qualified names are case-sensitive as written. (Ada is case-insensitive as a
  language; identifiers are kept in their declared spelling for stability.)

## Overloads

Overloaded methods/subprograms are distinguished by their normalized parameter
profile, so `Find_Path(Integer)` and `Find_Path(Integer,Integer)` have different
ids and are never merged. Return-type-only overloading of Ada functions is **not**
distinguished (a known limitation).

## Ada specification / body merge

An Ada package (or subprogram, or type) declared in a `.ads` specification and
implemented in a `.adb` body produces the **same** stable id, so the two collapse
into one logical entity. The merge:

- OR-s exposure (visible in the spec → externally exposed);
- takes the maximum of complexity and lines of code;
- keeps SPARK contracts and descriptive attributes from whichever unit supplied them;
- records **both** evidences via attributes:
  `hasSpec`, `hasBody`, `specLocation`, `bodyLocation`;
- prefers the specification as the canonical `location()`.

A spec-only or body-only declaration is represented honestly: `hasBody=false` for a
spec with no implementation, `hasSpec=false` for a body-local helper with no visible
specification. Child packages (`Navigation.Sensors`) remain distinct from their
parent.

## Collision behavior

Two **distinct** declarations that resolve to the same id (e.g. a duplicate
qualified name across files, or two bodies the parser could not tell apart) are
**never silently overwritten**. The first is kept and a
`STABLE_ID_COLLISION` diagnostic is recorded — including both source locations —
and surfaced in the JSON report (`diagnostics`) and the CLI summary. Legitimate
merges (spec/body, package aggregation, idempotent re-adds) are not collisions.

Migration/conflict rules at a glance:

| Case | Behavior |
|---|---|
| Java nested class | qualified name includes the outer type → distinct id |
| Java overloaded methods | distinguished by parameter profile |
| Java constructors | `constructor` kind token + parameter profile |
| Java anonymous classes | not modeled as separate entities |
| Ada overloaded subprograms | distinguished by normalized parameter profile |
| Ada child packages | dotted qualified name → distinct id |
| Ada renames | modeled as a `RENAMES` relationship, not a new identity |
| Duplicate qualified names | collision diagnostic; first kept, both evidences retained |
| Invalid/partial source | parsed best-effort; unparseable declarations are skipped |

## Migration implications

- The stable id is now the entity's primary identity and the value stored in the
  H2 index. Because no external artifact previously depended on the old
  location-embedded id format, this is a clean switch rather than a dual-id
  migration; `stableId` is additionally exposed in JSON/CSV findings for external
  consumers. If a persisted index from a prior format is encountered, re-index
  (the index is a cache, not a system of record).

## Known limitations

- Parameter types are source-spelled, not fully qualified (no symbol solver).
- Ada return-type-only overloads are not distinguished.
- The `PROJECT` id is the repository folder name; it is a label, not a
  cross-machine-stable identity.
