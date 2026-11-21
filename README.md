# EoL Ledger

A local web app that shows which end-of-life (EoL) release cycles your repositories depend on.
It reads a CSV registry of repository branches, fetches each branch's SBOM, matches packages to
[endoflife.date](https://endoflife.date) release cycles and keeps a snapshot history per branch.

Java 21 · Spring Boot 3.5 · Thymeleaf (server-rendered, one tiny script) · H2 file database by default,
or an external MySQL 8 server (see [Database: H2 or MySQL](#database-h2-or-mysql)). No external UI assets.

## Build and run

```bash
mvn verify                                  # tests + runnable jar
java -jar target/eol-ledger-0.1.0.jar       # http://127.0.0.1:8092
```

Data is stored in `./data/eol-ledger.mv.db` (relative to the working directory).
Health: `GET /actuator/health`.

First steps:
1. **Update EoL data** on the Release cycles page (same action as **Imports & sync → Sync catalogue now**;
   one HTTPS request to endoflife.date, about 3 MB). The result and last success are shown on Imports & sync.
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

### Database: H2 or MySQL

The database is chosen at startup; everything (catalogue products, their purl identifiers and release
cycles, sync status, registered repository branches, snapshots, dependencies and import history) lives in that one
database.

* **H2 (default).** No settings needed. Uses `./data/eol-ledger.mv.db` and `schema.sql`, unchanged
  from earlier versions, so an existing H2 file keeps working as it is.
* **MySQL 8.0.17 or newer** (tested target: 8.x; 8.4 LTS recommended). Start with the `mysql` profile:

```bash
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_URL='jdbc:mysql://db.example.com:3306/eol_ledger?sslMode=VERIFY_IDENTITY'
export MYSQL_USER=eol_ledger
export MYSQL_PASSWORD='paste-password-here'
java -jar target/eol-ledger-0.1.0.jar
```

One-time setup on the MySQL server (as an administrator):

```sql
CREATE DATABASE eol_ledger CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE USER 'eol_ledger'@'%' IDENTIFIED BY 'paste-password-here';
-- CREATE/INDEX/REFERENCES are needed only for the app's CREATE TABLE IF NOT EXISTS at startup
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX, REFERENCES ON eol_ledger.* TO 'eol_ledger'@'%';
```

* `MYSQL_URL`, `MYSQL_USER` and `MYSQL_PASSWORD` are required. If one is missing or the server
  cannot be reached, startup fails; it never falls back to H2. (`SPRING_DATASOURCE_URL` etc. also
  work and take precedence.) Passwords are never shown in pages; database errors during an import
  show a generic message and details go to the server log only.
* **TLS:** use `sslMode=VERIFY_IDENTITY` (or `VERIFY_CA`) with a trusted server certificate for any
  remote server. `sslMode=DISABLED` is only reasonable on loopback.
* **Time zones:** no `serverTimezone`/`connectionTimeZone` setting is needed. Timestamps are stored as
  UTC in `DATETIME(6)` by the app itself, with microsecond precision, so the server, session and
  JVM time zones do not affect stored values or "as of" days.
* At startup `schema-mysql.sql` creates missing tables only (`CREATE TABLE IF NOT EXISTS`); nothing
  is dropped or altered. Tables are InnoDB with `utf8mb4_0900_bin`, so repository URLs, branches and
  purls are exact and case-sensitive. Values longer than the columns are rejected, never truncated
  (the app turns on strict SQL mode for its own sessions). The long identities (`repo_url`+`branch`,
  `product`+`purl`) are kept unique through a stored SHA-256 key column; lookups still compare the
  full values.
* Run **one app instance per database**. Imports and syncs are serialized inside one process only.
* **Switching is not migration.** Choosing MySQL starts with an empty database: run **Update EoL
  data** and import your registry CSV again (history starts from that import). Nothing is copied
  between databases. The H2 file is left untouched; start without the `mysql` profile to use it again.

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

### Real MySQL tests (opt-in)

`mvn verify` runs only against in-memory H2; `MySqlStorageTest` is reported as skipped. The test base
pins the profile and the in-memory H2 datasource above environment variables and `-D` properties, so
a shell exported for production MySQL (`SPRING_PROFILES_ACTIVE`, `MYSQL_URL`, `SPRING_DATASOURCE_URL`)
cannot redirect it, and the per-test cleanup refuses to delete from anything other than in-memory H2
or the MySQL schema the run itself created. To run the
same storage contract against a real, **dedicated test** MySQL 8 server:

```sql
CREATE USER 'eol_test'@'%' IDENTIFIED BY 'test-password';
GRANT ALL ON `eol\_test\_%`.* TO 'eol_test'@'%';
```

```bash
EOL_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3307/?sslMode=DISABLED' \
EOL_TEST_MYSQL_USER=eol_test EOL_TEST_MYSQL_PASSWORD='test-password' \
mvn test -Dtest='MySqlStorageTest'
```

The URL names the server only (no database). Each run creates one new `eol_test_<random>` database,
uses only that, and drops only that database at the end. It never touches other databases.

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

## Dependency inventory JSON

Instead of pointing at an SBOM, a `.json` upload on the same Imports page carries each branch's
resolved dependencies itself. The root is an array with one object per repository branch:

```json
[
  {
    "schemaVersion": 1,
    "name": "enterprise-suite",
    "appId": "DEMO-1001",
    "url": "https://example.invalid/eol-demo/enterprise-suite",
    "provider": "other",
    "branch": "main",
    "demo": true,
    "localRepository": "sources/enterprise-suite.bundle",
    "sbom": {"url": null, "format": "inline-dependency-inventory", "generatedAt": "2026-11-20T10:00:00Z", "evidence": "evidence/summary.json"},
    "projects": [
      {"path": "backend-maven", "ecosystem": "Maven", "packageManager": "Maven", "coordinate": "com.example:backend-maven:1.0",
       "resolution": {"status": "resolved", "command": "mvn -B dependency:tree", "toolVersion": "Apache Maven 3.9.9", "tree": "evidence/backend-maven-tree.txt"}}
    ],
    "dependencies": [
      {"name": "com.google.guava:guava", "version": "33.0.0-jre", "purl": "pkg:maven/com.google.guava/guava@33.0.0-jre",
       "groupId": "com.google.guava", "artifactId": "guava", "type": "jar", "scope": "compile", "dependencyType": "direct",
       "optional": false, "depth": 1, "parentCoordinate": "com.example:backend-maven:1.0", "projectCoordinate": "com.example:backend-maven:1.0",
       "ecosystem": "Maven", "packageManager": "Maven", "projectPath": "backend-maven", "recordSource": "resolved-dependency-tree",
       "verification": {"status": "resolved", "source": "evidence/backend-maven-tree.txt", "context": "compile scope"}}
    ]
  }
]
```

`Download` on the Imports page offers `demo-dependency-inventory.json`, a small **hand-written format
example** (not resolver output; its rows say `hand-written-format-example`).

| Field | Rules |
|---|---|
| `name`, `url`, `branch` | Required. `url` follows the registry CSV `repo_url` rules and normalization; `branch` has no default (no `main` is assumed) |
| `provider` | Optional, as in the CSV; default `other` |
| `appId`, `localRepository`, `schemaVersion` (must be 1), `demo` (boolean) | Optional |
| `sbom` | Optional object: `url` (null, or an http(s) URL without credentials/query/fragment), `format`, `generatedAt` (ISO-8601 instant), `evidence` |
| `projects` | Optional array of objects with a unique string `path`; `ecosystem`, `packageManager`, `coordinate`, `resolution{status,command,toolVersion,tree}` are strings |
| `dependencies` | Required array (`[]` = no packages). One row per resolved graph edge/context; the same package may repeat under different parents or projects |
| dependency `name`, `version` | Required strings |
| dependency `purl` | Optional; must be well formed, its `@version` must equal `version` exactly, and its type must match `ecosystem` for Maven/Python/npm/NuGet |
| dependency `dependencyType`, `depth` | `direct` or `transitive`; `depth` a whole number ≥ 1 (direct = 1, transitive ≥ 2) |
| dependency `projectPath` | When `projects` are declared, must name one of them |
| other dependency fields | `groupId`, `artifactId`, `type`, `scope`, `parentCoordinate`, `projectCoordinate`, `ecosystem`, `packageManager`, `recordSource` are strings; `optional` boolean; `verification{status,source,context}` strings |

* **All or nothing, nothing fetched.** Every entry and nested row is validated before anything is
  saved; one bad entry rejects the file (errors name `entry N` and the row, e.g. `dependencies[3]`).
  JSON with a repeated key in one object is rejected. `sbom.url`, evidence paths and
  `localRepository` are recorded as provenance and **never fetched or opened**. Unknown fields are kept.
* **Storage.** Each new snapshot stores the whole validated entry (keys sorted, `projects` and
  `dependencies` in a stable order) in `snapshot_inventory`, so App ID, module paths, managers,
  scopes, depth, parents and verification survive. For dashboards and EoL matching, each distinct
  package identity (normalized purl, else name) plus version becomes one ordinary dependency row;
  ecosystem case rules apply (`pkg:pypi/Django` = `pkg:pypi/django`; Maven stays case-sensitive).
* **Snapshots.** A branch gets a new snapshot when anything in its entry except `sbom.generatedAt`
  differs from its latest snapshot, including graph context only (a changed scope or parent with the
  same packages). Reordered keys, rows or entries and a regenerated-but-identical inventory add no
  snapshot; the stored `generatedAt` is then the first observation of that inventory.
* **Refresh.** JSON branches are stored with the SBOM location `inline:json-upload` and are marked
  "Inline JSON upload" in the UI. The registry refresh (button or schedule) skips them and says how
  many it skipped; a scheduled `eol.refresh-csv-path` CSV naming such a branch is skipped the same way,
  so a declared `sbom.url` never silently replaces the uploaded inventory. Only an explicit upload
  (new JSON, or a manual registry CSV upload for that url + branch) changes it.
* **Pages.** Branch and history pages show App ID, projects/managers, declared SBOM (if any),
  generated time and demo status, and add a *Project context* column (project: scope, direct or
  transitive with depth). Synthetic `example.invalid` URLs are labelled as a placeholder demo that is
  not remotely hosted. CSV-registered branches look exactly as before.
* **Limits.** Uploads up to 10 MB. Purl-to-name consistency is not checked (only purl syntax,
  version and ecosystem type).

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
(MIT licence). That project's README points to this API ([docs](https://endoflife.date/docs/api/v1/))
as the way to consume its data, so the app does not clone or parse the repository itself. The
**Update EoL data** button on the Release cycles page runs the same sync as the Imports & sync page. Each product's cycles and identifiers are replaced in one transaction only after
that product parsed completely. A failed request or a malformed product keeps the previous data.
Products missing from a later response are kept, not deleted. The sync status (last attempt, last
success, errors) is shown on the Imports & sync page.
