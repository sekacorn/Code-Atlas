# Code Atlas - Persistence

_Created with the persistent-scan milestone (this document did not exist before;
earlier versions of Code Atlas defaulted to in-memory storage)._

Code Atlas persists its index in an **embedded, file-backed H2 database** - no
server, no administrator privileges, fully offline.

## Where the index lives

- CLI default: `~/.code-atlas/index/<repo-name>-<hash8>/atlas` (the hash of the
  absolute repository path keeps same-named repositories apart).
- `--index <path>` chooses an explicit location.
- `--in-memory` runs an explicit temporary session (nothing persists).
- The index is **never written inside the analyzed repository** - analysis leaves
  the analyzed tree untouched (verified by characterization tests).
- Unit and integration tests use in-memory or temp-directory stores.

## What is stored (schema v3)

| Table(s) | Contents |
|---|---|
| `scan` | one row per scan run: content-derived scan key, status (`IN_PROGRESS` / `COMPLETED` / `FAILED`), root, file count, timestamps |
| `entity`, `entity_attr` | the model snapshot of the latest completed scan |
| `relationship`, `relationship_attr` | edges **with resolution status, source location and attributes** (rule ids, confidence, ...) |
| `file_hash` | SHA-256 per file, the basis of incremental change detection |
| `cache_file`, `cache_entity(_attr)`, `cache_rel(_attr)` | the parse cache: exact per-file parser output keyed by content hash + parser id + parser version |
| `diagnostic` | scan-time diagnostics (e.g. stable-id collisions), persisted with the snapshot |
| `meta` | schema version and store metadata |

## Scan lifecycle and failure safety

1. `beginScan` records an `IN_PROGRESS` row (committed immediately, so crashes
   leave an honest trace).
2. The model is built fully in memory.
3. `persistCompletedScan` replaces the snapshot, hashes and cache updates and
   marks the scan `COMPLETED` - **in a single transaction**. Any failure rolls
   back: the previous completed scan remains current, intact and queryable.
4. A scan that throws is marked `FAILED`; `latestCompletedScan()` never returns it.

Scan identifiers are **content-derived** (`scan-<12 hex of SHA-256 over sorted
path:hash pairs>`): identical repository content always produces the identical
scan id, so deterministic-output guarantees extend to structures that embed it.
Run history (including failed runs) is kept in the `scan` table; only the *model
snapshot* of the latest completed scan is retained (a known limitation - see
KNOWN_LIMITATIONS.md).

## Read-only handles

`AtlasStore.atPathReadOnly` opens an existing index with H2's
`ACCESS_MODE_DATA=r`: the engine rejects every write. This is the storage-level
guarantee behind the agent tool API. Read-only handles never create or migrate a
schema - a version mismatch is reported with instructions to rescan.

## Schema migration

The index is a cache, not a system of record. On open, a store created by a
different schema version is **dropped and rebuilt** (clean reindex); the next scan
repopulates it. No destructive migration of user data is involved because the
source of truth is always the repository itself.
