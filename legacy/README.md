# Legacy implementation map

The root `src/` and `astrobox-plugin/` contain the historical Electron and AstroBox
implementation. The root `package.json` and related Electron tooling still exercise
these paths in Node tests and historical builds. They are not the current Windows,
Android, or Band 9 Pro product path.

This cleanup only marks the boundary. It leaves `src/`, `astrobox-plugin/`,
`package.json`, `package-lock.json`, `build/`, and `scripts/` in place to avoid broad,
unrelated build-path changes. A later legacy migration can be scoped and tested
separately if needed.
