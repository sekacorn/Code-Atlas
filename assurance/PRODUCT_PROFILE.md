# Product Profile

## Identity

| Field | Value |
|---|---|
| Product | Code Atlas |
| Publisher | Code Atlas project maintainers |
| Repository | `https://github.com/sekacorn/Code-Atlas` |
| License | Apache License 2.0 |
| Product type | Offline-first source-code indexing and static-analysis command-line application |
| Implementation | Java 21, Maven multi-module build |
| Primary interfaces | Command line, generated HTML/JSON/CSV/DOT reports, optional loopback-only explorer |
| Supported source inputs | Java, Ada, SQL/DDL, Maven, Gradle, GNAT project files, and selected configuration formats |

The exact product version is stored in the root `pom.xml`, printed by
`atlas --version`, and recorded in each release manifest. A reviewer should
evaluate one exact version and checksum, not the moving default branch.

## Intended use

Code Atlas helps developers orient themselves in unfamiliar Java and Ada
repositories. It inventories source structures, builds an evidence-bearing
relationship graph, reports complexity and possible dead code, calculates
change impact, and traces supported static data-lineage relationships.

The application can provide meaningful onboarding and investigation support when
its coverage report is reviewed with the findings. It is not a compiler, runtime
monitor, dynamic taint tracker, formal verifier, or substitute for source review.

## Supported release targets

- Red Hat Enterprise Linux compatible systems with a Java 21 runtime
- Ubuntu Linux with a Java 21 runtime
- Debian Linux with a Java 21 runtime
- Windows 10 with a Java 21 runtime and PowerShell

Release archives contain the same platform-independent Java application plus
platform launch scripts. Java 21 is an external prerequisite and is not bundled.
The operating system, Java distribution, update level, and lifecycle remain the
responsibility of the deploying organization.

## Runtime dependencies

The distributed application includes Java libraries for Java parsing, embedded
H2 indexing, command-line parsing, and logging. The generated CycloneDX SBOM and
`THIRD_PARTY_NOTICES.txt` identify exact versions and licenses. No application
dependency is downloaded at runtime.

## Privileges and connectivity

- Administrative privileges are not required.
- Normal analysis requires read access to the target repository and write access
  to the selected index and report directories.
- Core scan, analysis, reporting, tool, and onboarding operations do not require
  network access.
- The optional explorer listens only on the local loopback interface and serves
  read-only views from the completed index.
- Hardened mode disables the explorer listener and applies stricter scan limits.

## Ownership fields for an evaluation record

Complete these outside the source tree for each deployment:

| Field | Required record |
|---|---|
| Business or engineering owner | Named accountable owner |
| Technical maintainer | Team or individual responsible for updates |
| Security contact | Monitored private reporting channel |
| Installed version and hash | Exact version plus SHA-256 |
| Installation locations | Hosts, images, or managed software catalog entry |
| Associated systems | Repositories and workflows that consume the tool |
| User population | Authorized roles or groups |
| Support period | Review and replacement date |
| Alternatives considered | Existing tools and reason for selection |
| Cost and license record | Apache-2.0 terms and any internal support cost |

## Support lifecycle

The project currently publishes versioned releases but does not promise a fixed
long-term-support period. Deployments should define an update owner, review new
releases and security advisories, and remove unsupported versions according to
local policy.
