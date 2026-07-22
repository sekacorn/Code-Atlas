# Secure Development

## Development principles

- Keep core analysis deterministic and offline.
- Treat repository content as untrusted parser input.
- Preserve uncertainty and evidence instead of inventing relationships.
- Require explicit review for network, privilege, persistence, parser, and release
  boundary changes.
- Keep dependencies pinned and minimize runtime dependencies.
- Add focused tests for every defect and security control.

## Change workflow

1. Develop on a reviewed branch with a narrowly scoped change.
2. Update tests, threat-model entries, and documentation when behavior changes.
3. Run `mvn -B -ntp clean verify` on Java 21.
4. Review static-analysis, dependency, secret-scanning, and SBOM results.
5. Require human review before merging release-impacting changes.
6. Build releases from a clean, tagged commit through the release workflow.
7. Publish checksums, manifest, SBOM, provenance, and signatures with artifacts.

## Automated controls

The repository workflows provide:

- Linux and Windows build and test execution on Java 21
- SpotBugs static analysis at medium confidence or higher
- JaCoCo coverage reports
- CodeQL analysis
- Dependency review and OSV vulnerability scanning
- Gitleaks secret scanning
- CycloneDX SBOM generation
- Reproducible-build verification
- Release provenance and SBOM attestations
- Weekly automated update proposals for Maven dependencies and hosted actions
- Explicit ownership for release, security, legal, and assurance controls

Workflow actions are pinned to immutable commit hashes. Version updates should be
reviewed like dependency changes.

## Parser robustness

Parsers must return typed facts and diagnostics for malformed input rather than
crashing the process. Deterministic malformed-input regression tests run in the
normal build. A dedicated Jazzer profile exercises parser entry points with
coverage-guided fuzzing for time-bounded campaigns.

## Dependency management

- Runtime and test dependencies have explicit versions in the root Maven build.
- Maven Enforcer checks the required Java and Maven versions and rejects dynamic or
  snapshot dependency versions for release builds.
- CycloneDX records direct and transitive components.
- Automated vulnerability output is triaged for exploitability and reachability.
- A vulnerable dependency is updated, removed, or documented with a time-bounded
  exception and compensating controls.

## Reproducibility and provenance

Archive timestamps are derived from the source commit where the build environment
supports it. The release process records the commit, tool versions, artifact names,
sizes, and SHA-256 hashes. Two clean builds of the same commit should produce the
same shaded JAR. Platform archive bytes can differ when the packaging tool does not
offer deterministic metadata; the manifest still identifies each published byte
sequence.

## Manual review expectations

Automated tools do not replace review. Changes to parsers, HTML rendering, SQL
persistence, file traversal, archive construction, workflow permissions, or
cryptographic identity require a reviewer familiar with the affected boundary.
