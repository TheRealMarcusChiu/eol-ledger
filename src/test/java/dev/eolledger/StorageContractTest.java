package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Storage behaviour every supported database must satisfy, written once and run against H2 ({@link H2StorageTest})
 * and, opt-in, a real MySQL 8 ({@link MySqlStorageTest}). Uses only the app's own read/write paths plus portable SQL.
 */
abstract class StorageContractTest extends WebTestBase {

    @Autowired Ledger ledger;

    static final String HEAD = "repo_name,repo_url,provider,branch,sbom_url\n";

    static String row(String repoUrl, String branch, String path) {
        return "Repo," + repoUrl + ",github," + branch + "," + TestSupport.base() + path + "\n";
    }

    ImportService.Result importRows(String... rows) {
        return imports.importCsv(HEAD + String.join("", rows), "contract");
    }

    void serve(String path, String body) {
        TestSupport.REPLIES.put(path, TestSupport.Reply.ok(body));
    }

    int count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void generatedIdsAreDistinctAndStableAcrossUnchangedAndMetadataOnlyReimports() {
        serve("/a.json", cdx("pkg:npm/react@18.2.0"));
        serve("/b.json", cdx("pkg:npm/react@18.2.0"));
        importRows(row("https://github.com/acme/one", "main", "/a.json"), row("https://github.com/acme/two", "main", "/b.json"));
        long one = branchId("https://github.com/acme/one", "main");
        long two = branchId("https://github.com/acme/two", "main");
        long snap = db.sql("SELECT id FROM snapshot WHERE branch_id = ?").param(one).query(Long.class).single();

        ImportService.Result r = imports.importCsv(HEAD + "Renamed,https://github.com/acme/one,gitlab,main," + TestSupport.base() + "/a.json\n"
                + row("https://github.com/acme/two", "main", "/b.json"), "contract");

        assertThat(one).isPositive().isNotEqualTo(two);
        assertThat(r.newSnapshots()).isZero();
        assertThat(branchId("https://github.com/acme/one", "main")).isEqualTo(one);
        assertThat(db.sql("SELECT id FROM snapshot WHERE branch_id = ?").param(one).query(Long.class).single()).isEqualTo(snap);
        assertThat(db.sql("SELECT repo_name FROM repo_branch WHERE id = ?").param(one).query(String.class).single()).isEqualTo("Renamed");
        assertThat(count("repo_branch")).isEqualTo(2);
        assertThat(count("snapshot")).isEqualTo(2);
        assertThat(count("dependency")).isEqualTo(2);
    }

    @Test
    void identitiesDifferingOnlyInCaseStayDistinct() {
        serve("/a.json", cdx("pkg:maven/Org.Acme/x@1.0", "pkg:maven/org.acme/x@1.0"));
        importRows(row("https://github.com/acme/Web", "main", "/a.json"), row("https://github.com/acme/web", "main", "/a.json"),
                row("https://github.com/acme/web", "Main", "/a.json"));
        serve("/api/v1/products/full", """
                {"result":[
                 {"name":"acme-upper","label":"U","identifiers":[{"type":"purl","id":"pkg:maven/Org.Acme/x"}],"releases":[{"name":"1.0","eolFrom":"2027-01-01"}]},
                 {"name":"acme-lower","label":"L","identifiers":[{"type":"purl","id":"pkg:maven/org.acme/x"}],"releases":[{"name":"1.0","eolFrom":"2028-01-01"}]}]}""");
        assertThat(catalog.sync().ok()).isTrue();

        assertThat(count("repo_branch")).isEqualTo(3);
        assertThat(List.of(branchId("https://github.com/acme/Web", "main"), branchId("https://github.com/acme/web", "main"),
                branchId("https://github.com/acme/web", "Main"))).doesNotHaveDuplicates();
        assertThat(count("product_identifier")).isEqualTo(2);
        long snap = ledger.branch(branchId("https://github.com/acme/web", "Main")).snapshotId();
        assertThat(ledger.deps(snap)).extracting(d -> d.purl() + "=" + d.cycle().product() + "/" + d.cycle().eolFrom())
                .containsExactlyInAnyOrder("pkg:maven/Org.Acme/x@1.0=acme-upper/2027-01-01", "pkg:maven/org.acme/x@1.0=acme-lower/2028-01-01");
    }

