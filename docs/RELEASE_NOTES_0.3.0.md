# Code Atlas 0.3.0

Code Atlas 0.3.0 is a security, assurance, and release-integrity update for
controlled and offline environments.

## Highlights

- Added a neutral assurance package with a product profile, threat model,
  security architecture, data-handling statement, deployment hardening guide,
  secure-development practices, vulnerability process, and test-evidence guide.
- Added global `--hardened` operation. It disables the optional explorer listener
  and applies conservative file-size, file-count, aggregate-byte, duration, and
  worker limits.
- Added fail-closed scan resource controls and loopback Host-header validation.
- Added CycloneDX 1.6 SBOM generation, third-party notices, reproducible JAR
  verification, release manifests, SHA-256 sidecars, provenance attestations, and
  optional detached GPG signing.
- Added Java 21 Linux and Windows CI, SpotBugs, JaCoCo, CodeQL, dependency review,
  OSV vulnerability scanning, Gitleaks, and parser fuzz testing.
- Added focused comments around complex deterministic and concurrency behavior.
- Standardized Markdown punctuation and removed decorative symbols.

The release baseline completed 220 tests with 0 failures, 0 errors, and 1
intentional platform-dependent skip. SpotBugs reported 0 findings at medium
confidence or higher.

## Release contents

Each platform archive contains the same Java 21 application, launchers, full
documentation, assurance package, Apache License 2.0, third-party notices,
CycloneDX SBOM, build information, and screenshots.

- Red Hat compatible Linux: `code-atlas-0.3.0-rhel-linux.tar.gz`
- Ubuntu Linux: `code-atlas-0.3.0-ubuntu-linux.tar.gz`
- Debian Linux: `code-atlas-0.3.0-debian-linux.tar.gz`
- Windows 10: `code-atlas-0.3.0-windows10.zip`

The archives are portable packages, not native RPM, DEB, or MSI installers. A
maintained Java 21 runtime must be installed separately.

## Verification

Verify `release-manifest.json`, every SHA-256 sidecar, and the release attestation
or detached signature before installation. Then follow
`assurance/DEPLOYMENT_HARDENING.md` and record the exact installed version and
hash.

Static-analysis findings remain bounded by parser coverage, unresolved references,
dynamic behavior, generated code, native code, and external systems. Review
`KNOWN_LIMITATIONS.md` and coverage diagnostics with every result.
