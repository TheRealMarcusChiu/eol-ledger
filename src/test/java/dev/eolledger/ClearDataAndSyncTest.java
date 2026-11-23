package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Clearing all imported data, and the dashboard after a catalogue sync with no code repos imported. */
// a configured product list must not narrow the public sync: it always fetches the whole catalogue
@TestPropertySource(properties = "eol.catalog.products=python")
class ClearDataAndSyncTest extends WebTestBase {

    int count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    static final java.util.List<String> TABLES = java.util.List.of("dependency", "snapshot_inventory", "snapshot", "repo_branch", "import_run",
            "product_identifier", "release_cycle", "product", "sync_status");

    java.util.Map<String, Integer> counts() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (String t : TABLES) m.put(t, count(t));
        return m;
    }

    @Test
    void clearWithoutConfirmationCsrfOrPostDeletesNothing() throws Exception {
        seed();
        var before = counts();
        assertThat(before.values()).allMatch(n -> n > 0);

        mvc.perform(post("/imports/clear").with(csrf())).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/imports"))
                .andExpect(flash().attribute("warning", org.hamcrest.Matchers.containsString("Nothing was cleared")));
        mvc.perform(post("/imports/clear").param("confirm", "false").with(csrf())).andExpect(status().isSeeOther())
                .andExpect(flash().attribute("warning", org.hamcrest.Matchers.containsString("Nothing was cleared")));
        mvc.perform(post("/imports/clear").param("confirm", "true")).andExpect(status().isForbidden());
        mvc.perform(get("/imports/clear").param("confirm", "true"));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void confirmedClearEmptiesEveryDataTableRepeatablyThenReimportAndTrackedModeWork() throws Exception {
        seed();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/imports/clear").param("confirm", "true").with(csrf())).andExpect(status().isSeeOther())
                    .andExpect(redirectedUrl("/imports")).andExpect(flash().attribute("message", "All imported data and the synced catalogue were cleared."));
            assertThat(counts().values()).allMatch(n -> n == 0);
        }
        assertThat(catalog.status()).isNull();
        assertThat(page("/imports")).contains("Never synced", "No imports yet.");
        assertThat(page("/")).contains("The endoflife.date catalogue has not been synced yet.").doesNotContain("No code repos imported yet");

        // ids keep counting (no sequence reset) and the schema still takes the same data
        seed();
        assertThat(count("repo_branch")).isEqualTo(3);
        String html = page("/");
        // repos present again: every catalogue cycle by default, used-only on an explicit ?unused=false
        assertThat(DashboardTest.shown(html)).hasSize(7);
        assertThat(html).doesNotContain("No code repos imported yet").contains("data-row=\"react 19\" data-repos=\"0\"");
        assertThat(page("/?unused=true")).contains("data-legacy=\"false\"", "data-row=\"react 19\" data-repos=\"0\"");
        assertThat(DashboardTest.shown(page("/?unused=false"))).hasSize(5);
        assertThat(page("/?start=2026-11-20&end=2026-11-20")).contains("data-row=\"python 3.10\"");
    }

    @Test
    void failedClearRollsBackEveryTable() throws Exception {
        seed();
        var before = counts();
        // test-only blocker on in-memory H2: DELETE FROM product (late in the order) fails after the earlier tables were emptied
        db.sql("CREATE TABLE clear_blocker (p VARCHAR(200) REFERENCES product(name))").update();
        try {
            db.sql("INSERT INTO clear_blocker VALUES ('react')").update();
            mvc.perform(post("/imports/clear").param("confirm", "true").with(csrf())).andExpect(status().isSeeOther())
                    .andExpect(flash().attribute("warning", org.hamcrest.Matchers.containsString("nothing was cleared")));
            assertThat(counts()).isEqualTo(before);
        } finally {
            db.sql("DROP TABLE clear_blocker").update();
        }
    }

    @Test
    void clearWaitsForAnInFlightImportButRefusesARunningCatalogueSync() throws Exception {
        seed();
        Thread clearer = new Thread(imports::clearAll);
        // holding the monitor stands in for importJson running
        synchronized (imports) {
            clearer.start();
            while (clearer.getState() != Thread.State.BLOCKED) Thread.sleep(5);
            assertThat(count("repo_branch")).isEqualTo(3);
            assertThat(count("product")).isEqualTo(3);
        }
        clearer.join(10_000);
        assertThat(counts().values()).allMatch(n -> n == 0);
        // a running sync (its work gate held) is never waited for: the clear fails at once and deletes nothing
        seed();
        assertThat(catalog.beginWork()).isTrue();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(imports::clearAll).isInstanceOf(ImportService.ImportFailedException.class);
            assertThat(count("product")).isEqualTo(3);
        } finally {
            catalog.endWork();
        }
    }

    @Test
    void clearFormIsTheLastThingInMainWithWarningRequiredCheckboxAndCsrf() throws Exception {
        String html = page("/imports");
        String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
        String form = main.substring(main.lastIndexOf("<section"));
        assertThat(main.indexOf("Import history")).isLessThan(main.lastIndexOf("<section"));
        assertThat(form).contains("<h2 id=\"clear-h\">","method=\"post\"", "action=\"/imports/clear\"", "name=\"_csrf\"",
                "<input type=\"checkbox\" id=\"clear-confirm\" name=\"confirm\" value=\"true\" required",
                ">Clear all imported data</button>",
                "code repos, dependency inventories and snapshots, import history, and the synced endoflife.date catalogue and its sync status",
                "App configuration and appearance preferences are kept");
        // nothing but closing tags after the form
        assertThat(form.substring(form.indexOf("</form>") + 7).replaceAll("</section>|\\s", "")).isEmpty();
    }

    @Test
    void syncOnlyDashboardListsEveryCatalogueCycleWithHonestZeroCounts() throws Exception {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE));
        mvc.perform(post("/catalog/sync").with(csrf())).andExpect(status().isSeeOther());
        // the sync itself worked despite products=python: one request (only /products/full has a reply), all 7 cycles stored, status OK, no repos
        assertThat(TestSupport.AUTH_HEADERS).hasSize(1);
        assertThat(db.sql("SELECT product || ' ' || name FROM release_cycle").query(String.class).list()).containsExactlyInAnyOrder(
                "react 19", "react 18", "python 3.10", "python 3.9", "python 3.1", "python 2.7", "django 4.2");
        assertThat(db.sql("SELECT name FROM product").query(String.class).list()).containsExactlyInAnyOrder("react", "python", "django");
        assertThat(catalog.status().ok()).isTrue();
        assertThat(count("repo_branch")).isZero();
        assertThat(page("/imports")).contains("whole catalogue").doesNotContain("configured products");

        String html = page("/");
        assertThat(DashboardTest.rows(html)).hasSize(7);
        assertThat(DashboardTest.shown(html)).hasSize(7);
        assertThat(html).contains("data-row=\"react 19\" data-repos=\"0\" data-branches=\"0\"",
                "data-row=\"python 3.9\" data-repos=\"0\" data-branches=\"0\"", "data-legacy=\"false\"", "No code repos imported yet")
                .doesNotContain("to list only the versions in use");
        // an explicit used-only link is honoured even with no branches: no rows, still the same notice
        String usedOnly = page("/?unused=false");
        assertThat(DashboardTest.rows(usedOnly)).isEmpty();
        assertThat(usedOnly).contains("data-legacy=\"true\"", "No code repos imported yet", "<tr data-empty>");
    }
}
