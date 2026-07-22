# Code Atlas Assurance Package

This directory collects the material needed to evaluate Code Atlas for use in
controlled environments. It is written for technical reviewers, system owners,
operators, and maintainers. It does not claim certification, authorization, or
fitness for a particular environment.

## Package contents

| Document | Purpose |
|---|---|
| [Product profile](PRODUCT_PROFILE.md) | Product identity, supported platforms, dependencies, owners, and use cases |
| [Threat model](THREAT_MODEL.md) | Assets, trust boundaries, threats, controls, and residual risks |
| [Security architecture](SECURITY_ARCHITECTURE.md) | Components, data flow, privilege model, and network behavior |
| [Data handling](DATA_HANDLING.md) | Inputs, outputs, retention, sensitivity, and deletion |
| [Deployment hardening](DEPLOYMENT_HARDENING.md) | Installation and operating guidance for restricted systems |
| [Secure development](SECURE_DEVELOPMENT.md) | Build, review, testing, dependency, and release practices |
| [Vulnerability management](VULNERABILITY_MANAGEMENT.md) | Intake, triage, remediation, disclosure, and release response |
| [Test evidence](TEST_EVIDENCE.md) | Reproducible verification commands and expected evidence |

The repository also provides [SECURITY.md](../SECURITY.md), an Apache-2.0
[LICENSE](../LICENSE), [third-party notices](../THIRD_PARTY_NOTICES.txt), a
CycloneDX software bill of materials generated during the build, SHA-256 release
checksums, and a machine-readable release manifest.

## Suggested review order

1. Confirm the exact release version and SHA-256 checksum.
2. Review the product profile and supported use case.
3. Review the threat model, security architecture, and data-handling statement.
4. Compare deployment hardening guidance with the target system controls.
5. Reproduce the build and test evidence in an isolated build environment.
6. Review the CycloneDX SBOM, third-party notices, static-analysis output, and
   dependency scan results.
7. Record environment-specific risks, compensating controls, and the decision
   owner outside this repository.

## Evidence boundaries

Code Atlas performs static analysis. Its results are evidence-backed but are not
proof that every runtime data path, dynamically loaded component, generated
source, reflection target, native call, or external integration has been found.
Coverage, unresolved references, ambiguity, and parser diagnostics are preserved
in the reports so reviewers can see those limits.
