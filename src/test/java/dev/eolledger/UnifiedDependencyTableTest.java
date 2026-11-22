package dev.eolledger;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UnifiedDependencyTableTest extends WebTestBase {
    String page(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    List<String> headers(String html) {
        return Pattern.compile("<th scope=\"col\"([^>]*)>([^<]+)</th>").matcher(html).results()
                .map(m -> m.group(1) + ":" + m.group(2)).toList();
    }
    @Test void sameSchemaForDashboardJsonAndNonJsonAndHistory() throws Exception {
        seed();
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        var expected = List.of(
                " data-column=\"purl\":Ecosystem / Package URL",
                " data-column=\"dependency\":Dependency Name", " data-type=\"version\" data-column=\"version\":Version",
                " data-type=\"date\" data-column=\"eol\":EOL Date", " data-column=\"status\":Status",
                " data-type=\"number\" class=\"num\" data-column=\"repos\":# Code Repos Using It",
                " data-type=\"number\" class=\"num\" data-column=\"branches\":# Code Repo Branches Using It",
                " data-column=\"context\":Project context",
                " data-column=\"cycle\":Upstream cycle", " data-column=\"evidence\":Matching evidence",
                " data-column=\"source\":Source control", " data-column=\"sbom\":SBOM");
        String root = page("/"), latest = page("/repos/" + id), history = AcceptanceRepairTest.depsTable(page("/repos/" + id + "/history"));
        for (String html : List.of(root, latest, history)) {
            assertThat(headers(html)).containsExactlyElementsOf(expected);
            assertThat(html).doesNotContain("Ecosystem / purl");
        }
        db.sql("DELETE FROM snapshot_inventory").update();
        String fallback = page("/repos/" + id);
        assertThat(headers(fallback)).containsExactlyElementsOf(expected);
        assertThat(fallback).doesNotContain("Ecosystem / purl");
    }
    @Test void serverRenderedOrderAndCellMeaningWorkWithoutJavascript() throws Exception {
        seed();
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        var keys = List.of("purl", "dependency", "version", "eol", "status", "repos", "branches",
                "context", "cycle", "evidence", "source", "sbom");
        for (String path : List.of("/", "/repos/" + id, "/repos/" + id + "/history")) {
            String table = path.equals("/") ? page(path) : AcceptanceRepairTest.depsTable(page(path));
            assertThat(Pattern.compile("<col data-column=\"([^\"]+)\"").matcher(table).results()
                    .map(m -> m.group(1)).toList()).as(path + " colgroup").containsExactlyElementsOf(keys);
            var rows = Pattern.compile("<tr[^>]*>.*?</tr>", Pattern.DOTALL).matcher(table).results()
                    .map(m -> m.group()).filter(r -> r.contains("<td data-column=")).toList();
            assertThat(rows).isNotEmpty();
            for (String row : rows) {
                assertThat(Pattern.compile("<td data-column=\"([^\"]+)\"").matcher(row).results()
                        .map(m -> m.group(1)).toList()).as(path + " cells").containsExactlyElementsOf(keys);
            }
            String react = rows.stream().filter(r -> r.contains(">react</a>")).findFirst().orElseThrow();
            var cells = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL).matcher(react).results()
                    .map(m -> m.group(1)).toList();
            assertThat(cells.get(0)).contains("pkg:npm/react@18.2.0");
            assertThat(cells.get(1)).contains(">react</a>");
            assertThat(cells.get(2)).contains("18.2.0");
            assertThat(cells.get(3)).contains("2025-01-01");
            assertThat(cells.get(4)).contains("eol-ended", "Ended");
            assertThat(cells.get(5)).isEqualTo("1");
            assertThat(cells.get(6)).isEqualTo(path.equals("/") ? "2" : "1");
            assertThat(cells.get(8)).contains("React 18");
            assertThat(cells.get(10)).contains("https://gitlab.com/acme/alpha");
        }
    }
    @Test void aggregateValuesAreInstalledEvidenceNotCatalogueLatestAndCountsStayScoped() throws Exception {
        seed();
        String html = page("/");
        int start = html.indexOf("data-row=\"react 18\"");
        String row = html.substring(start, html.indexOf("</tr>", start));
        assertThat(row).contains("data-repos=\"1\" data-branches=\"2\"", "18.2.0", "18.3.0", "pkg:npm/react@18.2.0", "https://gitlab.com/acme/alpha")
                .doesNotContain("18.3.1");
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        String table = AcceptanceRepairTest.depsTable(page("/repos/" + id));
        assertThat(Pattern.compile("<tr data-href=").matcher(table).results().count()).isEqualTo(4);
        assertThat(table).contains("core", "data-column=\"repos\" class=\"num\" data-value=\"1\">1</td>");
    }
    @Test void allPackagesSurviveIncludingUnknownCycleConflictsAndMissingVersions() throws Exception {
        seed();
        imports.importJson(JsonImportTest.array(JsonImportTest.entry("mixed", "2026-11-20T10:00:00Z",
                JsonImportTest.REACT,
                "{\"name\":\"react-conflict\",\"version\":\"19.0.0\",\"purl\":\"pkg:npm/react@19.0.0\"}",
                "{\"name\":\"react-future\",\"version\":\"99.0.0\",\"purl\":\"pkg:npm/react@99.0.0\"}",
                "{\"name\":\"unknown-npm\",\"version\":\"1.2.3\",\"purl\":\"pkg:npm/unknown-npm@1.2.3\"}",
                "{\"name\":\"unstated\",\"version\":\"1\"}")), "test");
        long id = branchId(JsonImportTest.REPO, "mixed");
        // Legacy/SBOM inventories can contain conflicting version evidence; the JSON upload correctly refuses it.
        db.sql("UPDATE dependency SET version = '18.2.0' WHERE name = 'react-conflict'").update();
        db.sql("UPDATE dependency SET version = NULL WHERE name = 'unstated'").update();
        String table = AcceptanceRepairTest.depsTable(page("/repos/" + id));
        assertThat(Pattern.compile("<tr data-href=").matcher(table).results().count()).isEqualTo(5);
        for (String name : List.of("react", "react-conflict", "react-future", "unknown-npm", "unstated")) {
            assertThat(Pattern.compile(">" + name + "</a>").matcher(table).results().count()).as(name).isEqualTo(1);
        }
        assertThat(table).contains("18.2.0 (purl: 19.0.0)", "not stated", "No upstream mapping", "cycle unknown");
        assertThat(table.indexOf("data-href=\"/dependencies/react?version=18\""))
                .isLessThan(table.indexOf(">react-conflict</a>"));
    }
    @Test void aggregateContextAndSnapshotProvenanceAreTruthfulAndUnique() throws Exception {
        seed();
        imports.importJson(JsonImportTest.array(JsonImportTest.entry("context", "2026-11-20T10:00:00Z", JsonImportTest.REACT,
                JsonImportTest.REACT.replace("backend-maven", "worker-gradle"))), "test");
        String html = page("/");
        int start = html.indexOf("data-row=\"react 18\"");
        String row = html.substring(start, html.indexOf("</tr>", start));
        assertThat(row).contains("backend-maven: prod, direct", "worker-gradle: prod, direct", "data-repos=\"2\" data-branches=\"3\"");
        String versionCell = row.substring(row.indexOf("data-column=\"version\""), row.indexOf("data-column=\"eol\""));
        assertThat(versionCell).contains("18.2.0; 18.3.0").doesNotContain("18.2.0; 18.2.0");
    }
    @Test void emptySnapshotStillHasTheFullSchemaAndAnHonestEmptyState() throws Exception {
        seed();
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        db.sql("DELETE FROM dependency WHERE snapshot_id IN (SELECT id FROM snapshot WHERE branch_id = ?)").param(id).update();
        String table = AcceptanceRepairTest.depsTable(page("/repos/" + id));
        assertThat(headers(table)).hasSize(12);
        assertThat(table).contains("colspan=\"12\" class=\"empty\">No packages in this snapshot.").doesNotContain("<tr data-href=");
    }
    @Test void noImportsKeepsEveryCatalogueCycleWithoutInventedInstalledVersions() throws Exception {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE));
        catalog.sync();
        String html = page("/");
        assertThat(DashboardTest.rows(html)).hasSize(7);
        assertThat(html).contains("Not installed", "data-repos=\"0\" data-branches=\"0\"").doesNotContain("18.3.1", "19.2.0");
    }
}