    @Test
    void longIdentitiesSharingPrefixesBeyondIndexLimitsStayDistinctAndUntruncated() {
        String repoA = "https://github.com/acme/" + "p".repeat(975) + "a";
        String repoB = "https://github.com/acme/" + "p".repeat(975) + "b";
        String branch1 = "b".repeat(499) + "1";
        String branch2 = "b".repeat(499) + "2";
        serve("/a.json", cdx("pkg:npm/react@18.2.0"));
        importRows(row(repoA, branch1, "/a.json"), row(repoA, branch2, "/a.json"), row(repoB, branch1, "/a.json"));
        long a1 = branchId(repoA, branch1);
        assertThat(importRows(row(repoA, branch1, "/a.json"), row(repoA, branch2, "/a.json"), row(repoB, branch1, "/a.json")).newSnapshots()).isZero();

        String purl1 = "pkg:npm/" + "q".repeat(991) + "1";
        String purl2 = "pkg:npm/" + "q".repeat(991) + "2";
        String catalogue = "{\"result\":[{\"name\":\"long-a\",\"identifiers\":[{\"type\":\"purl\",\"id\":\"" + purl1 + "\"},{\"type\":\"purl\",\"id\":\"" + purl2
                + "\"}],\"releases\":[]},{\"name\":\"long-b\",\"identifiers\":[{\"type\":\"purl\",\"id\":\"" + purl1 + "\"}],\"releases\":[]}]}";
        serve("/api/v1/products/full", catalogue);
        assertThat(catalog.sync().ok()).isTrue();
        assertThat(catalog.sync().ok()).isTrue();

        assertThat(count("repo_branch")).isEqualTo(3);
        assertThat(branchId(repoA, branch1)).isEqualTo(a1);
        assertThat(List.of(a1, branchId(repoA, branch2), branchId(repoB, branch1))).doesNotHaveDuplicates();
        assertThat(db.sql("SELECT repo_url FROM repo_branch WHERE id = ?").param(a1).query(String.class).single()).isEqualTo(repoA).hasSize(1000);
        assertThat(db.sql("SELECT branch FROM repo_branch WHERE id = ?").param(a1).query(String.class).single()).isEqualTo(branch1);
        assertThat(db.sql("SELECT product, purl FROM product_identifier ORDER BY product, purl").query((rs, i) -> rs.getString(1) + "=" + rs.getString(2)).list())
                .containsExactly("long-a=" + purl1, "long-a=" + purl2, "long-b=" + purl1);
    }

    @Test
    void updateThenRevertKeepsEveryStateInHistory() {
        String csvRow = row("https://github.com/acme/web", "main", "/h.json");
        serve("/h.json", cdx("pkg:npm/react@18.2.0"));
        importRows(csvRow);
        CLOCK.now = CLOCK.now.plusSeconds(60);
        serve("/h.json", cdx("pkg:npm/react@19.0.0"));
        importRows(csvRow);
        CLOCK.now = CLOCK.now.plusSeconds(60);
        serve("/h.json", cdx("pkg:npm/react@18.2.0"));
        assertThat(importRows(csvRow).newSnapshots()).isEqualTo(1);

        List<Ledger.Snap> t = ledger.timeline(branchId("https://github.com/acme/web", "main"));
        assertThat(t).hasSize(3);
        assertThat(t).extracting(Ledger.Snap::id).doesNotHaveDuplicates().isSorted();
        assertThat(t.get(1).diff().changed()).containsExactly("pkg:npm/react 18.2.0 → 19.0.0");
        assertThat(t.get(2).diff().changed()).containsExactly("pkg:npm/react 19.0.0 → 18.2.0");
        assertThat(t.get(2).observedAt()).isEqualTo(CLOCK.now);
    }

    @Test
    void databaseFailureMidImportRollsBackEveryRowAndIsRecorded() {
        serve("/a.json", cdx("pkg:npm/react@18.2.0"));
        importRows(row("https://github.com/acme/one", "main", "/a.json"));
        serve("/a.json", cdx("pkg:npm/react@19.0.0"));
        // a package name longer than the column: only the database can reject it, after row one was already written
        serve("/bad.json", cdx("pkg:npm/" + "z".repeat(600) + "@1.0"));
        CLOCK.now = CLOCK.now.plusSeconds(60);

        assertThatThrownBy(() -> importRows(row("https://github.com/acme/one", "main", "/a.json"), row("https://github.com/acme/two", "main", "/bad.json")))
                .isInstanceOf(ImportService.ImportFailedException.class).hasMessageContaining("nothing was changed");

        assertThat(count("repo_branch")).isEqualTo(1);
        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(db.sql("SELECT version FROM dependency").query(String.class).list()).containsExactly("18.2.0");
        assertThat(db.sql("SELECT ok FROM import_run ORDER BY id DESC LIMIT 1").query(Boolean.class).single()).isFalse();
    }

