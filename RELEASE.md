# Release notes: SQL-paged dashboard and background catalogue sync

Branch `feature/server-paging-live-sync` (base `5bb7c9a`).

**Status:** product commit `f7621574289d94911a9ac476141d7fc4f6e64aaa` was fast-forwarded into `master`, pushed to
`origin/master` and deployed to production. See "Production acceptance" at the end. The red/green notes below are the
implementation history; the worker that wrote them ran no server or browser.

## Scope

### Dashboard paging in SQL
* A new read model, the table `dash_row` (H2 `schema.sql`, MySQL `schema-mysql.sql`, both `CREATE ... IF NOT EXISTS`),
  holds one row per catalogue release cycle, used or unused.
  * For each of the 13 columns it stores the cell value the shipped `app.js` filtered on (`c_*`).
  * Text columns also get a dense natural-order rank (`r_*`): digits compare as numbers, case is ignored, and blank
    values get no rank.
  * `ord` stores the default order: EoL date ascending, unknown dates last, then product, then cycle.
* `DashboardQuery` rebuilds `dash_row` from the existing `Ledger.dashboard` matching code. It rebuilds when any of
  these change:
  * the row counts of product, release_cycle, product_identifier, repo_branch or snapshot
  * `max(snapshot.id)` or `max(product.synced_at)`
  * the day (the Status column depends on it)
* While a sync runs, the rebuild happens at most every 3 s. Other readers keep serving the previous model while one
  request rebuilds it, so there is no blocking monitor. Only the first build in a process waits.
* `GET /`:
  * renders only the current page from SQL, honouring `?view.dependencies-table`, with 20 rows by default.
  * turns legacy `start`/`end`/`sort` links into the same EoL range filter and sort, applied in SQL. An explicit view
    param wins.
  * keeps `unused=false` (only cycles in use) as a base filter.
  * adds `data-server`, `data-total`, `data-matching` and `data-sync-running` attributes to the table.
* `GET /api/dashboard?view=<state>[&unused=false]` returns
  `{total, matching, page, pages, size, rows:[...]}` and only ever the requested page. `<state>` is the URL view JSON,
  version 1. Anything malformed, unknown or of another version falls back to the defaults. An out-of-range page is
  clamped to the last page. The only 400 is for more than 20,000 values in one filter.
* `GET /api/dashboard/values?column=<key>[&unused=false]` returns one column's distinct values over the whole table,
  in the browser's ascending order. It returns 400 for an unknown column.
* The SQL is fully parameterized. Column names come from a fixed whitelist. User text is only ever a bound value, and
  `LIKE` escapes `% _ !`.
* Semantics ported from `app.js`:
  * the 9 text operators: literal, case-insensitive, with a trimmed query; a blank query is inactive
  * values selected/excluded, including an empty selection meaning no rows
  * `decodeView` / `checkRange` validation, including the invalid-draft carried bounds
  * typed ranges: number, date, and version/text by natural order
  * blanks last in both sort directions, with ties keeping the default order
  * `fuzzyMatches` / `editDistance`, ported literally
* `app.js`, server mode for tables with `data-server`:
  * Filter, sort, page and size changes fetch one page. Responses that arrive after a newer request are ignored.
  * Rows are rebuilt with the same cells, `data-value` attributes and links as the server-rendered ones, in the
    user's current column order and visibility. Column drag reorders fetched rows too.
  * A column's Values list loads from `/api/dashboard/values` when its menu first opens.
  * An `excluded` list from the URL is sent to the server as-is until the column's values are known.
  * The URL state format, the 20/50/100 page sizes, storage, layout, resizing and visibility are unchanged. Other
    tables are unchanged (client-side paging).
  * While a sync is running at page load, the page polls the sync status and refetches the shown page whenever the
    phase or the products-done count changes.
* There is no CSV export in this codebase (checked `app.js` and the templates), so no export semantics changed.

### Background catalogue sync
* `CatalogService` has one work gate (`AtomicBoolean`) shared by sync and clear. A second sync or clear is refused at
  once and never queued. The old `synchronized` monitors on sync and on clear-inside-catalogue are gone.
