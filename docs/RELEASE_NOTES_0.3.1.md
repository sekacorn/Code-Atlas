# Code Atlas 0.3.1

Code Atlas 0.3.1 is a focused explorer usability release.

## Highlights

- Added an interactive graph viewer to `atlas serve` for dependency, call,
  dead-code, and architecture graphs.
- Added zoom in, zoom out, reset, mouse-wheel zoom, drag panning, and keyboard
  graph navigation while preserving the static, script-free `atlas graph` SVG
  export.
- Kept the explorer offline and self-contained: the viewer is implemented in the
  existing nonce-authorized inline script with no new runtime dependencies.
- Updated status and limitations documentation to distinguish the interactive
  explorer view from deterministic static CLI graph exports.

## Verification

- `mvn -pl atlas-ui -am test`: build success.
- Release archives should be verified with `scripts/verify-release.ps1` before
  installation or publication.
