# Code Atlas - Incremental Analysis

_Created with the persistent-scan milestone._

Every scan compares the repository's current per-file SHA-256 hashes against the
persisted index and classifies each file as **added**, **changed**, **removed** or
**unchanged**. On top of that classification the pipeline reuses work
conservatively.

## Conservative reuse of parser results

A file's cached parser facts (its exact entities and relationships, pre-linking)
are reused **only when all of these match**:

1. the file's **content hash** is unchanged,
2. the **parser id** that would handle it today equals the one that produced the
   cached facts, and
3. that parser's **version** is unchanged.

Anything else - edited content, a newly installed parser claiming the file, a
parser upgrade - forces a re-parse. Reuse is required to be indistinguishable from
re-parsing: the cache stores the parser output verbatim (including source
locations with columns, attributes, and unresolved references), and tests assert
that a fully-reused scan produces an identical model and identical scan id.

## What is never cached

- **Cross-reference linking**, **lineage rules** and **analysis** run fresh on
  every scan over the merged model - they are cheap, deterministic, and depend on
  the whole repository, not single files. (Lineage edges are then persisted with
  the scan snapshot, so `atlas lineage` reads them without rescanning.)
- The `FILE` entities' line statistics are cached in the parse-cache header so
  unchanged files need not even be re-read.

## Change semantics

| Event | Effect |
|---|---|
| File added | parsed, facts cached, model gains its entities |
| File changed | re-parsed; stale facts replaced in cache and model |
| File deleted | its facts are absent from the new snapshot; its cache rows are pruned |
| Nothing changed | all files reused; identical model and scan id |
| Scan fails | previous completed snapshot remains current and queryable |

## Current limitations

- The model snapshot is rebuilt in memory each scan (reuse avoids re-parsing, not
  re-merging). Fine at current scale; revisit for very large repositories.
- Only the latest completed scan's model is retained; older scans keep metadata
  (id, key, status) but not their snapshots, so cross-scan model diffing is not
  yet possible.
