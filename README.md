# EoL Ledger

A local web app that shows which end-of-life (EoL) release cycles your repositories depend on.
It reads a CSV registry of repository branches, fetches each branch's SBOM, matches packages to
[endoflife.date](https://endoflife.date) release cycles and keeps a snapshot history per branch.

Java 21 · Spring Boot 3.5 · Thymeleaf (server-rendered, one tiny script) · H2 file database. No external UI assets.

## Build and run

```bash
mvn verify                                  # tests + runnable jar
java -jar target/eol-ledger-0.1.0.jar       # http://127.0.0.1:8092
```

Data is stored in `./data/eol-ledger.mv.db` (relative to the working directory).
Health: `GET /actuator/health`.

First steps:
1. **Imports & sync → Sync catalogue now** (one HTTPS request to endoflife.date, about 3 MB).
2. **Import demo registry** (or upload your own CSV).
3. Open **Release cycles**.

## Security and deployment

EoL Ledger is a **single trusted local operator** tool. It has no login. It binds to `127.0.0.1`
by default. Do not expose it on a network without putting authentication in front of it (for example
a reverse proxy with SSO). All forms are CSRF-protected, and responses send a strict
Content-Security-Policy.

## Configuration

Set values in `application.properties`, with `--name=value` arguments, or as environment variables
(Spring relaxed binding: `eol.sbom.hosts[0].token` → `EOL_SBOM_HOSTS_0_TOKEN`).

| Property | Env | Default | Meaning |
|---|---|---|---|
| `server.address` / `server.port` | `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8092` | Bind address and port |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/eol-ledger` | Database |
| `eol.zone` | `EOL_ZONE` | JVM zone | Zone for "today" and "as of day" boundaries |
| `eol.catalog.base-url` | `EOL_CATALOG_BASE_URL` | `https://endoflife.date/api/v1` | endoflife.date API base URL (must be HTTPS) |
| `eol.catalog.products` | `EOL_CATALOG_PRODUCTS` | blank | Blank = whole catalogue via `GET /products/full`. Or a list such as `python,nodejs,spring-boot` (one request each) |
| `eol.catalog.timeout` / `max-bytes` | | `60s` / 32 MB | Catalogue fetch limits |
| `eol.sbom.hosts[n].host` | `EOL_SBOM_HOSTS_0_HOST` | none | SBOM host allowlist |
| `eol.sbom.hosts[n].token` | `EOL_SBOM_HOSTS_0_TOKEN` | none | Sent as `Authorization: Bearer <token>` to that host only |
| `eol.sbom.hosts[n].allow-http` | | `false` | Plain HTTP, honoured only for loopback hosts (local dev/test) |
| `eol.sbom.timeout` / `max-bytes` | | `30s` / 10 MB | SBOM fetch limits |
| `eol.mappings` | `EOL_MAPPINGS` | blank | Explicit mappings, see below |
| `eol.jobs.catalog-sync.cron` / `.zone` | `EOL_JOBS_CATALOGSYNC_CRON` | `-` (off) / `UTC` | Scheduled catalogue sync |
| `eol.jobs.repo-refresh.cron` / `.zone` | `EOL_JOBS_REPOREFRESH_CRON` | `-` (off) / `UTC` | Scheduled SBOM refresh |
| `eol.refresh-csv-path` | `EOL_REFRESH_CSV_PATH` | blank | CSV re-imported by the refresh job; blank = refresh the stored registry |

### Artifactory token setup

```bash
export EOL_SBOM_HOSTS_0_HOST=artifactory.example.com
export EOL_SBOM_HOSTS_0_TOKEN=<access token with read access to the SBOM repository>
java -jar target/eol-ledger-0.1.0.jar
```

Artifactory accepts access tokens as Bearer tokens. Tokens never appear in the CSV, links, errors,
logs or import history.

### Scheduled jobs

Both jobs are off by default and can be turned on separately. The manual buttons always work.

```properties
eol.jobs.catalog-sync.cron=0 0 3 * * *        # daily 03:00
eol.jobs.catalog-sync.zone=Europe/Amsterdam
eol.jobs.repo-refresh.cron=0 30 3 * * MON-FRI
eol.jobs.repo-refresh.zone=Europe/Amsterdam
eol.refresh-csv-path=/srv/eol-ledger/registry.csv   # optional
```

Failures keep the previous good data, appear on the Imports & sync page and are retried on the
next run. Tests never enable the jobs and never contact the internet: the catalogue and SBOMs are
served by a loopback test server.

## Registry CSV

The header must be exactly:

```
repo_name,repo_url,provider,branch,sbom_url
```

| Column | Rules |
|---|---|
| `repo_name` | Display name, required |
| `repo_url` | `http(s)` URL with no credentials, query or fragment. Normalized (lower-case host, no trailing `/` or `.git`); this plus `branch` is the identity |
| `provider` | `github`, `gitlab`, `azure-devops` (also accepts `Azure DevOps`), `tfs`, `bitbucket`, `other`. Metadata only: no SCM API is called |
| `branch` | Required; slashes are fine (`release/2.x`); no spaces |
| `sbom_url` | HTTPS URL on an allowlisted host, with no credentials, query or fragment; or a bundled sample `classpath:samples/<file>.json` |

The CSV is a registry of where SBOMs live, not a dependency list. Quoted fields (`"a, b"`) are supported.

### Import semantics

* **All or nothing.** Every row is validated and every SBOM is fetched and parsed before anything
  is saved. One bad row or one failed fetch rejects the file and leaves existing data as it was.
  Every error is listed with its line number.
* **Upsert, never delete.** Listed rows are added or updated. Rows left out of the file are untouched.
* **Snapshots.** Each branch has its own history. A new snapshot is stored only when the branch's
  normalized inventory (SBOM URL plus the sorted, de-duplicated list of name, version and purl)
  differs from that branch's *latest* snapshot. Re-importing the same CSV re-fetches every SBOM, so
  remote changes are picked up, but an unchanged inventory adds no history. Package order does not
  matter. A change back to an earlier state (A → B → A) creates a new snapshot. A change to
  `repo_name`/`provider` alone updates the registry without a snapshot.
* **Formats.** CycloneDX JSON (nested `components` included) and SPDX 2.x JSON (`packages`, purl from
  `externalRefs`; the described root package is skipped). XML is not supported.
* **Fetch safety.** HTTPS to allowlisted hosts only. Redirects are never followed. Timeouts and body
  size are bounded. `classpath:` is limited to bundled `samples/*.json`. `file:` and other schemes are
  rejected.

### History and "as of"

The history page picks the latest snapshot observed before midnight at the end of the chosen day,
in `eol.zone`. Before the first observation it says so and shows nothing. It never back-fills
data it had not seen. Packages shown are historical. **EoL dates and status always come from the
current catalogue**, not from the catalogue as it was on that day, and the page says this.

### Demo data

`Download demo CSV` / `Import demo registry` load four **fictional DEMO** branches: a GitLab repo on
`main` and `release/2.x`, a GitHub repo (SPDX) and an Azure DevOps repo. Their SBOMs are bundled in
`src/main/resources/samples/` and include unmatched packages (`com.example.demo:core`, guava,
`internal-ui-kit`, requests). `demo-registry-update.csv` moves `main` to a newer SBOM so you can see
history. EoL dates still come only from a real catalogue sync; the app ships no EoL dates.

## Dashboard semantics

* With no query string, the start date is today (in `eol.zone`) and only cycles used by at least one
  branch are shown. **Reset to today** returns to that view.
* Dates are inclusive. Start only = from start onwards. End only = up to and including end. Clearing
  both (`?start=&end=`) = all time. A deliberately cleared start is different from the default view.
* Cycles without a published EoL date appear only in the all-time view and are labelled
  "No EoL date announced" or "Ended (date not published)". They never pass a date filter.
* **Repositories** counts distinct repository URLs. A repo with two branches on the same cycle counts
  once. **Branches** counts branches.
* Malformed or reversed dates return a 400 page that explains the problem.

## Matching rules and limits

1. **purl**: the dependency's purl, normalized to `pkg:type/namespace/name` (lower-case,
   percent-decoded, no version, qualifiers or subpath), listed as an identifier by exactly one
   endoflife.date product.