    @Test
    void endOfDayInNonUtcZoneUsesExactFractionalSecondsWithoutRoundingIntoNextDay() {
        String csvRow = row("https://github.com/acme/web", "main", "/h.json");
        // Europe/Amsterdam (test eol.zone) is UTC+1 in November: 21 Nov ends at 23:00Z
        CLOCK.now = Instant.parse("2026-11-21T10:00:00.123456Z");
        serve("/h.json", cdx("pkg:npm/react@18.2.0"));
        importRows(csvRow);
        CLOCK.now = Instant.parse("2026-11-21T22:59:59.9999996Z"); // rounding to micros would land on 22 Nov
        serve("/h.json", cdx("pkg:npm/react@19.0.0"));
        importRows(csvRow);
        CLOCK.now = Instant.parse("2026-11-21T23:00:00Z");
        serve("/h.json", cdx("pkg:npm/react@19.1.0"));
        importRows(csvRow);
        long id = branchId("https://github.com/acme/web", "main");

        assertThat(ledger.timeline(id)).extracting(Ledger.Snap::observedAt).containsExactly(
                Instant.parse("2026-11-21T10:00:00.123456Z"), Instant.parse("2026-11-21T22:59:59.999999Z"), Instant.parse("2026-11-21T23:00:00Z"));
        assertThat(ledger.deps(ledger.asOf(id, LocalDate.parse("2026-11-21")).id())).extracting(Ledger.Dep::version).containsExactly("19.0.0");
        assertThat(ledger.deps(ledger.asOf(id, LocalDate.parse("2026-11-22")).id())).extracting(Ledger.Dep::version).containsExactly("19.1.0");
        assertThat(ledger.asOf(id, LocalDate.parse("2026-11-20"))).isNull();
        assertThat(ledger.branch(id).observedAt()).isEqualTo(Instant.parse("2026-11-21T23:00:00Z"));
    }

    @Test
    void catalogueTimestampsRoundTripAndFailedSyncKeepsDates() {
        CLOCK.now = Instant.parse("2026-11-20T23:30:00.654321Z");
        seed();
        Instant firstSync = CLOCK.now;
        assertThat(catalog.sync().ok()).isTrue(); // unchanged re-sync is idempotent
        int cycles = count("release_cycle");
        CLOCK.now = Instant.parse("2026-11-21T08:00:00.000001Z");
        serve("/api/v1/products/full", "{\"result\":[{\"name\":\"react\",\"releases\":[{\"name\":\"18\",\"eolFrom\":\"not-a-date\"}]}]}");

        assertThat(catalog.sync().ok()).isFalse();

        assertThat(count("release_cycle")).isEqualTo(cycles);
        assertThat(count("product")).isEqualTo(3);
        assertThat(ledger.productCycles("react")).extracting(c -> c.name() + "=" + c.eolFrom()).contains("18=2025-01-01");
        assertThat(ledger.productCycles("python")).filteredOn(c -> c.name().equals("2.7")).extracting(Ledger.Cycle::isEol).containsExactly(true);
        assertThat(ledger.product("react").syncedAt()).isEqualTo(firstSync);
        CatalogService.Status s = catalog.status();
        assertThat(s.ok()).isFalse();
        assertThat(s.attemptedAt()).isEqualTo(CLOCK.now);
        assertThat(s.succeededAt()).isEqualTo(firstSync);
    }

    @Test
    void everyPageRendersFromThisDatabase() throws Exception {
        seed();
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        long dep = db.sql("SELECT min(d.id) FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE s.branch_id = ?").param(id).query(Long.class).single();
        for (String url : List.of("/", "/?start=&end=", "/repos", "/repos/" + id, "/repos/" + id + "/deps/" + dep, "/repos/" + id + "/history",
                "/products/python", "/products/python?cycle=3.10", "/imports")) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }
        assertThat(mvc.perform(get("/imports")).andReturn().getResponse().getContentAsString()).contains("2026-11-20");
        assertThat(count("import_run")).isEqualTo(1);
    }
}
