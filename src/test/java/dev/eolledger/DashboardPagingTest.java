package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Server-side (SQL) paging, filtering and sorting of the dashboard over a catalogue larger than one page. */
class DashboardPagingTest extends WebTestBase {

    static final ObjectMapper JSON = new ObjectMapper();

    /** WebTestBase's 7 cycles plus 25 unused Node.js cycles "1".."25": 32 rows, two pages of 20. */
    @BeforeEach
    void data() {
        StringBuilder node = new StringBuilder("{\"name\":\"nodejs\",\"label\":\"Node.js\",\"identifiers\":[{\"type\":\"purl\",\"id\":\"pkg:generic/node\"}],\"releases\":[");
        for (int i = 1; i <= 25; i++) {
            node.append(i == 1 ? "" : ",").append("{\"name\":\"").append(i).append("\",\"isEol\":true,\"eolFrom\":\"20")
                    .append(String.format("%02d", i)).append("-01-01\"}");
        }
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE.replace("{\"result\":[", "{\"result\":[" + node + "]},")));
        catalog.sync();
        imports.importJson(jsonInventory(
                jsonEntry("Alpha", "https://gitlab.com/acme/alpha", "gitlab", "main",
                        "pkg:npm/react@18.2.0", "pkg:generic/python@3.10.5", "pkg:pypi/django@4.2.1", "pkg:maven/com.example/core@1.0.0"),
                jsonEntry("Beta", "https://dev.azure.com/acme/P/_git/beta", "azure-devops", "main", "pkg:pypi/django@4.2.3", "pkg:generic/python@3.1.4")),
                "test");
    }

    JsonNode api(String view) throws Exception {
        var req = get("/api/dashboard");
        if (view != null) req = req.param("view", view);
        return JSON.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    static List<String> keys(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.path("rows").forEach(r -> out.add(r.path("product").asText() + " " + r.path("cycle").asText()));
        return out;
    }

    @Test
    void rootRendersOnlyTheFirstTwentyRowsAndPointsAtTheApi() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(DashboardTest.rows(html)).hasSize(20);
        assertThat(html).contains("data-server=\"/api/dashboard\"", "data-total=\"32\"", "data-matching=\"32\"")
                .doesNotContain("hidden=\"hidden\" data-row", "nodejs 25\""); // EoL 2025 is past row 20 in EoL order
    }

    @Test
    void pagesComeFromSqlWithCountsAndClampedPages() throws Exception {
        JsonNode p1 = api(null);
        assertThat(p1.path("total").asInt()).isEqualTo(32);
        assertThat(p1.path("matching").asInt()).isEqualTo(32);
        assertThat(p1.path("pages").asInt()).isEqualTo(2);
        assertThat(keys(p1)).hasSize(20).startsWith("nodejs 1", "nodejs 2");
        JsonNode p2 = api("{\"v\":1,\"page\":2}");
        assertThat(keys(p2)).hasSize(12).doesNotContainAnyElementsOf(keys(p1)).endsWith("python 2.7", "react 19"); // unpublished dates last
        assertThat(keys(api("{\"v\":1,\"size\":50}"))).hasSize(32);
        // out of range, malformed, unknown version or size: defaults or the last page, never an error
        assertThat(api("{\"v\":1,\"page\":999}").path("page").asInt()).isEqualTo(2);
        for (String bad : List.of("not json", "{\"v\":2,\"page\":2}", "{\"v\":1,\"page\":-4,\"size\":7.5}", "[]", "{\"v\":1,\"sort\":{\"key\":\"x;DROP\",\"dir\":\"up\"}}")) {
            JsonNode d = api(bad);
            assertThat(keys(d)).as(bad).isEqualTo(keys(p1));
            assertThat(d.path("size").asInt()).isEqualTo(20);
        }
    }

    @Test
    void anyWholePageSizeFromOneToAThousandIsQueriedAsItsSqlLimit() throws Exception {
        List<String> all = keys(api("{\"v\":1,\"size\":1000}"));
        assertThat(all).hasSize(32);
        for (int size : new int[]{1, 7, 13, 37}) {
            JsonNode p = api("{\"v\":1,\"size\":" + size + ",\"page\":2}");
            assertThat(p.path("size").asInt()).as("size " + size).isEqualTo(size);
            assertThat(p.path("pages").asInt()).isEqualTo((32 + size - 1) / size);
            int page = Math.min(2, p.path("pages").asInt());
            assertThat(keys(p)).isEqualTo(all.subList((page - 1) * size, Math.min(32, page * size)));
        }
        // an integral JSON number in another spelling is the same size, as in the browser
        assertThat(api("{\"v\":1,\"size\":7.0}").path("size").asInt()).isEqualTo(7);
        // never truncated, never unbounded: anything else is the default 20
        for (String bad : List.of("0", "-7", "7.5", "1001", "1e400", "\"37\"", "null", "true", "99999999999")) {
            JsonNode p = api("{\"v\":1,\"size\":" + bad + "}");
            assertThat(p.path("size").asInt()).as(bad).isEqualTo(20);
            assertThat(keys(p)).as(bad).hasSize(20);
        }
    }

    @Test
    void sortIsGlobalNaturalAndBlanksLastBothWays() throws Exception {
        List<String> asc = keys(api("{\"v\":1,\"sort\":{\"key\":\"cycle\",\"dir\":\"asc\"},\"size\":50}"));
        assertThat(asc.indexOf("nodejs 9")).isLessThan(asc.indexOf("nodejs 10"));
        assertThat(asc.indexOf("python 3.9")).isLessThan(asc.indexOf("python 3.10"));
        List<String> desc = keys(api("{\"v\":1,\"sort\":{\"key\":\"eol\",\"dir\":\"desc\"}}"));
        assertThat(desc.get(0)).isEqualTo("django 4.2");
        List<String> descAll = keys(api("{\"v\":1,\"sort\":{\"key\":\"eol\",\"dir\":\"desc\"},\"size\":50}"));
        assertThat(descAll.subList(descAll.size() - 2, descAll.size())).containsExactlyInAnyOrder("python 2.7", "react 19");
        List<String> repos = keys(api("{\"v\":1,\"sort\":{\"key\":\"repos\",\"dir\":\"desc\"}}"));
        assertThat(repos.get(0)).isEqualTo("django 4.2"); // two repos
    }

    @Test
    void filtersApplyToTheWholeResultBeforePaging() throws Exception {
        JsonNode used = api("{\"v\":1,\"filters\":{\"repos\":{\"mode\":\"range\",\"startText\":\"1\"}}}");
        assertThat(used.path("matching").asInt()).isEqualTo(4);
        assertThat(used.path("total").asInt()).isEqualTo(32);
        assertThat(keys(api("{\"v\":1,\"filters\":{\"dependency\":{\"mode\":\"text\",\"op\":\"contains\",\"text\":\"REACT\"}}}")))
                .containsExactlyInAnyOrder("react 18", "react 19");
        assertThat(keys(api("{\"v\":1,\"filters\":{\"dependency\":{\"mode\":\"text\",\"op\":\"fuzzy\",\"text\":\"pyhton\"}}}")))
                .containsExactlyInAnyOrder("python 3.10", "python 3.9", "python 3.1", "python 2.7");
        assertThat(api("{\"v\":1,\"filters\":{\"cycle\":{\"mode\":\"text\",\"op\":\"starts-with\",\"text\":\"node.js 1\"}}}").path("matching").asInt()).isEqualTo(11);
        assertThat(api("{\"v\":1,\"filters\":{\"context\":{\"mode\":\"text\",\"op\":\"is-blank\"}}}").path("matching").asInt()).isEqualTo(32);
        assertThat(api("{\"v\":1,\"filters\":{\"ecosystem\":{\"selected\":[\"npm\",\"pypi\"]}}}").path("matching").asInt()).isEqualTo(2);
        assertThat(api("{\"v\":1,\"filters\":{\"ecosystem\":{\"excluded\":[\"Unknown\"]}}}").path("matching").asInt()).isEqualTo(4);
        assertThat(api("{\"v\":1,\"filters\":{\"ecosystem\":{\"selected\":[]}}}").path("matching").asInt()).isZero();
        assertThat(api("{\"v\":1,\"filters\":{\"eol\":{\"mode\":\"range\",\"startText\":\"2025-01-01\",\"endText\":\"2026-12-31\"}}}").path("matching").asInt()).isEqualTo(4);
        // an invalid range draft applies nothing (or its carried valid bounds), like the browser
        assertThat(api("{\"v\":1,\"filters\":{\"eol\":{\"mode\":\"range\",\"startText\":\"2026-02-31\"}}}").path("matching").asInt()).isEqualTo(32);
        assertThat(api("{\"v\":1,\"filters\":{\"version\":{\"mode\":\"range\",\"startText\":\"3.9\",\"endText\":\"4.2.2\"}}}").path("matching").asInt()).isEqualTo(2);
        // as on the shipped page, a not-installed row's version value is its cell text, not blank
        assertThat(api("{\"v\":1,\"filters\":{\"version\":{\"selected\":[\"Not installed\"]}}}").path("matching").asInt()).isEqualTo(28);
        assertThat(api("{\"v\":1,\"filters\":{\"version\":{\"mode\":\"text\",\"op\":\"is-blank\"}}}").path("matching").asInt()).isZero();
        assertThat(api("{\"v\":1,\"filters\":{\"eol\":{\"selected\":[\"\"]}}}").path("matching").asInt()).isEqualTo(2);
    }

    @Test
    void hostileStateIsOnlyEverABoundValue() throws Exception {
        String text = "x' OR '1'='1'; DROP TABLE product; --";
        JsonNode r = api(JSON.writeValueAsString(java.util.Map.of("v", 1, "filters", java.util.Map.of("purl", java.util.Map.of("mode", "text", "op", "contains", "text", text),
                "nope); DELETE FROM product; --", java.util.Map.of("selected", List.of("a"))))));
        assertThat(r.path("matching").asInt()).isZero();
        assertThat(api("{\"v\":1,\"filters\":{\"purl\":{\"mode\":\"text\",\"op\":\"contains\",\"text\":\"%\"}}}").path("matching").asInt()).isZero();
        assertThat(api("{\"v\":1,\"filters\":{\"purl\":{\"mode\":\"text\",\"op\":\"contains\",\"text\":\"_\"}}}").path("matching").asInt()).isZero();
        assertThat(db.sql("SELECT count(*) FROM product").query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void valuesListIsTheWholeColumnDomain() throws Exception {
        JsonNode v = JSON.readTree(mvc.perform(get("/api/dashboard/values").param("column", "ecosystem")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> values = new ArrayList<>();
        v.forEach(n -> values.add(n.path("value").asText()));
        assertThat(values).containsExactly("generic", "npm", "pypi", "Unknown");
        mvc.perform(get("/api/dashboard/values").param("column", "nope")).andExpect(status().isBadRequest());
    }

    @Test
    void matchingAndEvidenceFollowNewImports() throws Exception {
        assertThat(api("{\"v\":1,\"filters\":{\"repos\":{\"mode\":\"range\",\"startText\":\"1\"}}}").path("matching").asInt()).isEqualTo(4);
        imports.importJson(jsonInventory(jsonEntry("Gamma", "https://gitlab.com/acme/gamma", "gitlab", "main", "pkg:generic/node@25.1.0")), "test");
        JsonNode r = api("{\"v\":1,\"filters\":{\"cycle\":{\"mode\":\"text\",\"op\":\"exact\",\"text\":\"Node.js 25\"}}}");
        assertThat(r.path("rows").get(0).path("repos").asInt()).isEqualTo(1);
        assertThat(r.path("rows").get(0).path("evidence").get(0).path("text").asText()).isEqualTo("Gamma / main: Details");
        assertThat(r.path("rows").get(0).path("version").asText()).isEqualTo("25.1.0");
    }

    @org.springframework.beans.factory.annotation.Autowired DashboardQuery query;

    @Test
    void afterASyncReadersWaitForTheRunningRebuildWhileDuringASyncTheyDoNot() throws Exception {
        String used = "{\"v\":1,\"filters\":{\"repos\":{\"mode\":\"range\",\"startText\":\"1\"}}}";
        assertThat(query.page(used, false).matching()).isEqualTo(4);
        imports.importJson(jsonInventory(jsonEntry("Gamma", "https://gitlab.com/acme/gamma", "gitlab", "main", "pkg:generic/node@25.1.0")), "test");
        query.rebuilding.lock(); // another request is rebuilding the model
        Thread reader;
        java.util.concurrent.atomic.AtomicReference<DashboardQuery.Page> got = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            // while a sync runs, the previous model answers at once (the page stays responsive)
            assertThat(catalog.beginWork()).isTrue();
            try {
                assertThat(query.page(used, false).matching()).isEqualTo(4);
            } finally {
                catalog.endWork();
            }
            // with no sync running, a reader must not get the stale model: it waits for the rebuild and rechecks
            reader = new Thread(() -> got.set(query.page(used, false)));
            reader.start();
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (reader.getState() != Thread.State.WAITING && reader.isAlive()) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.onSpinWait();
            }
            assertThat(got.get()).as("returned the stale model while a rebuild was running").isNull();
        } finally {
            query.rebuilding.unlock();
        }
        reader.join(10_000);
        assertThat(got.get().matching()).isEqualTo(5);
    }

    @Test
    void defaultAndEolRangePagesUseTheProjectionIndexes() {
        String plan = db.sql("EXPLAIN SELECT product, cycle FROM dash_row ORDER BY ord LIMIT 20").query(String.class).single();
        assertThat(plan).containsIgnoringCase("DASH_ROW_ORD");
        String range = db.sql("EXPLAIN SELECT product, cycle FROM dash_row WHERE eol_from >= DATE '2025-01-01' ORDER BY eol_from, ord LIMIT 20")
                .query(String.class).single();
        assertThat(range).containsIgnoringCase("DASH_ROW_EOL");
        String repos = db.sql("EXPLAIN SELECT product, cycle FROM dash_row WHERE c_repos >= 1 ORDER BY c_repos, ord LIMIT 20").query(String.class).single();
        assertThat(repos).containsIgnoringCase("DASH_ROW_REPOS");
        String contains = db.sql("EXPLAIN SELECT count(*) FROM dash_row WHERE LOWER(c_purl) LIKE '%react%' ESCAPE '!'").query(String.class).single();
        System.out.println("H2 EXPLAIN default page:\n" + plan + "\nH2 EXPLAIN eol range:\n" + range + "\nH2 EXPLAIN repos range:\n" + repos
                + "\nH2 EXPLAIN contains (residual scan):\n" + contains);
    }
}