* Progress (`GET /catalog/sync/status`) reports `running`, `phase` (starting → fetching → saving → succeeded/failed),
  products done/total, release cycles done/total, the current product, start and finish times, `ok` and `message`.
  The counters advance at real boundaries: after the envelope is fetched and validated, and after each product's
  transaction.
* The terminal state is published while the job still owns the gate, and the gate is released last. Unexpected
  `RuntimeException`s become a visible terminal failure on both the synchronous and the background path.
* `POST /catalog/sync/start` (CSRF) returns 202 `{started:true,status}`, or 409 `{started:false,status}`.
  `POST /catalog/sync` keeps its 303 contract as the no-script fallback, and reports "already running" when busy.
  The scheduled `Jobs.catalogSync` calls `sync()`, so it is refused too.
* The Imports page JS starts the job, polls once per second, shows the phase and counts, and resumes polling after a
  reload during a sync.
* `ImportService.clearAll` takes the gate. While a sync runs it fails immediately with "A catalogue sync is running,
  so nothing was cleared…", at the service boundary and as a flash warning. Clear also empties `dash_row`.
* The network fetch runs outside any transaction or database lock, as before.

## Tests (real runs in this worktree)

| Command | Result |
|---|---|
| `mvn test -Dtest=DashboardPagingTest+BackgroundSyncTest` | Wrong selector syntax for this surefire version: "No tests matching pattern". Not RED evidence. |
| `mvn test -Dtest=DashboardPagingTest,BackgroundSyncTest` (before the work gate existed) | **RED**: test compile failure, `cannot find symbol beginWork()/endWork()` |
| same, after the gate, async job and `DashboardQuery` | DashboardPagingTest 8/8 green. BackgroundSyncTest 5/6: one wrong assertion of mine, which expected `running` while only the test held the gate. Fixed to assert the status answers. |
| `mvn test` (full) | Hung in `ClearDataAndSyncTest.clearWaitsForAnInFlightImportOrCatalogueSync`, which waited for clear to *block* on the catalogue monitor. That is the old contract, which this task replaces. Stopped. The test now still proves clear waits for an import, and proves it is refused (deleting nothing) during a sync. |
| `mvn verify` #1 | 206 tests, 5 failures. 4 in `DashboardTest`: the empty EoL `data-value=""` was dropped by `th:attr` (a real regression, fixed), and three asserted the old contract of hidden rows for legacy links and the old table tag (updated: legacy bounds now filter in SQL; whole-result counts are asserted). 1 in `MySqlProfileTest` (table count 10 → 11). |
| `mvn test -Dtest=DashboardTest,MySqlProfileTest,DashboardPagingTest,BackgroundSyncTest,ClearDataAndSyncTest` | 43/43 green |
| **`mvn verify` (final)** | **206 run, 0 failures, 0 errors, 12 skipped. BUILD SUCCESS. Built `target/eol-ledger-0.1.0.jar` (36,405,973 bytes).** |
| **`node --test src/test/js/*.test.js` (final)** | **69/69 pass** (67 existing + 2 new in `server-paging.test.js`). One intermediate run failed 15 because the fake DOM has no `hasAttribute`; switched to `dataset.server`. |

**Skips:** all 12 are `MySqlStorageTest`. It needs `EOL_TEST_MYSQL_*` and a dedicated MySQL server, so the MySQL DDL
and the `dash_row` queries were **not** executed against MySQL. `MySqlProfileTest` checks the DDL text only.

**Honest TDD gaps:**
* Paging RED was captured only as the compile failure; no behavioural RED run against the old code.
* The sync ordering/RuntimeException regressions (`theGateIsReleasedOnlyAfterTheTerminalStateIsPublished`,
  `anUnexpectedRuntimeFailureStillEndsTheJobVisibly`) were written together with the fix, so no RED run was captured.
  The ordering test is deterministic: it spins on `beginWork()` and asserts the job is already terminal.

**New tests:**
* `DashboardPagingTest` (32 rows):
  * root renders 20 rows and no hidden rows
  * page 1/2, clamped page, malformed and injection states
  * global natural sort; blanks last in both directions
  * text, fuzzy, starts-with, is-blank, selected/excluded/empty selection, date, number and version ranges
  * the "Not installed" version semantics
  * literal `%`/`_`; `DROP TABLE` text stays a bound value
  * whole-column values list
  * evidence and matching follow a new import
  * EXPLAIN index use
