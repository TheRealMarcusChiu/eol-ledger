package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/repos: every code repo branch with its current (latest-snapshot) facts and its ended dependencies. */
class RepositoryEolApiTest extends IntegrationTest {

    @Test
    void emptyDatabaseIsTodayAndNoRepos() throws Exception {
        mvc.perform(get("/api/repos")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.asOf").value("2026-11-20"))
                .andExpect(jsonPath("$.repos", hasSize(0)));
    }

    @org.springframework.beans.factory.annotation.Autowired ImportService imports;
    /** The shared JdbcClient as a spy, so the test can look at the transaction around the report's own query. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void theReportReadsOneReadOnlyRepeatableReadTransaction() throws Exception {
        java.util.List<String> seen = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            if (((String) inv.getArgument(0)).contains("FROM dependency d WHERE d.snapshot_id IN")) {
                seen.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive() + " "
                        + org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly() + " "
                        + org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            }
            return inv.callRealMethod();
        }).when(jdbc).sql(org.mockito.ArgumentMatchers.anyString());
        mvc.perform(get("/api/repos")).andExpect(status().isOk());
        assertThat(seen).containsExactly("true true " + java.sql.Connection.TRANSACTION_REPEATABLE_READ);
    }

    /** django cycles (today is 2026-11-20): ended 2024, ends today, ended without a date, ends 2027, no EoL announced, ended but unused. */
    void catalogue() {
        db.sql("INSERT INTO product (name, label, html_link, synced_at) VALUES ('django', 'Django', 'https://endoflife.date/django', CURRENT_TIMESTAMP)").update();
        db.sql("INSERT INTO product_identifier (product, purl) VALUES ('django', 'pkg:pypi/django')").update();
        db.sql("""
                INSERT INTO release_cycle (product, name, label, is_eol, eol_from) VALUES
                ('django', '3.2', '3.2', TRUE, DATE '2024-04-01'), ('django', '4.2', '4.2', FALSE, DATE '2026-11-20'),
                ('django', '1.11', '1.11', TRUE, NULL), ('django', '5.0', '5.0', FALSE, DATE '2027-04-01'),
                ('django', '6.0', '6.0', FALSE, NULL), ('django', '2.2', '2.2', TRUE, DATE '2022-04-01')""").update();
    }

    static String dep(String name, String version, String purl) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"" + (purl == null ? "" : ",\"purl\":\"" + purl + "@" + version + "\"") + "}";
    }

