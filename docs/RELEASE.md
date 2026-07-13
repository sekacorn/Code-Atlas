# Code Atlas Release Guide

This project is distributed as a self-contained Java command-line tool for
controlled environments. The release artifacts contain the runnable jar, local
launcher scripts, checksums, and this deployment guidance.

## Supported release targets

- Red Hat compatible Linux: `code-atlas-<version>-rhel-linux.tar.gz`
- Ubuntu Linux: `code-atlas-<version>-ubuntu-linux.tar.gz`
- Debian Linux: `code-atlas-<version>-debian-linux.tar.gz`
- Windows 10: `code-atlas-<version>-windows10.zip`

The application is Java-based, so the packaged jar is identical across targets.
The target-specific archives exist to make review, transfer, and local
installation straightforward for each operating system family.

## Runtime requirements

- JDK 21 or newer on `PATH`
- Maven 3.9 or newer only when building from source
- No network access is required for normal runtime after the jar is built
- No administrator privileges are required

## Build and package

From the repository root:

```powershell
.\scripts\package-release.ps1
```

To package a specific version label:

```powershell
.\scripts\package-release.ps1 -Version 0.1.0
```

Artifacts are written to `dist/`.

## Verify artifacts

Each archive is accompanied by a `.sha256` file. On Linux:

```bash
sha256sum -c code-atlas-0.1.0-ubuntu-linux.tar.gz.sha256
```

On Windows PowerShell:

```powershell
Get-FileHash .\code-atlas-0.1.0-windows10.zip -Algorithm SHA256
Get-Content .\code-atlas-0.1.0-windows10.zip.sha256
```

## Local operation

Linux:

```bash
tar -xzf code-atlas-0.1.0-ubuntu-linux.tar.gz
cd code-atlas-0.1.0
./atlas.sh scan /path/to/repo
```

Windows 10:

```powershell
Expand-Archive .\code-atlas-0.1.0-windows10.zip
cd .\code-atlas-0.1.0-windows10\code-atlas-0.1.0
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