* `BackgroundSyncTest`: 202 then real counts (3/3 products, 7/7 cycles); fetch failure is terminal; CSRF; gate
  ordering; RuntimeException; duplicate and clear refusal with nothing deleted while the page and status stay
  responsive.
* `server-paging.test.js`: round-trip of an unresolved `excluded` list; sync status text.

## SQL plans (actual H2 `EXPLAIN`, printed by `DashboardPagingTest`)
```
default page:  FROM DASH_ROW /* PUBLIC.DASH_ROW_ORD */ ORDER BY ORD FETCH FIRST 20 ROWS ONLY /* index sorted */
eol range:     /* PUBLIC.DASH_ROW_EOL: EOL_FROM >= DATE '2025-01-01' */ ORDER BY EOL_FROM, ORD ... /* index sorted */
repos range:   /* PUBLIC.DASH_ROW_REPOS: C_REPOS >= 1 */ ORDER BY C_REPOS, ORD ... /* index sorted */
contains:      /* PUBLIC.DASH_ROW.tableScan */ WHERE LOWER(C_PURL) LIKE '%react%' ESCAPE '!'
```

Residual limits, not hidden:
* contains, not-contains, ends-with, exact on `LOWER()`, and blank tests scan `dash_row` (about one row per catalogue
  cycle; roughly 9.5k for the full catalogue).
* Ranked text sorts are sorted in the database (`r_x IS NULL, r_x`) without an index.
* A text or version range reads that column's distinct values to map its bounds onto ranks.
* Fuzzy reads `ord` plus the column for the rows the other filters leave, in order, and matches in Java. Only the
  page's rows are then loaded.
* The rebuild hydrates the catalogue once per change or day, not per request.
* MySQL stores long text as `MEDIUMTEXT`, so its ORDER BY uses ranks, not text. No MySQL plans were captured (no
  server).

## Interfaces for the browser coordinator
* `GET /` (also `?view.dependencies-table=…`, `?start=&end=&sort=desc`, `?unused=false`): first page only.
  `table#dependencies-table[data-server][data-total][data-matching][data-sync-running]`.
* `GET /api/dashboard?view=<json>[&unused=false]` and `GET /api/dashboard/values?column=<key>[&unused=false]`.
* `POST /catalog/sync/start` (form `_csrf`) returns 202/409. `GET /catalog/sync/status` returns the JSON progress.
  `POST /catalog/sync` is the 303 fallback.
* `POST /imports/clear` (confirm=true): a flash warning while a sync runs.
* Imports page: `form[data-sync-form]` and `p[data-sync-status]`.

## Round 2: oracle comparator fix

The coordinator's independent oracle (217 frozen old-JS cases, on the isolated :8098 instance) passed 212 and failed 5:
sort-purl asc/desc, sort-evidence asc/desc and sort-status asc. The cause was that `NATURAL` split strings into
digit/non-digit chunks and compared the non-digit chunks with Java's English `Collator`. That differs from
`Intl.Collator` in two ways:
* Punctuation order: Java put `/` before `.`.
* Chunk boundaries: `"Ended "` was compared as a prefix, so `"Ended (date not published)"` sorted after
  `"Ended 2 days ago"`.

* **RED**: `mvn test -Dtest=DashboardCollationTest` failed 3 of 3 before the fix. The failures were the exact
  hibernate `.orm` vs `/` pair, the status ordering, and space not being ignorable.
* **Fix**: `NATURAL` now compares the whole string element by element at primary strength. Case and accents are
  ignored and digit runs compare as numbers. The order follows the CLDR root: whitespace, then ASCII punctuation in
  CLDR order, then other symbols, then digits, then letters. `java.text.Collator` is no longer used.
* **GREEN**: `DashboardCollationTest` and `DashboardPagingTest` pass. One expectation in my new test was itself wrong
  (`"Ended 3 years ago"` sorts before `"Ended 10 days ago"` under numeric collation) and was corrected. The oracle
  was not altered.
