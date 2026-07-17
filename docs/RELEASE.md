# Code Atlas Release Guide

This project is distributed as a self-contained Java command-line tool for
controlled environments. The release artifacts contain the runnable jar, local
launcher scripts, checksums, and this deployment guidance.

## Supported release targets

- Red Hat compatible Linux: `code-atlas-<version>-rhel-linux.tar.gz`
- Ubuntu Linux: `code-atlas-<version>-ubuntu-linux.tar.gz`
- Debian Linux: `code-atlas-<version>-debian-linux.tar.gz`
- Windows 10: `code-atlas-<version>-windows10.zip`

The application is Java-based, so **the packaged jar is byte-identical across all
targets** — the archives differ only in format (`.tar.gz` vs `.zip`) and in which
launcher you will use. They exist to make review, transfer and local installation
straightforward for each operating system family, not because the code differs.

These are portable archives, not native installers: there is no RPM, DEB or MSI,
and no bundled Java runtime. Each archive needs a JDK 21 already on `PATH`.

## Runtime requirements

- JDK 21 or newer on `PATH`
- Maven 3.9 or newer only when building from source
- No network access is required at any point after the jar is built
- No administrator privileges are required
- No database, service or container is required — the index is a local file

## Build and package

From the repository root:

```powershell
.\scripts\package-release.ps1
```

To package a specific version label:

```powershell
.\scripts\package-release.ps1 -Version 0.2.0
```

Artifacts are written to `dist/`.

## Verify artifacts

Each archive is accompanied by a `.sha256` file. On Linux:

```bash
sha256sum -c code-atlas-0.2.0-rhel-linux.tar.gz.sha256
```

On Windows PowerShell:

```powershell
Get-FileHash .\code-atlas-0.2.0-windows10.zip -Algorithm SHA256
Get-Content .\code-atlas-0.2.0-windows10.zip.sha256
```

## Local operation

Linux:

```bash
tar -xzf code-atlas-0.2.0-rhel-linux.tar.gz     # or ubuntu / debian
cd code-atlas-0.2.0
./atlas.sh                       # menu: build, scan, report, onboard, explore
./atlas.sh scan /path/to/repo    # or drive it directly
```

Windows 10:

```powershell
Expand-Archive .\code-atlas-0.2.0-windows10.zip
cd .\code-atlas-0.2.0-windows10\code-atlas-0.2.0
.\atlas.ps1 scan C:\path\to\repo
```

Reports are generated locally. The optional report server binds to
`127.0.0.1` only.

## Review notes

- Static analysis is deterministic and evidence-based.
- The core product does not require cloud services or AI.
- Persistent indexes are file-backed H2 databases stored outside the scanned
  repository by default.
- Findings that involve uncertainty expose confidence and resolution status
  rather than making absolute claims.
- The explorer (`atlas serve`) is a read-only view bound to loopback with no
  authentication, because there is no boundary to authenticate across. It must not
  be placed behind a proxy or bound to a routable interface.
- Nothing in an archive reaches the network: the reports and the explorer embed
  their CSS and script inline and load nothing from any host.
- `KNOWN_LIMITATIONS.md` ships inside every archive and states plainly what the
  tool does not do and does not claim.
