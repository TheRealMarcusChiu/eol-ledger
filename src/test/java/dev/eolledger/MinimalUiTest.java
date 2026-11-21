package dev.eolledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Minimal UI: long help sits in closed native disclosures, while controls, status and data stay outside them. */
class MinimalUiTest extends WebTestBase {

    @BeforeEach
    void data() {
        seed();
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** The page as first shown: every closed disclosure's body removed, its summary kept. */
    static String visible(String html) {
        return html.replaceAll("(?s)(<details[^>]*>\\s*<summary>[^<]*</summary>).*?</details>", "$1");
    }

    @Test
    void helpIsClosedByDefaultOnEveryPage() throws Exception {
        long main = branchId("https://gitlab.com/acme/alpha", "main");
        long coreId = db.sql("SELECT d.id FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE s.branch_id = ? AND d.name = 'core'")
                .param(main).query(Long.class).single();
        for (String url : List.of("/", "/imports", "/repos", "/repos/" + main, "/repos/" + main + "/history",
                "/repos/" + main + "/deps/" + coreId, "/dependencies/react", "/dependencies/react?version=18")) {
            String html = page(url);
            assertThat(html).as(url).doesNotContainPattern("<details[^>]*\\sopen").doesNotContain("<details>");
            assertThat(html.split("<details", -1).length).as(url).isEqualTo(html.split("<summary>", -1).length);
        }
        assertThat(visible(page("/repos/" + main + "/deps/" + coreId))).contains("No catalogue identifier or mapping for pkg:maven/com.example/core")
                .contains("How to map this package").doesNotContain("eol.mappings");
    }

    @Test
    void dashboardHasNoTopControlsOnlyTheTable() throws Exception {
        String html = page("/");
        // filtering and sorting moved into the table headers (app.js); the old form, its help and the update button were removed
        assertThat(html).doesNotContain("<form", ">Apply</button>", "Reset filters", "Update EoL data", "<summary>Help</summary>", "Both dates are inclusive");
        assertThat(visible(html)).contains("<h1>EoL Dependencies</h1>", "<table id=\"dependencies-table\"");
    }

    @Test
    void importsActionsAndStatusAreVisibleAndFormatDocsArePreserved() throws Exception {
        String html = page("/imports");
        assertThat(visible(html)).contains(">Import file</button>", ">Download demo JSON</a>", ">Sync catalogue now</button>",
                        "href=\"/imports/samples/demo-dependency-inventory.json\"", "Last success", "Import history", "<summary>Format help</summary>")
                .doesNotContain("repo_name,repo_url,provider,branch,sbom_url");
        // CSV import, the demo registry and the SBOM refresh were removed
        assertThat(html).doesNotContain("Import demo registry", "demo-registry", "Refresh all now", "branches registered", "Demo data",
                "Registry CSV", "text/csv");
        assertThat(html).contains("enctype=\"multipart/form-data\"", "name=\"_csrf\"", "aria-describedby=\"json-h\"", "id=\"json-h\"",
                "parentCoordinate", "never fetched");
        assertThat(html.split("demo-dependency-inventory.json\"", -1)).hasSize(2);
    }

    @Test
    void navHeadingsAndBreadcrumbsUseEolDependenciesAndCodeRepos() throws Exception {
        long main = branchId("https://gitlab.com/acme/alpha", "main");
        long coreId = db.sql("SELECT d.id FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE s.branch_id = ? AND d.name = 'core'")
                .param(main).query(Long.class).single();
        for (String url : List.of("/", "/imports", "/repos", "/repos/" + main, "/repos/" + main + "/history",
                "/repos/" + main + "/deps/" + coreId, "/dependencies/react", "/dependencies/react?version=18")) {
            String html = page(url);
            assertThat(html).as(url).contains(">EoL Dependencies</a>", ">Code repos</a>")
                    .doesNotContain(">Cycles</a>", "Repositories", "repositories", ">Release cycles</", "EoL Packages");
        }
        assertThat(page("/")).contains("<title>EoL Dependencies · EoL Ledger</title>", "<h1>EoL Dependencies</h1>", ">Code repos</th>");
        assertThat(page("/repos")).contains("<title>Code repos · EoL Ledger</title>", "<h1>Code repos</h1>", "2</span> code repos,",
                ">Code repo</th>", "aria-label=\"Code repo branches\"");
        for (String url : List.of("/repos/" + main, "/repos/" + main + "/history", "/repos/" + main + "/deps/" + coreId)) {
            assertThat(page(url)).as(url).contains("<a href=\"/repos\">Code repos</a>");
        }
        assertThat(page("/repos/" + main)).contains("<dt>Code repo</dt>");
        assertThat(page("/dependencies/react")).contains("<a href=\"/\">EoL Dependencies</a> /", "class=\"num\">Code repos</th>");
        assertThat(page("/dependencies/react?version=18")).contains("<a href=\"/\">EoL Dependencies</a> /", "<dt>Code repos</dt>", " code repos,",
                ">Code repo</th>");
        // the product page's own table is still a list of actual release cycles
        assertThat(page("/dependencies/react")).contains("aria-label=\"Release cycles\"", ">Cycle</th>");
    }

    @Test
    void everyPageAppliesTheSavedAppearanceBeforeItsStylesheet() throws Exception {
        for (String url : List.of("/", "/imports", "/repos", "/dependencies/react", "/dependencies/react?version=18")) {
            String html = page(url);
            assertThat(html.indexOf("<script src=\"/theme.js\"></script>")).as(url).isPositive().isLessThan(html.indexOf("/app.css"));
        }
        String js = mvc.perform(get("/theme.js")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("eol-ledger.appearance.v1", "data-mode", "data-theme", "'dark', 'light', 'system'");
    }

    @Test
    void oldProductLinksRedirectPermanentlyToDependencyPages() throws Exception {
        mvc.perform(get("/products/react").param("cycle", "18")).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/dependencies/react?version=18"));
        mvc.perform(get("/products/react")).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/dependencies/react"));
        assertThat(page("/dependencies/react?version=18")).contains("<h1>React 18</h1>");
    }

    @Test
    void detailPagesKeepTheirNavigationActionsVisible() throws Exception {
        long main = branchId("https://gitlab.com/acme/alpha", "main");
        assertThat(visible(page("/repos/" + main))).contains(">View history</a>", "aria-label=\"Dependencies\"", "Source control");
        assertThat(visible(page("/dependencies/react?version=18"))).contains(">Show all versions</a>", "Installed versions", "aria-label=\"Affected code repo branches\"");
        assertThat(visible(page("/repos/" + main + "/history"))).contains("As of the end of", ">Show snapshot</button>", "today's endoflife.date data",
                "aria-label=\"Snapshot timeline\"");
    }
}