* **Startup build**: `DashboardQuery.warm()` now builds the read model on `ApplicationReadyEvent`, so a cold start
  no longer hydrates the catalogue on the first `/` request. After an import or sync, the rebuild still happens
  lazily on the next dashboard read once the fingerprint changes. During a sync it is throttled to at most every 3 s.
  This is not a push on mutation.

## Round 3: stale model after a sync finishes

In the coordinator's browser run (clear, then a full sync: 489 products, 9470 cycles), `GET /` showed 0 rows right
after the sync finished. The cause was in `fresh()`: when another request held the rebuild lock, any reader returned
the previous (here: empty) model, not only readers during a sync.

* **RED**: the new `DashboardPagingTest.afterASyncReadersWaitForTheRunningRebuildWhileDuringASyncTheyDoNot` holds
  the rebuild lock deterministically, changes the data, then reads. `mvn test -Dtest=DashboardPagingTest` failed:
  "returned the stale model while a rebuild was running".
* **Fix** (one condition): when no sync is running, a reader that finds a rebuild in progress now waits for it, then
  re-checks the fingerprint and rebuilds again if needed. Only while a sync runs does a reader get the previous model
  at once, so the page stays responsive during a sync. As a result, the dashboard's single fetch after the sync
  finishes now returns the completed model.
* **Clear racing a rebuild**: a rebuild that read its data before a clear can re-insert old rows after the clear.
  Its recorded fingerprint is the pre-clear one, so the next read sees a different fingerprint and rebuilds (waiting
  if needed). The stale rows are therefore never served outside a running sync. There is no separate regression test
  for this interleaving.

## Remaining limitations
* The collation emulation is exact only for ASCII punctuation and symbols plus Latin letters. Non-ASCII symbols and
  other scripts order by code point within their class, which can differ from ICU.
* *(Historical, superseded: all 217 cases now pass on both fixtures; see below.)* The 5 oracle cases have not been
  re-run against this new build. Only the coordinator can run them.
* *(Historical, superseded by "Final coordinator acceptance" below for the paths checked there.)* Not verified in a
  browser:
  * the JS server-mode code paths: fetch, row rebuild, lazy values, column reorder of fetched rows, sync polling
  * the Imports sync UI
  The Node tests cover only the pure helpers. The coordinator's browser run is the proof.
* Natural-order ranks emulate `Intl.Collator('en',{numeric,sensitivity:'base'})` with the hand-written comparator
  described in Round 2 below, not with `java.text.Collator`. It is not full ICU equivalence: non-ASCII symbols and
  non-Latin scripts order by code point within their class, and ICU contractions and expansions (such as `ß`, or
  ligatures that do not decompose) are not modelled.
* Text operators lower-case with SQL `LOWER`, and blank tests use SQL `TRIM`. These differ from JS only for exotic
  Unicode and whitespace.
* *(Historical, superseded: the coordinator ran the oracle on the final candidate; see below.)* The parent's 217
  frozen old-JS acceptance cases were not available here and were not run.
* The model fingerprint misses direct SQL edits that change neither those counts nor the timestamps. All app write
  paths change them.
* One app instance per database, as before: the work gate is in-process.

## Final coordinator acceptance (final candidate, after Round 3)

The coordinator recorded this evidence on isolated instances only (:8098 and :8099). There were no production
mutations. The evidence files are in `/root/projects/eol-ledger-ops/server-paging-live-sync/continuation-02/`.

* **Artifact.** `final-candidate.jar` was built after Round 3. SHA-256:
  `3a9e337a98f77607950c784063ea658a1e57f3b03b3b783e1df4c2484beba23d`.
* **Build and tests.**
  * `mvn verify` (`final-maven-verify.log`): 210 tests, 0 failures, 0 errors, 12 skipped. All 12 skips are
    `MySqlStorageTest`, which needs a dedicated MySQL server.
  * Node (`final-node-tests.log`): 69/69 pass.
* **Old-JS oracle.**
  * 217 frozen cases on the catalogue-only fixture (:8098, `oracle-catalogue-final.json`).
  * 217 cases on the populated fixture (:8099, `oracle-populated-final.json`).
  * Result: 434/434 pass. Both the first-row identity and the matching-count assertions pass, including the
    5 sort cases that failed in Round 2.
