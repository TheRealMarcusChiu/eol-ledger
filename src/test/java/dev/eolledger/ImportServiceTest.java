package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportServiceTest extends IntegrationTest {

    @Autowired ImportService imports;

    static final String HEADER = "repo_name,repo_url,provider,branch,sbom_url\n";

    static String cdx(String... comps) {
        return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[" + String.join(",", comps) + "]}";
    }

    static String comp(String name, String version) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"purl\":\"pkg:npm/" + name + "@" + version + "\"}";
    }

    static String row(String repo, String branch, String sbomPath) {
        return "Demo," + repo + ",github," + branch + "," + TestSupport.base() + sbomPath + "\n";
    }

    void serve(String path, String body) {
        TestSupport.REPLIES.put(path, TestSupport.Reply.ok(body));
    }

    int count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void importCreatesBranchSnapshotAndDependencies() {
        serve("/a.json", cdx(comp("react", "18.2.0"), comp("left-pad", "1.3.0")));

        ImportService.Result r = imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json"), "upload");

        assertThat(r.rows()).isEqualTo(1);
        assertThat(r.newSnapshots()).isEqualTo(1);
        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(count("dependency")).isEqualTo(2);
    }

    @Test
    void identicalReimportFetchesAgainButAddsNoHistory() {
        serve("/a.json", cdx(comp("react", "18.2.0"), comp("left-pad", "1.3.0")));
        String csv = HEADER + row("https://github.com/acme/web", "main", "/a.json");
        imports.importCsv(csv, "upload");
        TestSupport.AUTH_HEADERS.clear();
        // same inventory in a different order
        serve("/a.json", cdx(comp("left-pad", "1.3.0"), comp("react", "18.2.0")));

        ImportService.Result r = imports.importCsv(csv, "upload");

        assertThat(TestSupport.AUTH_HEADERS).hasSize(1);
        assertThat(r.newSnapshots()).isZero();
        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(count("repo_branch")).isEqualTo(1);
        assertThat(count("dependency")).isEqualTo(2);
    }

    @Test
    void changedRemoteContentCreatesNewSnapshotAndKeepsPrior() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        String csv = HEADER + row("https://github.com/acme/web", "main", "/a.json");
        imports.importCsv(csv, "upload");
        serve("/a.json", cdx(comp("react", "18.3.1")));
        CLOCK.now = CLOCK.now.plus(Duration.ofMinutes(5));

        assertThat(imports.importCsv(csv, "upload").newSnapshots()).isEqualTo(1);
        assertThat(db.sql("SELECT d.version FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id ORDER BY s.observed_at").query(String.class).list())
                .containsExactly("18.2.0", "18.3.1");
    }

    @Test
    void changedSbomUrlCreatesNewSnapshotEvenWithSameContent() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        serve("/b.json", cdx(comp("react", "18.2.0")));
        imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json"), "upload");
        assertThat(imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/b.json"), "upload").newSnapshots()).isEqualTo(1);
    }

    @Test
    void branchesOfOneRepoKeepSeparateSnapshots() {
        serve("/main.json", cdx(comp("react", "18.2.0")));
        serve("/rel.json", cdx(comp("react", "17.0.2")));

        imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/main.json")
                + row("https://github.com/acme/web", "release/2.x", "/rel.json"), "upload");

        List<Map<String, Object>> rows = db.sql("""
                SELECT b.branch, d.version FROM repo_branch b JOIN snapshot s ON s.branch_id = b.id JOIN dependency d ON d.snapshot_id = s.id ORDER BY b.branch""")
                .query().listOfRows();
        assertThat(rows).extracting(m -> m.get("BRANCH") + "=" + m.get("VERSION")).containsExactly("main=18.2.0", "release/2.x=17.0.2");
    }

    @Test
    void revertedInventoryCreatesNewSnapshotEachTime() {
        String csv = HEADER + row("https://github.com/acme/web", "main", "/a.json");
        serve("/a.json", cdx(comp("react", "18.2.0")));
        imports.importCsv(csv, "upload");
        serve("/a.json", cdx(comp("react", "19.0.0")));
        CLOCK.now = CLOCK.now.plusSeconds(60);
        imports.importCsv(csv, "upload");
        serve("/a.json", cdx(comp("react", "18.2.0")));
        CLOCK.now = CLOCK.now.plusSeconds(60);

        assertThat(imports.importCsv(csv, "upload").newSnapshots()).isEqualTo(1);
        assertThat(count("snapshot")).isEqualTo(3);
    }

    @Test
    void invalidRowRejectsWholeFileWithoutTouchingExistingData() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json"), "upload");
        serve("/a.json", cdx(comp("react", "19.0.0")));

        assertThatThrownBy(() -> imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json")
                + "Bad,https://github.com/acme/x,github,main,file:///etc/passwd\n", "upload"))
                .isInstanceOf(RegistryCsv.InvalidCsvException.class);

        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(db.sql("SELECT version FROM dependency").query(String.class).list()).containsExactly("18.2.0");
        assertThat(db.sql("SELECT ok FROM import_run ORDER BY id DESC LIMIT 1").query(Boolean.class).single()).isFalse();
    }

    @Test
    void sbomFetchFailureRejectsWholeImportAndHidesToken() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        TestSupport.REPLIES.put("/down.json", new TestSupport.Reply(503, "down", Map.of()));

        assertThatThrownBy(() -> imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json")
                + row("https://gitlab.com/acme/api", "main", "/down.json"), "upload"))
                .isInstanceOf(ImportService.ImportFailedException.class)
                .hasMessageContaining("HTTP 503")
                .hasMessageNotContaining("test-secret-token");

        assertThat(count("repo_branch")).isZero();
        assertThat(count("snapshot")).isZero();
        assertThat(db.sql("SELECT message FROM import_run").query(String.class).single()).doesNotContain("test-secret-token").contains("line 3");
    }

    @Test
    void invalidSbomBodyIsRejected() {
        serve("/bad.json", "<bom/>");
        assertThatThrownBy(() -> imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/bad.json"), "upload"))
                .hasMessageContaining("JSON");
    }

    @Test
    void perHostTokenIsSentAsBearer() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json"), "upload");
        assertThat(TestSupport.AUTH_HEADERS).containsExactly("Bearer test-secret-token");
    }

    @Test
    void metadataChangeUpdatesRegistryWithoutNewSnapshotAndOmittedRowsStay() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        serve("/b.json", cdx(comp("vue", "3.4.0")));
        imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json") + row("https://github.com/acme/other", "main", "/b.json"), "upload");

        ImportService.Result r = imports.importCsv(HEADER + "Renamed,https://github.com/acme/web,gitlab,main," + TestSupport.base() + "/a.json\n", "upload");

        assertThat(r.newSnapshots()).isZero();
        assertThat(db.sql("SELECT repo_name || '/' || provider FROM repo_branch WHERE repo_url='https://github.com/acme/web'").query(String.class).single())
                .isEqualTo("Renamed/gitlab");
        assertThat(count("repo_branch")).isEqualTo(2);
        assertThat(count("snapshot")).isEqualTo(2);
    }

    @Test
    void sameDayChangesAreAllRetainedInOrder() {
        String csv = HEADER + row("https://github.com/acme/web", "main", "/a.json");
        serve("/a.json", cdx(comp("react", "18.0.0")));
        imports.importCsv(csv, "upload");
        CLOCK.now = CLOCK.now.plus(Duration.ofHours(1));
        serve("/a.json", cdx(comp("react", "18.1.0")));
        imports.importCsv(csv, "upload");

        assertThat(db.sql("SELECT d.version FROM snapshot s JOIN dependency d ON d.snapshot_id = s.id ORDER BY s.observed_at").query(String.class).list())
                .containsExactly("18.0.0", "18.1.0");
    }

    @Test
    void bundledSampleCsvImports() {
        ImportService.Result r = imports.importCsv(ImportService.sampleCsv("demo-registry.csv"), "sample");
        assertThat(r.rows()).isGreaterThanOrEqualTo(4);
        assertThat(db.sql("SELECT count(DISTINCT repo_url) FROM repo_branch").query(Integer.class).single()).isEqualTo(3);
    }

    /** Imports a good inventory, then asserts {@code badSbom} is rejected and the good latest snapshot is untouched. */
    void assertRejectedKeepingPriorInventory(String badSbom, String expectedMessage) {
        String csv = HEADER + row("https://github.com/acme/web", "main", "/a.json");
        serve("/a.json", cdx(comp("react", "18.2.0"), comp("left-pad", "1.3.0")));
        imports.importCsv(csv, "upload");
        serve("/a.json", badSbom);
        CLOCK.now = CLOCK.now.plusSeconds(60);

        assertThatThrownBy(() -> imports.importCsv(csv, "upload"))
                .isInstanceOf(ImportService.ImportFailedException.class).hasMessageContaining(expectedMessage);

        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(db.sql("SELECT name || '@' || version FROM dependency ORDER BY name").query(String.class).list())
                .containsExactly("left-pad@1.3.0", "react@18.2.0");
    }

    @Test
    void cycloneDxWithoutComponentsArrayKeepsPriorInventory() {
        assertRejectedKeepingPriorInventory("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\"}", "components");
        assertRejectedKeepingPriorInventory("{\"bomFormat\":\"CycloneDX\",\"components\":{\"name\":\"react\"}}", "components");
    }

    @Test
    void spdxWithoutPackagesArrayKeepsPriorInventory() {
        assertRejectedKeepingPriorInventory("{\"spdxVersion\":\"SPDX-2.3\",\"packages\":null}", "packages");
    }

    @Test
    void malformedRecordsKeepPriorInventory() {
        assertRejectedKeepingPriorInventory(cdx(comp("react", "18.3.1"), "{\"version\":\"1\"}"), "name");
        assertRejectedKeepingPriorInventory("{\"spdxVersion\":\"SPDX-2.3\",\"packages\":[{\"name\":\"react\",\"versionInfo\":18}]}", "versionInfo");
    }

    @Test
    void explicitEmptyComponentsIsAnIntentionalEmptySnapshot() {
        String csv = HEADER + row("https://github.com/acme/web", "main", "/a.json");
        serve("/a.json", cdx(comp("react", "18.2.0")));
        imports.importCsv(csv, "upload");
        serve("/a.json", cdx());
        CLOCK.now = CLOCK.now.plusSeconds(60);

        assertThat(imports.importCsv(csv, "upload").newSnapshots()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE s.observed_at = ?")
                .param(java.sql.Timestamp.from(CLOCK.now)).query(Integer.class).single()).isZero();
    }

    @Test
    void allowlistedHostWithoutTokenFetchesUnauthenticated() {
        serve("/public.json", cdx(comp("react", "18.2.0")));
        String url = TestSupport.base().replace("127.0.0.1", "localhost") + "/public.json";

        assertThat(imports.importCsv(HEADER + "Pub,https://github.com/acme/pub,github,main," + url + "\n", "upload").newSnapshots()).isEqualTo(1);
        assertThat(TestSupport.AUTH_HEADERS).containsExactly("null");
    }

    @Test
    void unterminatedQuoteRejectsUploadAndKeepsInventory() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json"), "upload");

        assertThatThrownBy(() -> imports.importCsv(HEADER + "Web,https://github.com/acme/web,github,main,\"" + TestSupport.base() + "/a.json\n", "upload"))
                .isInstanceOf(RegistryCsv.InvalidCsvException.class).hasMessageContaining("quote");

        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(db.sql("SELECT ok FROM import_run ORDER BY id DESC LIMIT 1").query(Boolean.class).single()).isFalse();
    }

    @Test
    void mavenCoordinatesDifferingOnlyInCaseStayDistinct() {
        serve("/m.json", "{\"bomFormat\":\"CycloneDX\",\"components\":["
                + "{\"name\":\"x\",\"version\":\"1\",\"purl\":\"pkg:maven/Org.Acme/x@1\"},"
                + "{\"name\":\"x\",\"version\":\"1\",\"purl\":\"pkg:maven/org.acme/x@1\"}]}");
        imports.importCsv(HEADER + row("https://github.com/acme/m", "main", "/m.json"), "upload");
        long branch = db.sql("SELECT id FROM repo_branch").query(Long.class).single();

        assertThat(count("dependency")).isEqualTo(2);
        assertThat(ledger.timeline(branch).get(0).diff().added()).containsExactlyInAnyOrder("pkg:maven/Org.Acme/x 1", "pkg:maven/org.acme/x 1");
    }

    @Autowired Ledger ledger;

    @Test
    void registryRefreshRefetchesStoredBranches() {
        serve("/a.json", cdx(comp("react", "18.2.0")));
        imports.importCsv(HEADER + row("https://github.com/acme/web", "main", "/a.json"), "upload");
        serve("/a.json", cdx(comp("react", "19.0.0")));
        CLOCK.now = CLOCK.now.plusSeconds(30);

        assertThat(imports.refreshRegistry("scheduled").newSnapshots()).isEqualTo(1);
    }
}
