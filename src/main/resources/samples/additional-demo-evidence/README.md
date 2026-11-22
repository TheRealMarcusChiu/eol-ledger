# Additional demo repositories: native build and resolver evidence

`payments-service` (DEMO-1003) and `reporting-service` (DEMO-1004) are real Git repositories with branches `main` and
`release/legacy`, generated, built and resolved by
`/root/projects/eol-ledger-ops/drag-preview/additional-demo/generate.py`. Their four entries were appended to
`../demo-dependency-inventory.json` after the original four, whose bytes are unchanged (see `release.md`).

| Repository | Module | `main` | `release/legacy` | Native commands |
|---|---|---|---|---|
| payments-service | payments-api | spring-context 7.0.11 | spring-context 5.3.39 | `mvn clean package`, `dependency:tree` (JSON + text, maven-dependency-plugin 3.8.1), `java -cp` smoke run |
| payments-service | payments-common | commons-lang3 3.17.0 | commons-lang3 3.17.0 | same |
| reporting-service | report-api | Django 5.2.19 | Django 3.2.25 | `uv lock --python 3.11`, `uv sync --frozen`, `uv tree --no-dedupe` (active env and `--universal`), `compileall`, import smoke |
| reporting-service | report-worker | requests 2.32.5 | requests 2.32.5 | same |

Layout:

- `sources/<repo>.bundle`: `git bundle create --all` (both branch heads), checked with `git bundle verify` and `list-heads`.
- `<repo>/<branch>/<module>/`: the committed manifest (and `uv.lock`) and the native tree output.
- `<repo>/<branch>/receipts.json`: every build/resolve command with its cwd, exit code, stdout, stderr, timestamps and the branch commit.
- `<repo>/git-receipts.json`, `tool-receipts.json`, `run.json`: repository creation, tool versions, commits, bundle heads and hashes.

Maven rows are one per `dependency:tree` node, recursive, with depth, parent and project coordinate.
Python rows are one per `uv.lock` dependency edge reachable from the project root (the virtual root itself is not a
row), traversed without de-duplication. The lock is universal, so edges whose marker excludes the active Linux CPython 3.11
environment (for example `tzdata; sys_platform == 'win32'`) are kept, and their verification context says they are not installed there.
The rows equal `uv tree --universal --no-dedupe` and cover every locked package.
No EoL data is stored here; lifecycle status comes only from a catalogue sync.
