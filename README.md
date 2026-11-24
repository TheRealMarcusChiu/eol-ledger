# EoL Ledger

A local web app that shows which end-of-life (EoL) release cycles your repositories depend on.
You upload a dependency-inventory JSON describing your repository branches and their resolved
dependencies; it matches packages to [endoflife.date](https://endoflife.date) release cycles and keeps
a snapshot history per branch. Nothing but the endoflife.date catalogue is ever fetched.

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
1. **Imports & sync → Sync catalogue now** (one HTTPS request to endoflife.date, about 3 MB). The result
   and last success are shown on Imports & sync.
2. **Download demo JSON**, then upload it with **Import file** (or upload your own dependency-inventory JSON).
3. Open **EoL Dependencies**.

## Security and deployment

EoL Ledger is a **single trusted local operator** tool. It has no login. It binds to `127.0.0.1`
by default. Do not expose it on a network without putting authentication in front of it (for example
a reverse proxy with SSO). All forms are CSRF-protected, and responses send a strict
Content-Security-Policy.

## Configuration

Set values in `application.properties`, with `--name=value` arguments, or as environment variables.
Spring Boot's environment-variable rule: upper-case, `.` and `[n]` become `_`, and **dashes are
removed** (`eol.catalog.base-url` → `EOL_CATALOG_BASEURL`, `eol.jobs.catalog-sync.cron` →
`EOL_JOBS_CATALOGSYNC_CRON`). The names in the table below are covered by a test (`EolPropertiesTest`).

| Property | Env | Default | Meaning |
|---|---|---|---|
| `server.address` / `server.port` | `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `8092` | Bind address and port |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/eol-ledger` | Database |
| `eol.zone` | `EOL_ZONE` | JVM zone | Zone for "today" and "as of day" boundaries |
| `eol.catalog.base-url` | `EOL_CATALOG_BASEURL` | `https://endoflife.date/api/v1` | endoflife.date API base URL (must be HTTPS) |
| `eol.catalog.products` | `EOL_CATALOG_PRODUCTS` | blank | Blank = whole catalogue via `GET /products/full`. Or a list such as `python,nodejs,spring-boot` (one request each) |
| `eol.catalog.timeout` / `max-bytes` | | `60s` / 32 MB | Catalogue fetch limits |
| `eol.mappings` | `EOL_MAPPINGS` | blank | Explicit mappings, see below |
| `eol.jobs.catalog-sync.cron` / `.zone` | `EOL_JOBS_CATALOGSYNC_CRON` / `EOL_JOBS_CATALOGSYNC_ZONE` | `-` (off) / `UTC` | Scheduled catalogue sync |

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
  data** and upload your dependency-inventory JSON again (history starts from that import). Nothing is copied
  between databases. The H2 file is left untouched; start without the `mysql` profile to use it again.

### Scheduled jobs

The catalogue sync job is off by default. The **Sync catalogue now** button always works.

```properties
# daily at 03:00
eol.jobs.catalog-sync.cron=0 0 3 * * *
eol.jobs.catalog-sync.zone=Europe/Amsterdam
```

`.properties` files have no inline comments: anything after the value, including `#`, becomes part of it.

Failures keep the previous good data, appear on the Imports & sync page and are retried on the
next run. Tests never enable the job and never contact the internet: the catalogue is served by a
loopback test server.

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

## Dashboard paging and catalogue sync

**EoL Dependencies is paged in the database.** The page and `GET /api/dashboard?view=<state>` read the table
`dash_row`, a read model with one row per catalogue release cycle (used or not). Each row holds every column's cell
value exactly as the table filters it (`c_*`) and a natural-order rank for text columns (`r_*`, "3.9" before "3.10",
case-insensitive, blank last). Counts, filters, sorting and `LIMIT ? OFFSET ?` all run as parameterized SQL. The page
renders only the current page (20 by default, or 50/100). `<state>` is the same `?view.dependencies-table` JSON the
address bar holds. The model is rebuilt from the catalogue and the latest snapshots whenever their row counts, newest
snapshot, last product sync time or the day change. While a sync runs, it is rebuilt at most every 3 s.

| Column | Filter value | Values / text | Range | Sort |
|---|---|---|---|---|
| purl, ecosystem, dependency, context, cycle, evidence, source, sbom, status | `c_<col>` | `IN`/`NOT IN`; `LOWER(c) LIKE ? ESCAPE '!'`, `=`, `<>`, `TRIM(c)=''` | rank bounds (`r_<col> BETWEEN`) | `r_<col>`, then `ord` |
| version | `c_version` | same | rank bounds | `r_version`, `ord` |
| eol | `c_eol` (`YYYY-MM-DD` or blank) | same | `eol_from` (index `dash_row_eol`) | `ord` (asc) / `eol_from` |
| repos, branches | `c_repos`, `c_branches` | same on their text | numeric (indexes `dash_row_repos`, `dash_row_branches`) | column, `ord` |

Indexes: `dash_row_ord` (the default page), `dash_row_eol`, `dash_row_repos`, `dash_row_branches`. H2 creates them
with `CREATE INDEX IF NOT EXISTS`. MySQL declares them inside `CREATE TABLE IF NOT EXISTS`, because MySQL 8 has no
`CREATE INDEX IF NOT EXISTS`.

Some queries still scan rather than use an index:
* Contains, ends-with and other `LIKE '%…'` text filters cannot use a B-tree index. They scan `dash_row`, which has
  about one row per catalogue cycle.
* Sorting by a ranked text column sorts in the database.
* A text or version range first reads that column's distinct values to map the bounds onto ranks.
* Fuzzy filters cannot be written in SQL. They read the ordered key plus that column for the rows the other
  filters leave, then match in Java.

A column's Values list (`GET /api/dashboard/values?column=<key>`) is loaded when its filter menu first opens.

**Catalogue sync runs in the background.** **Sync catalogue now** calls `POST /catalog/sync/start`, which returns
`202`, or `409` when a sync or clear is already running. The page then polls `GET /catalog/sync/status`, which
reports `running`, `phase`, products done/total, release cycles done/total, the current product and the terminal
result. A reload resumes polling. Without scripts, the form posts to `/catalog/sync` and waits. Only one sync or clear
runs at a time. A second manual or scheduled sync is refused, and **Clear all data** is refused with a visible message
while a sync runs. The network fetch holds no database lock.

## SBOM import

The Imports page has two sections. **Bulk import** takes one dependency-inventory JSON for any number of branches
(`POST /imports`) and offers the bulk demo JSON in its Format help. **Individual import** takes one SBOM for one branch.
**Import History** in the page head lists both kinds of import.

Individual import links five demo SBOMs, served from `GET /imports/samples/{name}` as attachments:
* `demo-cyclonedx-1.6.json`
* `demo-cyclonedx-1.6.xml`
* `demo-spdx-2.3.json`
* `demo-spdx-2.3.spdx`
* `demo-swid-2015.swidtag`

They live in `src/main/resources/samples/`. They are fictional, hand-written illustrations, not build or resolver
output. Each lists Django 3.2.25 (`pkg:pypi/django@3.2.25`).
* The CycloneDX and SPDX files describe the application `demo-sbom-app` 1.0.0. SPDX also lists that application as a
  package.
* The SWID tag describes Django itself.

Import one on its own, or with a name, URL and branch of your choosing, for example `https://example.invalid/demo/sbom` on `main`.
Only these five names and the bulk demo JSON are served; any other name is a 404.

**Imports → Import SBOM** uploads one SBOM file for one code repo branch: `POST /imports/sbom` (multipart, CSRF).
Only `file` is required. `name` (repository name), `url` (http(s) repository URL), `branch`, `provider` and `appId`
are each optional; a missing or blank one is stored as unknown (shown as —), never defaulted (no `other` provider, no
`main` branch) and never taken from the document or the file name. A supplied value gets the same checks as in the
JSON import (length, http(s) URL without credentials, query or fragment, known provider, branch without spaces).
With both `url` and `branch` the import updates that branch exactly as a JSON import would. Without both, identity is
conservative: the entry is keyed by the file's content plus the supplied fields, so re-uploading the same file with
the same fields (under any file name) updates the same entry, while a different file, or the same file with other
fields, is always a separate entry. Such an entry never merges with, or overwrites, a branch that has a URL and branch. The file becomes one dependency-inventory JSON entry and then takes exactly the
JSON import path: the same validation, import history, single transaction and per-branch snapshot history. An
unchanged re-upload adds no snapshot. Success redirects back to Imports (303); a rejected file returns the Imports page
with the reasons (400) and changes nothing but the history row.

The format is detected from the content, not the file name:

| Format | Serializations | Versions | Packages taken |
|---|---|---|---|
| CycloneDX | JSON (`bomFormat`, `specVersion`), XML (`http://cyclonedx.org/schema/bom/1.x`) | 1.4, 1.5, 1.6 | Every component, including nested and unreachable ones. Not the `metadata.component` root, `file`/`data` components or services. |
| SPDX | JSON (`spdxVersion`), tag-value | 2.2, 2.3 | Every package, including the described root (`name`, `versionInfo`/`PackageVersion`, `purl` external ref). Not files, snippets or relationships. Tag-value sections and multi-line `<text>` values are honoured. |
| SWID | ISO/IEC 19770-2:2015 XML | 2015 | The one `SoftwareIdentity` (`name`, `version`). Not its payload files. `Link` hrefs are never followed. |

Rules:
* Never packages: components of type `file`, `data`, `service` or `license`, and top-level services.
* Present but mistyped is rejected, not ignored: a nested `components` that is not an array (including `null`), a
  non-text `purl`/`group`/`bom-ref`/`SPDXID`/`referenceLocator`, a non-array `externalRefs`, or two different purls
  on one SPDX package.
* **Code repos** shows the application **Version** and **SBOM Format** of each branch's latest snapshot:
  * The version comes from CycloneDX `metadata.component.version`, from the one SPDX package the document describes
    (`documentDescribes` or `DESCRIBES` relationships; none or several means `—`), or from the SWID root `version`.
  * Dependency-inventory JSON may give an optional root `version` (string, at most 200 characters) and shows the
    format "Inventory JSON".
* Every package needs a name and a version; one without them rejects the whole file.
* A purl without a version gets the package version (percent-encoded, before any qualifiers). A purl whose version
  differs from the package version is rejected.
* Ecosystems are set only for purl types `pypi`, `maven`, `npm` and `nuget`; other purls are kept as they are.
* An empty `components`/`packages` array deliberately empties the branch. A missing one is rejected, so a truncated
  file never clears a branch.

Limits:
* XML with a DOCTYPE (DTD or entities) or any XInclude element is refused, and external resources are never resolved.
* At most 10 MB of UTF-8 per file and 50,000 packages.
* XML may be at most 32 **elements** deep, counting every element and not only components. Each nested CycloneDX
  component uses two levels (`components` and `component`). JSON components may nest at most 32 levels.
* Inside `<components>` only CycloneDX `<component>` elements are allowed; this is not full schema validation.
* A SWID upload must hold exactly one `SoftwareIdentity`; embedded or multiple tags are rejected.
* Not supported: SPDX 3 (JSON-LD), SPDX RDF/XML/YAML, CycloneDX before 1.4 and protobuf. Licence and archive contents
  are not validated.

## Dependency inventory JSON

A `.json` upload on the Imports page carries each branch's resolved dependencies itself. The root is an array with one object per repository branch:

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

The Imports page offers `demo-dependency-inventory.json` for download: the **real resolved demo**
inventory (`src/main/resources/samples/`). It describes two fictional DEMO repositories, `enterprise-suite`
(`DEMO-1001`: Maven, Gradle, NuGet) and `web-platform` (`DEMO-1002`: pip, pip3, uv, npm, Yarn), each
on `main` and `release/legacy`. Every row comes from a native resolver run: a dependency tree or
resolution graph, not a flat list. Its `example.invalid` URLs are placeholders, not remotely hosted.
The original modules' rows are unchanged from the demo deliverable's `demo-code-repo-dependency.json`;
the git bundles, sources, lock files and raw evidence that their `localRepository`, `evidence`, `tree`
and `source` paths name ship in the separate demo archive, not in the app. Each branch also has one
appended module, `lifecycle-maven` (Spring `spring-core` 7.0.11 on `main`, 5.3.39 on `release/legacy`)
or `lifecycle-uv` (Django 5.2.19 / 3.2.25), so a catalogue sync links some packages to endoflife.date
release cycles; its manifest and native tree output ship in the app under
`src/main/resources/samples/demo-lifecycle-evidence/`, the path its `tree` and `source` fields name.

| Field | Rules |
|---|---|
| `name`, `url`, `branch` | Required. `url` is an `http(s)` URL with no credentials, query or fragment, normalized (lower-case host, no trailing `/` or `.git`); `url` + `branch` is the identity. `branch` has no default (no `main` is assumed); slashes are fine, spaces are not |
| `provider` | Optional: `github`, `gitlab`, `azure-devops` (also accepts `Azure DevOps`), `tfs`, `bitbucket`, `other` (default). Metadata only: no SCM API is called |
| `appId`, `localRepository`, `schemaVersion` (must be 1), `demo` (boolean) | Optional |
| `sbom` | Optional object: `url` (null, or an http(s) URL without credentials/query/fragment), `format`, `generatedAt` (ISO-8601 instant), `evidence` |
| `projects` | Optional array of objects with a unique string `path`; `ecosystem`, `packageManager`, `coordinate`, `resolution{status,command,toolVersion,tree}` are strings |
| `dependencies` | Required array (`[]` = no packages). One row per resolved graph edge/context; the same package may repeat under different parents or projects |
| dependency `name`, `version` | Required strings |
| dependency `purl` | Optional; must be well formed, its `@version` must equal `version` exactly, and its type must match `ecosystem` for Maven/Python/npm/NuGet. Rows without a purl are identified by name + version, so two such rows with that name and version but a different `ecosystem`, `groupId` or `artifactId` reject the file (add purls) |
| dependency `dependencyType`, `depth` | `direct` or `transitive`; `depth` a whole number ≥ 1 (direct = 1, transitive ≥ 2) |
| dependency `projectPath` | When `projects` are declared, must name one of them |
| other dependency fields | `groupId`, `artifactId`, `type`, `scope`, `parentCoordinate`, `projectCoordinate`, `ecosystem`, `packageManager`, `recordSource` are strings; `optional` boolean; `verification{status,source,context}` strings |

* **All or nothing, nothing fetched.** Every entry and nested row is validated before anything is
  saved; one bad entry rejects the file (errors name `entry N` and the row, e.g. `dependencies[3]`).
  JSON with a repeated key in one object, or anything after the root array, is rejected. `sbom.url`, evidence paths and
  `localRepository` are recorded as provenance and **never fetched or opened**. Unknown fields are kept.
* **Upsert, never delete.** Listed branches are added or updated. Branches left out of the file are untouched.
* **Clear all imported data** (bottom of Imports; needs the confirmation checkbox, checked on the server too)
  deletes in one transaction every code repo, snapshot, dependency inventory, import history row and the synced
  catalogue with its sync status. Schema, configuration and browser preferences stay. It waits for any running
  import or catalogue sync in this process to finish first.
* **Storage.** Each new snapshot stores the whole validated entry (keys sorted, `projects` and
  `dependencies` in a stable order) in `snapshot_inventory`, so App ID, module paths, managers,
  scopes, depth, parents and verification survive. For dashboards and EoL matching, each distinct
  package identity (normalized purl, else name) plus version becomes one ordinary dependency row;
  ecosystem case rules apply (`pkg:pypi/Django` = `pkg:pypi/django`; Maven stays case-sensitive).
* **Snapshots.** A branch gets a new snapshot when anything in its entry except `sbom.generatedAt`
  differs from its latest snapshot, including graph context only (a changed scope or parent with the
  same packages). Reordered keys, rows or entries and a regenerated-but-identical inventory add no
  snapshot; the stored `generatedAt` is then the first observation of that inventory.
* **Updating.** A branch changes only when you upload new JSON for it; nothing refreshes on its own.
  Branches are stored with the SBOM location `inline:json-upload` and marked "Inline JSON upload".
  Branches registered by earlier versions (which fetched SBOMs from a CSV registry) keep their stored
  data and are shown as before; uploading JSON for the same url + branch takes them over.
* **Pages.** Branch and history pages show App ID, projects/managers, declared SBOM (if any),
  generated time and demo status, and add a *Project context* column (project: scope, direct or
  transitive with depth). Synthetic `example.invalid` URLs are labelled as a placeholder demo that is
  not remotely hosted.
* **Limits.** Uploads up to 10 MB. Purl-to-name consistency is not checked (only purl syntax,
  version and ecosystem type).

### History and "as of"

The history page picks the latest snapshot observed before midnight at the end of the chosen day,
in `eol.zone`. Before the first observation it says so and shows nothing. It never back-fills
data it had not seen. Packages shown are historical. **EoL dates and status always come from the
current catalogue**, not from the catalogue as it was on that day, and the page says this.

### Demo data

**Download demo JSON** (in the upload section) gives a resolver-derived inventory of two **fictional
DEMO** code repos (`example.invalid` placeholders, not remotely hosted), each on `main` and
`release/legacy`. Upload it with **Import file** to try the app. Every branch includes Spring Framework
(`spring-core`) or Django, whose purls endoflife.date lists, so after a sync each branch shows matched
release cycles. EoL dates still come only from a real catalogue sync; the app ships no EoL dates.

## Dashboard semantics

* Every tracked dependency version (release cycle used by at least one branch) is listed with no date
  cutoff: EoL dates in the past, in the future and not published, earliest EoL date first (unknown last).
  There is no filter form above the table.
* With no code repos imported (for example right after a sync on an empty or cleared database), every
  synced catalogue release cycle is listed instead, each with 0 code repos and 0 branches, under a note
  saying so. No inventory is invented; once a repo is imported the used-only default returns.
* **Header sort and filters** (need JavaScript; page-local, nothing stored, so a reload starts blank):
  click a header name to sort ascending, again for descending. Versions and text sort naturally (3.9
  before 3.10), counts as numbers, dates as ISO dates; blank/unknown values stay last both ways. Each
  header's ▾ menu filters that column in one of three modes, applied as you type:
  *Values* (checkbox list of every distinct value including blank/"Not published"; its search box with
  contains / exact / fuzzy only narrows the list; select all / select none act on every value),
  *Text* (contains, does not contain, is exactly, is not exactly, starts with, ends with, fuzzy, is blank,
  is not blank; literal and case-insensitive; fuzzy tolerates a missing letter or swapped pair per word,
  e.g. `pyhton` finds Python) or *Range* (inclusive From/To, either blank = open; typed by column:
  YYYY-MM-DD dates, numbers, natural text/versions; any bound excludes blank values; an invalid or
  reversed range is shown as an error and the last valid one stays applied). Columns combine with AND,
  checked values with OR; unchecking every value shows 0 rows. **Clear filter** in a menu clears that
  column only; **Clear all filters** clears every filter and restores the EoL date ascending sort. Neither
  touches column widths or visibility. The count above the table names the filtered columns, including
  hidden ones. Escape or a click outside closes a menu.
* Old links still work: `?start=&end=` (inclusive YYYY-MM-DD, malformed or reversed = 400 page) and
  `?sort=desc` become the initial EoL date filter and sort. All rows are still sent; rows outside the
  range carry `hidden`, so without JavaScript the old link still shows the filtered view, and with it
  clearing the filter restores every row in place. `?unused=true` also lists catalogue cycles no branch
  uses; on such a link **Clear all filters** reloads the plain default page.
* Columns: Dependency Name (endoflife.date product), Version (its release cycle), EoL date, Status,
  Code repos, Branches. **Table settings** (needs JavaScript) shows or hides any column (at least one
  stays visible) and sets widths of 80–1000 px; headers also have drag/arrow-key resize handles. These
  settings live only in this browser's localStorage under `eol-ledger.dependencies-table.v1`;
  **Reset columns** removes that key. Without JavaScript all columns show at automatic widths.
  The pure settings, sort and filter logic has Node tests: `node --test src/test/js/app.test.js`.
* Cycles without a published EoL date are labelled "No EoL date announced" or "Ended (date not
  published)". They never pass a date range; Values and the blank operators can select them.
