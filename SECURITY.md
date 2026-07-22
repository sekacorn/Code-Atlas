# Security Policy

## Supported versions

Security fixes are applied to the latest published release. Older versions may not
receive fixes. Deployment owners should track the exact installed version and review
new releases promptly.

## Report a vulnerability

Please report suspected vulnerabilities privately through the repository's
[security advisory form](https://github.com/sekacorn/Code-Atlas/security/advisories/new).
If private vulnerability reporting is unavailable, contact the repository owner
through a private channel before sharing technical details.

Do not open a public issue containing exploit steps, sensitive source, credentials,
or unredacted logs. Include the affected version, operating system and Java version,
reproduction steps, impact, required privileges, and any suggested mitigation.

The project will validate the report, assess affected versions, prepare a fix and
regression test when practical, and coordinate disclosure. See
[Vulnerability Management](assurance/VULNERABILITY_MANAGEMENT.md) for the complete
process.

## Operational security

Verify release hashes and provenance before installation, run with least privilege,
protect indexes and reports like source-derived architecture data, and use
`--hardened` for controlled or unattended operation. Deployment guidance is in
[Deployment Hardening](assurance/DEPLOYMENT_HARDENING.md).
