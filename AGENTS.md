# AGENTS.md

## Cursor Cloud specific instructions

This repository currently contains **no application code** on the `master` branch — only
placeholder text files (`readme`, `branch1readme`). There is therefore:

- No package manager, language runtime project, or dependency manifest to install
  (no `package.json`, `requirements.txt`, `go.mod`, `Cargo.toml`, `Dockerfile`, CI config, etc.).
- Nothing to lint, test, build, or run. Environment setup is effectively a no-op.

If/when real application code is added, update this section with the actual services,
their dev/run commands, and how to lint/test/build them, and replace the no-op update
script with the real dependency-install steps.

The base VM provides common runtimes (e.g. Node and Python 3) should they be needed for
future work.