* **Code repos** counts distinct repository URLs. A repo with two branches on the same cycle counts
  once; an SBOM import without a URL counts as its own repo. **Branches** counts branches.

## Code repos page

One row per branch: App ID, Code repo, Branch, Provider, Packages, Latest snapshot. **App ID** is the
`appId` of the branch's latest snapshot, or `—` when it states none (older snapshots and other
branches are never used). **Code repo** is the repository URL, a link only for `http(s)` URLs without credentials,
otherwise plain text. Every cell of a row is a real link to the branch page, so the browser shows the
URL on hover and Ctrl/⌘-click opens a new tab; the repo link itself goes to the external site. Without JavaScript a *Details* link in the Branch
cell opens the branch page.

## Matching rules and limits

Order: explicit purl mapping, then catalogue purl, then name mapping, then name fallback.

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
   Use this for packages upstream does not list, for purls listed by several products
   (those stay unmatched until mapped), or to correct upstream: a **purl** mapping wins over the
   catalogue's own identifier. A package-name mapping applies only when no single product lists the
   purl. An entry without `=`, with an empty side, or with a malformed purl key stops startup with
   an error naming the entry.
3. **Name fallback**: only for packages *without* a purl whose name equals a product name exactly.

Nothing else matches. A generic name like `core` is never matched by name when the package has a
purl, and Java/npm/PyPI packages are never assumed to be the language runtime.

The **cycle** is the longest numeric cycle whose dot-separated parts are a prefix of the installed
version (`3.10.5` → `3.10`, never `3.1`; `18.2.0` → `18`). Only plain release versions are matched:
digits and dots, an optional leading `v`, and the vendor suffixes `.Final`, `.RELEASE` and `.GA`
(e.g. `6.4.4.Final`). Pre-releases and anything else (`3.10.0-rc1`, `6.0.0-SNAPSHOT`, `1.0.0+build`,
`3.10evil`, `nightly`) are not guessed, because upstream support policy for them is unknown.
Non-numeric cycles (code names) are never guessed either.

The version used is the dependency row's own version. If it is missing, the purl's version (after
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
as the way to consume its data, so the app does not clone or parse the repository itself. The sync
runs from **Imports & sync → Sync catalogue now** (`POST /catalog/sync`) or on `eol.jobs.catalog-sync.cron` when set. Each product's cycles and identifiers are replaced in one transaction only after
that product parsed completely. A failed request or a malformed product keeps the previous data.
Products missing from a later response are kept, not deleted. The sync status (last attempt, last
success, errors) is shown on the Imports & sync page.
