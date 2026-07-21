# Code Atlas 0.2.2

Code Atlas 0.2.2 is a maintenance and packaging release for controlled, offline environments.

## Highlights

- Added documented screenshots for the Explorer and generated analysis reports.
- Added the Apache License 2.0 to the source tree, distribution archives, and release assets.
- Added separate archives for RHEL-compatible Linux, Ubuntu, Debian, and Windows 10.
- Neutralized spreadsheet formula prefixes in generated CSV reports.
- Strengthened XML external-entity restrictions for Maven and configuration parsing.
- Hardened Graphviz DOT escaping for identifiers, labels, and control characters.
- Opened lineage indexes with database-enforced read-only access.
- Made call-graph edge deduplication linear in the number of relationships.
- Made configured worker limits apply to parsing and bounded the default worker count.
- Corrected symbolic-link traversal and interruption handling in repository scanning.

## Verification

- `mvn clean verify`: 205 tests, 0 failures, 0 errors, 1 platform-dependent symbolic-link test skipped on Windows.
- Runtime dependencies checked against the OSV advisory database at release time; no advisories were returned for the resolved versions.
- SHA-256 checksum files are provided for every platform archive.

Code Atlas remains a static-analysis aid. Its findings should be reviewed with source code, tests, build output, and runtime evidence before operational decisions are made.
