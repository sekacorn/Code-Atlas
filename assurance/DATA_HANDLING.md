# Data Handling

## Data inventory

| Data | Source | Stored or emitted | Sensitivity |
|---|---|---|---|
| Source and configuration bytes | Selected repository | Read in process memory; not intentionally copied into reports | Same as source repository |
| File paths and SHA-256 hashes | Scanner | H2 index and reports | May reveal project structure and change state |
| Entities and relationships | Parsers and linker | H2 index and reports | Architecture and implementation metadata |
| SQL tables, configuration keys, endpoints, lineage | Static analysis | H2 index and reports | May reveal data model and integration design |
| Diagnostics and unresolved references | Parsers and linker | H2 index and reports | May reveal identifiers and source locations |
| Build and release metadata | Build system | SBOM, manifest, checksums, attestations | Generally releasable, but may expose build paths if not controlled |

## Collection and purpose

Code Atlas reads repository files solely to build the local static-analysis model.
It does not require user accounts, telemetry identifiers, API tokens, or cloud
services. It does not intentionally collect personal information, but source code,
comments, paths, identifiers, configuration keys, and SQL names can contain it.

## Storage and transmission

- Persistent scan facts are stored in a local H2 index unless `--in-memory` is
  selected.
- Reports and onboarding packages are written to directories selected by the
  operator.
- Core operation does not transmit repository or index data.
- The optional explorer sends rendered metadata only to a browser on the same
  host through loopback HTTP.
- Release builds may access configured Maven repositories, but they do not analyze
  a target repository as part of dependency resolution.

## Retention and deletion

Code Atlas does not enforce a retention period. The system owner should define one
for indexes, reports, build logs, and onboarding packages. To remove Code Atlas
data, stop any explorer process, delete the selected report directories, and
delete the corresponding H2 index directory. Apply normal backup and media
sanitization rules because deleted local files may remain in backups or snapshots.

## Access control

- Restrict repository access to authorized users.
- Place indexes and reports in user-specific or access-controlled directories.
- Avoid shared temporary directories.
- Do not publish reports automatically; review their content first.
- Treat outputs at least as carefully as architecture documentation for the source
  repository.

## Logging

Default logs report paths, counts, timing, parser problems, and operational errors.
They are not designed to contain entire source files. Paths and diagnostic text may
still be sensitive. Redirect logs only to approved storage and apply the same
retention review as other analysis output.

## Data-lineage interpretation

Lineage results show statically supported paths with edge evidence, confidence,
resolution state, and gaps. They can demonstrate that the indexed source contains
a supported path from an origin through transformations to a sink. They cannot
prove that every runtime value took that path or that no unobserved path exists.
For critical flows, combine Code Atlas evidence with tests, runtime traces, database
audit records, or other environment evidence.