* **Real catalogue sync** (`official-sync.json`): the browser ran clear, then the official full sync.
  * It ended in success with 489 products and 9470 cycles.
  * 14 status samples showed 4 distinct running counter values.
  * A duplicate start returned 409.
  * The first raw document fetched straight after the sync finished had 20 rows with total 9470
    (`official-first-document.html`). The Round 3 stale-model fix held.
* **Browser UI** (`final-ui.json`, `final-filter-layout.png`):
  * Page sizes 20/50/100, Next, a copied URL restoring the same view, and a global Python filter all work.
  * The cycle column's Values list showed 9466 distinct values for 9470 cycles.
  * The Text filter "Python" gave 35 of 9470.
  * A keyboard (Alt+Left) cycle-column reorder persisted across a reload, and fetched page rows kept the header
    order.
  * There were 0 page errors and the CSP was unchanged.
  * The screenshot was checked visually: "35 of 9470", "Page 1 of 2", 20 per page.
* **No-JS first document** (`final-first-document.html`): 20 rows.

These checks supersede the "not verified in a browser" and "217 oracle not run" notes above, for the paths named here
only.

Still not covered by this acceptance, and still genuine limits:
* **Mouse resize and drag reorder** were not browser-checked; only the existing Node tests cover them.
* **ICU/Unicode collation** is approximated, not equivalent. See the comparator notes above.
* **Fuzzy filters** do a residual scan in Java over the rows the other filters leave.
* **The read model refreshes lazily** after imports and syncs, at most every 3 s during a sync. It is built eagerly
  only at startup.
* **MySQL** DDL and queries were not executed.
* **Without JavaScript** the first page renders, but the filter, sort and paging controls need JavaScript.

## Production acceptance (deployment-01)

The coordinator deployed and checked production. The evidence files are in
`/root/projects/eol-ledger-ops/server-paging-live-sync/deployment-01/`: `deployment.json`, `acceptance.json`,
`live-preservation-detail.json`, `final-ui.json` and `live-pagination/browser-pagination.json`.

* **Deployment.**
  * URL `http://192.168.111.176:8092`, production PID 282206.
  * The original working directory (`/root/projects/eol-ledger`), arguments, config and environment were kept.
  * The JAR is the immutable `final-candidate.jar`, SHA-256
    `3a9e337a98f77607950c784063ea658a1e57f3b03b3b783e1df4c2484beba23d`.
* **Backup and restart.**
  * A fresh cold backup was taken after a clean shutdown: `deployment-01/backup/eol-ledger.mv.db`, SHA-256
    `05ac59abf69996e50f8e294b820a8dda2967bd22fcdcabc3290bc9715894313b`, plus the previous JAR.
  * Stop to observed health took 20.384 s; health is UP.
* **First response.** The raw first response went from 9470 rows (10,991,540 bytes) to 20 rows (26,940 bytes).
* **Oracle and comparison.**
  * All 217 old-JS oracle cases pass live, including the counts.
  * A full old/new comparison over 9470 rows and 13 columns found 0 differences in semantics, values or links.
  * The initial strict HTML hash comparison failed for one reason only: the new page writes an explicit
    `data-value="Not installed"`, where the old page had no attribute and fell back to the same cell text. The value
    is the same. The diagnostic is preserved in the evidence.
* **Data preserved.** Repos (0) and history (0) are unchanged; the empty state was actually seen before and after.
* **Live browser checks.**
  * 20/50/100 page sizes, Next, and the global Python filter (35 of 9470) work.
  * The Values list showed 9466 distinct values.
  * Keyboard reorder carried through to fetched rows and survived a reload.
  * The no-JS first page has 20 rows; there were 0 page errors.
* **No production writes.** No POST, import, clear or sync was run against production. The real official full sync
  was tested on isolated instances only.
* **Other instances.** The coordinator-owned previews on :8098 and :8099 were stopped; :8097 was not touched.
* **Product tests** for the deployed commit: Maven 210 tests, 0 failures, 12 MySQL skips; Node 69/69. These were local
  runs; no external CI run is claimed.

The limits listed above still apply: MySQL was never executed, ICU/Unicode collation is approximated, fuzzy filters do
a residual scan in Java, and the read model refreshes lazily (at most every 3 s during a sync).
