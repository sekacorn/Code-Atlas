# Deployment Hardening

## Installation checklist

1. Obtain one versioned release archive from the project release channel.
2. Verify the archive SHA-256 against `release-manifest.json`.
3. Verify the build provenance attestation or detached GPG signature using an
   independently trusted maintainer identity.
4. Review the CycloneDX SBOM and vulnerability-scan results for the exact release.
5. Install a maintained Java 21 runtime from an approved internal source.
6. Extract Code Atlas to a non-shared, non-world-writable application directory.
7. Run under a dedicated least-privilege user or the authorized developer account.
8. Give the process read access only to intended repositories and write access only
   to dedicated index and report directories.
9. Run the verification commands in [TEST_EVIDENCE.md](TEST_EVIDENCE.md).
10. Record version, checksum, owner, locations, exception decisions, and review date.

## Recommended execution

Use hardened mode for unattended runs and restricted workstations:

```powershell
.\atlas.ps1 --hardened scan C:\src\project `
  --index C:\code-atlas-data\project\atlas `
  --out C:\code-atlas-data\project\report
```

```bash
./atlas.sh --hardened scan /srv/src/project \
  --index /var/lib/code-atlas/project/atlas \
  --out /var/lib/code-atlas/project/report
```

Hardened mode disables the loopback explorer and applies conservative defaults for
file size, file count, total accepted bytes, worker threads, and scan duration.
Explicit resource options may reduce those values further.

## Operating controls

- Prefer an isolated or network-restricted execution environment.
- Do not run as root, an administrator, or a privileged build account.
- Use a read-only repository mount where the platform supports it.
- Keep index and report paths outside the source repository.
- Apply operating-system access control and encryption to analysis outputs.
- Disable or restrict outbound network access because normal operation needs none.
- Monitor disk, memory, CPU, and process duration for automated scans.
- Patch the operating system and Java runtime through the normal maintenance cycle.
- Remove old releases after the supported transition and evidence-retention period.

## Optional explorer

The explorer is intended for interactive single-user use on a trusted workstation.
It is not a remotely hosted service and must not be exposed through port forwarding,
reverse proxies, containers with published ports, or shared remote desktop gateways.
Use generated HTML reports where a listener is not acceptable.

## Repository input precautions

- Treat every repository as untrusted input.
- Keep symbolic-link following disabled unless the link targets have been reviewed.
- Use explicit limits for unusually large or generated repositories.
- Do not scan mounted secrets directories, home directories, or system roots.
- Review parser diagnostics and partial-coverage indicators before relying on a
  report.

## Backup and recovery

The source repository is the system of record. Indexes and reports can be
regenerated and generally do not require backup unless they are retained as review
evidence. If retained, protect and test recovery of them under the same controls as
other source-derived engineering records.