    @Test
    void eachBranchShowsOnlyItsCurrentFactsAndCurrentlyEndedDependencies() throws Exception {
        catalogue();
        imports.importJson("[{\"name\":\"Alpha\",\"url\":\"https://x.test/alpha\",\"branch\":\"main\",\"appId\":\"APP-1\",\"version\":\"1.0\",\"dependencies\":["
                + dep("django", "2.2.28", "pkg:pypi/django") + "]}]", "test");
        // a later import of the same branch states no App ID or version: the older facts must not show through
        CLOCK.now = java.time.Instant.parse("2026-11-20T13:00:00Z");
        imports.importJson("[{\"name\":\"Alpha\",\"url\":\"https://x.test/alpha\",\"branch\":\"main\",\"dependencies\":["
                + String.join(",", dep("django", "3.2.25", "pkg:pypi/django"), dep("django", "4.2.1", "pkg:pypi/django"), dep("django", "1.11.29", "pkg:pypi/django"),
                dep("django", "5.0.1", "pkg:pypi/django"), dep("django", "6.0.0", "pkg:pypi/django"), dep("left-pad", "1.3.0", null)) + "]}]", "test");
        // a registered branch without any snapshot is listed too
        db.sql("INSERT INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) VALUES ('https://x.test/beta', 'dev', 'Beta', 'github', 'inline:json-upload')").update();
        long alpha = db.sql("SELECT id FROM repo_branch WHERE repo_name = 'Alpha'").query(Long.class).single();
        long latest = db.sql("SELECT max(id) FROM snapshot WHERE branch_id = ?").param(alpha).query(Long.class).single();

        mvc.perform(get("/api/repos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-11-20"))
                .andExpect(jsonPath("$.repos", hasSize(2)))
                .andExpect(jsonPath("$.repos[0].id").value(alpha))
                .andExpect(jsonPath("$.repos[0].repoName").value("Alpha"))
                .andExpect(jsonPath("$.repos[0].repoUrl").value("https://x.test/alpha"))
                .andExpect(jsonPath("$.repos[0].provider").value("other"))
                .andExpect(jsonPath("$.repos[0].branch").value("main"))
                .andExpect(jsonPath("$.repos[0].sbomUrl").value("inline:json-upload"))
                .andExpect(jsonPath("$.repos[0].snapshotId").value(latest))
                .andExpect(jsonPath("$.repos[0].observedAt").value("2026-11-20T13:00:00Z"))
                .andExpect(jsonPath("$.repos[0].depCount").value(6))
                .andExpect(jsonPath("$.repos[0].appId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.repos[0].version").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.repos[0].format").value("Inventory JSON"))
                // ended in the past, ends today, ended without a date; never 5.0 (future), 6.0 (none announced), left-pad (unmatched) or 2.2 (older snapshot)
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[*].installedVersion").value(org.hamcrest.Matchers.contains("1.11.29", "3.2.25", "4.2.1")))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[*].cycle.name").value(org.hamcrest.Matchers.contains("1.11", "3.2", "4.2")))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[*].eol.css").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ended"))))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[2].eol.text").value("Ends today"))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[1].name").value("django"))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[1].purl").value("pkg:pypi/django@3.2.25"))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[1].ecosystem").value("pypi"))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[1].match.product").value("django"))
                .andExpect(jsonPath("$.repos[0].outdatedDependencies[1].cycle.eolFrom").value("2024-04-01"))
                .andExpect(jsonPath("$.repos[1].repoName").value("Beta"))
                .andExpect(jsonPath("$.repos[1].snapshotId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.repos[1].observedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.repos[1].depCount").value(0))
                .andExpect(jsonPath("$.repos[1].appId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.repos[1].format").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.repos[1].outdatedDependencies", hasSize(0)));
    }

    /** Supplemental (passed on its first run, after the read model existed): identities, newest version only, nulls, read-only. */
    @Test
    void branchesStayDistinctByIdWithNullsAndTheNewestVersionAndReadingChangesNothing() throws Exception {
        catalogue();
        imports.importJson("[{\"name\":\"Alpha\",\"url\":\"https://x.test/alpha\",\"branch\":\"main\",\"version\":\"1.0\",\"dependencies\":[]},"
                + "{\"name\":\"Alpha\",\"url\":\"https://x.test/alpha\",\"branch\":\"release/1\",\"dependencies\":[" + dep("django", "3.2.25", "pkg:pypi/django") + "]},"
                + "{\"name\":\"Alpha\",\"url\":\"https://x.test/alpha-fork\",\"branch\":\"main\",\"dependencies\":[]}]", "test");
        imports.importJson("[{\"name\":\"Alpha\",\"url\":\"https://x.test/alpha\",\"branch\":\"main\",\"version\":\"2.0\",\"dependencies\":[]}]", "test");
        // two different SBOMs with no metadata at all: two branches, never merged on their NULLs
        var none = new SbomInventory.Meta(null, null, null, null, null);
        imports.importSbom("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[{\"type\":\"library\",\"name\":\"django\",\"version\":\"4.2.1\",\"purl\":\"pkg:pypi/django@4.2.1\"}]}", "a.json", none, "sbom a.json");
        imports.importSbom("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[]}", "b.json", none, "sbom b.json");
        java.util.function.Supplier<List<Integer>> counts = () -> java.util.stream.Stream.of("repo_branch", "snapshot", "dependency", "import_run")
                .map(t -> db.sql("SELECT count(*) FROM " + t).query(Integer.class).single()).toList();
        List<Integer> before = counts.get();

        String body = mvc.perform(get("/api/repos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.repos", hasSize(5)))
                .andExpect(jsonPath("$.repos[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(
                        db.sql("SELECT id FROM repo_branch").query(Long.class).list().stream().map(Long::intValue).toArray())))
                .andExpect(jsonPath("$.repos[?(@.branch == 'main' && @.repoUrl == 'https://x.test/alpha')].version").value(org.hamcrest.Matchers.contains("2.0")))
                .andExpect(jsonPath("$.repos[?(@.branch == 'release/1')].outdatedDependencies[*].installedVersion").value(org.hamcrest.Matchers.contains("3.2.25")))
                .andExpect(jsonPath("$.repos[?(@.repoUrl == 'https://x.test/alpha-fork')].outdatedDependencies[*]", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        var anonymous = com.jayway.jsonpath.JsonPath.<List<java.util.Map<String, Object>>>read(body, "$.repos[?(@.repoUrl == null)]");
        assertThat(anonymous).hasSize(2);
        for (var r : anonymous) {
            assertThat(java.util.Arrays.asList(r.get("repoName"), r.get("branch"), r.get("provider"), r.get("appId"), r.get("version"))).containsOnlyNulls();
            assertThat(r.get("format")).isEqualTo("CycloneDX 1.6 JSON");
        }
        assertThat(anonymous).extracting(r -> r.get("depCount")).containsExactlyInAnyOrder(1, 0);
        assertThat(body).doesNotContain("—", "import_key", "payload");
        assertThat(counts.get()).isEqualTo(before);
    }
}
