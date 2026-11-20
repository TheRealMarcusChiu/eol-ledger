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

Set values in `application.properties`, with `--name=value` arguments, or as environment variables.
Spring Boot's environment-variable rule: upper-case, `.` and `[n]` become `_`, and **dashes are
removed** (`eol.catalog.base-url` → `EOL_CATALOG_BASEURL`, `eol.sbom.hosts[0].token` →
`EOL_SBOM_HOSTS_0_TOKEN`). The names in the table below are covered by a test (`EolPropertiesTest`).

| Property | Env | Default | Meaning |
|---|---|---|---|
| `server.address` / `server.port` | `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8092` | Bind address and port |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/eol-ledger` | Database |
| `eol.zone` | `EOL_ZONE` | JVM zone | Zone for "today" and "as of day" boundaries |
| `eol.catalog.base-url` | `EOL_CATALOG_BASEURL` | `https://endoflife.date/api/v1` | endoflife.date API base URL (must be HTTPS) |
| `eol.catalog.products` | `EOL_CATALOG_PRODUCTS` | blank | Blank = whole catalogue via `GET /products/full`. Or a list such as `python,nodejs,spring-boot` (one request each) |
| `eol.catalog.timeout` / `max-bytes` | | `60s` / 32 MB | Catalogue fetch limits |
| `eol.sbom.hosts[n].host` | `EOL_SBOM_HOSTS_0_HOST` | none | SBOM host allowlist |
| `eol.sbom.hosts[n].token` | `EOL_SBOM_HOSTS_0_TOKEN` | none | Sent as `Authorization: Bearer <token>` to that host only |
| `eol.sbom.hosts[n].allow-http` | | `false` | Plain HTTP, honoured only for loopback hosts (local dev/test) |
| `eol.sbom.timeout` / `max-bytes` | | `30s` / 10 MB | SBOM fetch limits |
| `eol.mappings` | `EOL_MAPPINGS` | blank | Explicit mappings, see below |
| `eol.jobs.catalog-sync.cron` / `.zone` | `EOL_JOBS_CATALOGSYNC_CRON` / `EOL_JOBS_CATALOGSYNC_ZONE` | `-` (off) / `UTC` | Scheduled catalogue sync |
| `eol.jobs.repo-refresh.cron` / `.zone` | `EOL_JOBS_REPOREFRESH_CRON` / `EOL_JOBS_REPOREFRESH_ZONE` | `-` (off) / `UTC` | Scheduled SBOM refresh |
| `eol.refresh-csv-path` | `EOL_REFRESHCSVPATH` | blank | CSV re-imported by the refresh job; blank = refresh the stored registry |

### Artifactory token setup

```bash
export EOL_SBOM_HOSTS_0_HOST=artifactory.example.com
# replace the placeholder with an access token that can read the SBOM repository
export EOL_SBOM_HOSTS_0_TOKEN='paste-access-token-here'
java -jar target/eol-ledger-0.1.0.jar
```

Artifactory accepts access tokens as Bearer tokens. Tokens never appear in the CSV, links, errors,
logs or import history. An allowlisted host without a token is fetched without authentication.

### Scheduled jobs

Both jobs are off by default and can be turned on separately. The manual buttons always work.

```properties
# daily at 03:00
eol.jobs.catalog-sync.cron=0 0 3 * * *
eol.jobs.catalog-sync.zone=Europe/Amsterdam
# weekdays at 03:30
eol.jobs.repo-refresh.cron=0 30 3 * * MON-FRI
eol.jobs.repo-refresh.zone=Europe/Amsterdam
# optional: re-import this CSV instead of the stored registry
eol.refresh-csv-path=/srv/eol-ledger/registry.csv
```

`.properties` files have no inline comments: anything after the value, including `#`, becomes part of it.

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

