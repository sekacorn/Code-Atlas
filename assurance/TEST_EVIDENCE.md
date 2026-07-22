# Test Evidence

## Build prerequisites

- Java Development Kit 21
- Apache Maven 3.9 or later
- Git
- PowerShell 7 for release packaging and manifest scripts
- GPG only when creating or verifying optional detached signatures

Run verification from a clean checkout of the exact tag under review. Record the
commit id, operating system, Java version, Maven version, command, exit status, and
artifact hashes with the review record.

## Core verification

```bash
git status --short
git rev-parse HEAD
java -version
mvn -version
mvn -B -ntp clean verify
```

A successful build produces unit and integration test reports, SpotBugs output,
JaCoCo coverage reports, and `target/bom.json`.

The 0.3.0 release baseline completed 220 tests with 0 failures, 0 errors, and 1
intentional platform-dependent skip. SpotBugs reported 0 findings at medium
confidence or higher, and the generated CycloneDX 1.6 runtime SBOM contained 32
components.

## Parser fuzz campaign

Run a time-bounded Jazzer campaign in an isolated build environment:

```bash
mvn -B -ntp -Pparser-fuzz -pl atlas-core -am test
```

Preserve any crashing input with the defect report and convert it to a deterministic
regression test before closing the issue.

## Package and manifest

```powershell
pwsh ./scripts/package-release.ps1
pwsh ./scripts/verify-release.ps1 -DistPath ./dist
```

The verifier checks every artifact listed in `dist/release-manifest.json`, validates
SHA-256 sidecar files, and confirms that required evidence files are present in each
archive.

## Reproducible JAR check

```powershell
pwsh ./scripts/verify-reproducible-build.ps1
```

This performs two clean Maven package builds from the same source state and compares
the SHA-256 hash of the shaded `atlas.jar`.

## Software bill of materials

Review `target/bom.json` or the versioned `.cdx.json` file in the release directory.
Confirm that its root component version matches the release and that runtime
dependencies match `THIRD_PARTY_NOTICES.txt`.

## Static and dependency analysis

The hosted workflows retain CodeQL, SpotBugs, dependency-review, OSV, Gitleaks, and
test evidence. For disconnected review, export the workflow evidence and place it
with the exact release manifest. Findings require triage; tool completion alone is
not evidence that no defect exists.

## Functional acceptance checks

Use a representative, non-sensitive Java and Ada repository:

```bash
atlas --hardened scan /path/to/repository --out /tmp/atlas-report
atlas orient --repo /path/to/repository
atlas onboard /path/to/repository --out /tmp/atlas-onboarding
atlas investigate "POST /example" --repo /path/to/repository
atlas tool get_repository_summary --repo /path/to/repository
atlas tool get_diagnostics --repo /path/to/repository
```

Confirm that reports open offline, results carry a scan id, evidence points to real
file locations, unresolved and ambiguous references are visible, and the reported
coverage is acceptable for the intended decision.

## Data trace evidence

For an endpoint, table, Ada variable, or stable entity id:

```bash
atlas investigate sql:table:customer --repo /path/to/repository
atlas tool trace_data_lineage --id sql:table:customer --direction upstream --repo /path/to/repository
atlas tool trace_data_lineage --id sql:table:customer --direction downstream --repo /path/to/repository
atlas tool get_source_evidence --id sql:table:customer --repo /path/to/repository
```

Retain the command, version, scan id, structured result, and referenced source lines.
Treat the output as static evidence with known gaps, not proof of runtime execution.