2. **Explicit mapping**: `eol.mappings=<normalized purl or exact package name>=<product>`, comma
   separated, for example
   `eol.mappings=pkg:maven/com.acme/acme-boot-bom=spring-boot,my-base-image=ubuntu`.
   Use this for packages upstream does not list, or for purls listed by several products
   (those stay unmatched until mapped).
3. **Name fallback**: only for packages *without* a purl whose name equals a product name exactly.

Nothing else matches. A generic name like `core` is never matched by name when the package has a
purl, and Java/npm/PyPI packages are never assumed to be the language runtime.

The **cycle** is the longest numeric cycle whose dot-separated parts are a prefix of the installed
version (`3.10.5` → `3.10`, never `3.1`; `18.2.0` → `18`; a leading `v` is ignored). Non-numeric
cycles (code names) and non-numeric versions are not guessed: the package shows its product with
"cycle unknown". Upstream boolean or null EoL values are kept as they are: "ended, date not
published" is different from "no EoL date announced". Only upstream `eolFrom` is used. Extended or
commercial support dates are not shown.

## endoflife.date source and attribution

EoL data comes from the endoflife.date API v1 (`/products/full`, or `/products/<name>`), which is
generated from [github.com/endoflife-date/endoflife.date](https://github.com/endoflife-date/endoflife.date)
(MIT licence). Each product's cycles and identifiers are replaced in one transaction only after
that product parsed completely. A failed request or a malformed product keeps the previous data.
Products missing from a later response are kept, not deleted. The sync status (last attempt, last
success, errors) is shown on the Imports & sync page.