The CSV is a registry of where SBOMs live, not a dependency list. Quoting follows RFC 4180: a
field is either unquoted or wholly quoted (`"a, b"`, inner quotes doubled: `"say ""hi"""`). A stray
quote inside an unquoted field, text after a closing quote, or a quote that is never closed rejects
the whole file.

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
* **Formats.** CycloneDX JSON and SPDX 2.x JSON; XML is not supported. The accepted profile is
  strict, so a malformed SBOM can never replace a good inventory with an empty or partial one:
  * CycloneDX: a top-level `components` **array** is required (`[]` means "no packages" and is
    stored as an empty snapshot). Each component must be an object with a string `name`; `group`,
    `version` and `purl` must be strings when present; nested `components` must be an array.
  * SPDX 2.x: a `packages` **array** is required (`[]` allowed). Each package must be an object with
    a string `name`; `versionInfo` must be a string when present; `externalRefs` must be an array of
    objects, and a purl reference's `referenceLocator` must be a string. The described root package
    is skipped.
  * Any purl must be well formed (`pkg:type/name`). `NOASSERTION`/`NONE` values count as absent.
  * Anything else (missing or non-array inventory, non-object records, missing names, wrong types,
    malformed purls) rejects the whole import with the record's position, e.g. `components[3] has no "name"`.
* **Fetch safety.** HTTPS to allowlisted hosts only. Redirects are never followed. `timeout` is one
  overall deadline for connect, headers and the whole body (a stalled body is cancelled), and the
  body is cut off as soon as it exceeds `max-bytes`. `classpath:` is limited to bundled `samples/*.json`. `file:` and other schemes are
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

1. **purl**: the dependency's purl, normalized to `pkg:type/namespace/name`, listed as an
   identifier by exactly one endoflife.date product. Normalization is deliberately conservative:
   version, qualifiers and subpath are dropped; each segment is percent-decoded (an encoded `/` stays
   encoded, so it cannot collide with a real separator); scheme and type are lower-cased. Namespace
   and name are lower-cased **only** for types the purl spec defines as case-insensitive (`npm`,
   `pypi`, `github`, `bitbucket`, `deb`, `nuget`, `composer`, `hex`, `apk`), and `pypi` maps `_` to
   `-`. Maven and every other type keep their case, so `pkg:maven/Org.Acme/x` and
   `pkg:maven/org.acme/x` are different packages. A malformed purl is reported as such and is never
   matched by package name instead. (A catalogue synced before this rule should be re-synced once.)
2. **Explicit mapping**: `eol.mappings=<purl or exact package name>=<product>`, comma separated.
   Purl keys are normalized the same way; package-name keys are case-insensitive. For example
   `eol.mappings=pkg:maven/com.acme/acme-boot-bom=spring-boot,my-base-image=ubuntu`.
   Use this for packages upstream does not list, or for purls listed by several products
   (those stay unmatched until mapped).
3. **Name fallback**: only for packages *without* a purl whose name equals a product name exactly.

Nothing else matches. A generic name like `core` is never matched by name when the package has a
purl, and Java/npm/PyPI packages are never assumed to be the language runtime.

The **cycle** is the longest numeric cycle whose dot-separated parts are a prefix of the installed
version (`3.10.5` → `3.10`, never `3.1`; `18.2.0` → `18`). Only plain release versions are matched:
digits and dots, an optional leading `v`, and the vendor suffixes `.Final`, `.RELEASE` and `.GA`
(e.g. `6.4.4.Final`). Pre-releases and anything else (`3.10.0-rc1`, `6.0.0-SNAPSHOT`, `1.0.0+build`,
`3.10evil`, `nightly`) are not guessed, because upstream support policy for them is unknown.
Non-numeric cycles (code names) are never guessed either.

The version used is the SBOM record's own version. If it is missing, the purl's version (after
`@` in the last segment, percent-decoded, `+` kept) is used and shown as "x (from purl)". If both
are present and differ, the product is still shown but **no cycle or EoL date is assigned**; the
version shows as "x (purl: y)" and the evidence names the conflict. This is applied when pages are
rendered, so data imported by earlier builds is treated the same way. In both cases the package shows its
product with "cycle unknown" and the evidence says why. Upstream boolean or null EoL values are kept as they are: "ended, date not
published" is different from "no EoL date announced". Only upstream `eolFrom` is used. Extended or
commercial support dates are not shown.

## endoflife.date source and attribution

EoL data comes from the endoflife.date API v1 (`/products/full`, or `/products/<name>`), which is
generated from [github.com/endoflife-date/endoflife.date](https://github.com/endoflife-date/endoflife.date)
(MIT licence). Each product's cycles and identifiers are replaced in one transaction only after
that product parsed completely. A failed request or a malformed product keeps the previous data.
Products missing from a later response are kept, not deleted. The sync status (last attempt, last
success, errors) is shown on the Imports & sync page.
