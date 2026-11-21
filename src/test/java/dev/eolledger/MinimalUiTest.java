package dev.eolledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                "/repos/" + main + "/deps/" + coreId, "/products/react", "/products/react?cycle=18")) {
            String html = page(url);
            assertThat(html).as(url).doesNotContainPattern("<details[^>]*\\sopen").doesNotContain("<details>");
            assertThat(html.split("<details", -1).length).as(url).isEqualTo(html.split("<summary>", -1).length);
        }
        assertThat(visible(page("/repos/" + main + "/deps/" + coreId))).contains("No catalogue identifier or mapping for pkg:maven/com.example/core")
                .contains("How to map this package").doesNotContain("eol.mappings");
    }

    @Test
    void dashboardControlsAreVisibleAndHelpKeepsItsLinks() throws Exception {
        String html = page("/");
        assertThat(visible(html)).contains("id=\"start\" name=\"start\"", "id=\"end\" name=\"end\"", "id=\"sort\" name=\"sort\"",
                "id=\"unused\" name=\"unused\"", ">Apply</button>", ">Reset to today</a>", ">Update EoL data</button>", "<summary>Help</summary>")
                .doesNotContain("Both dates are inclusive");
        assertThat(html).contains("id=\"filter-help\"", "id=\"update-help\"", "Both dates are inclusive", "href=\"https://endoflife.date/docs/api/v1/\"");
    }

    @Test
    void importsActionsAndStatusAreVisibleAndFormatDocsArePreserved() throws Exception {
        String html = page("/imports");
        assertThat(visible(html)).contains(">Import file</button>", ">Import demo registry</button>", "value=\"demo-registry.csv\"",
                        ">Download demo JSON</a>", ">Refresh all now</button>", ">Sync catalogue now</button>",
                        "href=\"/imports/samples/demo-dependency-inventory.json\"", "branches registered", "Last success", "Import history",
                        "<summary>Format help</summary>")
                .doesNotContain("repo_name,repo_url,provider,branch,sbom_url");
        // removed from the UI only; the sample endpoints stay for compatibility
        assertThat(html).doesNotContain("Import demo update", "Download demo update CSV", "Download demo CSV",
                "demo-registry-update.csv", "href=\"/imports/samples/demo-registry.csv\"");
        assertThat(html).contains("enctype=\"multipart/form-data\"", "name=\"_csrf\"", "aria-describedby=\"csv-h json-h\"", "id=\"csv-h\"", "id=\"json-h\"",
                "repo_name,repo_url,provider,branch,sbom_url", "parentCoordinate", "never fetched");
        assertThat(html.split("demo-dependency-inventory.json\"", -1)).hasSize(2);
    }

    @Test
    void navHeadingsAndBreadcrumbsUseEolPackagesAndCodeRepos() throws Exception {
        long main = branchId("https://gitlab.com/acme/alpha", "main");
        long coreId = db.sql("SELECT d.id FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE s.branch_id = ? AND d.name = 'core'")
                .param(main).query(Long.class).single();
        for (String url : List.of("/", "/imports", "/repos", "/repos/" + main, "/repos/" + main + "/history",
                "/repos/" + main + "/deps/" + coreId, "/products/react", "/products/react?cycle=18")) {
            String html = page(url);
            assertThat(html).as(url).contains(">EoL Packages</a>", ">Code repos</a>")
                    .doesNotContain(">Cycles</a>", "Repositories", "repositories", ">Release cycles</");
        }
        assertThat(page("/")).contains("<title>EoL Packages · EoL Ledger</title>", "<h1>EoL Packages</h1>", ">Code repos</th>");
        assertThat(page("/repos")).contains("<title>Code repos · EoL Ledger</title>", "<h1>Code repos</h1>", "2</span> code repos,",
                ">Code repo</th>", "aria-label=\"Code repo branches\"");
        for (String url : List.of("/repos/" + main, "/repos/" + main + "/history", "/repos/" + main + "/deps/" + coreId)) {
            assertThat(page(url)).as(url).contains("<a href=\"/repos\">Code repos</a>");
        }
        assertThat(page("/repos/" + main)).contains("<dt>Code repo</dt>");
        assertThat(page("/products/react")).contains("<a href=\"/\">EoL Packages</a> /", "class=\"num\">Code repos</th>");
        assertThat(page("/products/react?cycle=18")).contains("<a href=\"/\">EoL Packages</a> /", "<dt>Code repos</dt>", " code repos,",
                ">Code repo</th>");
        // the product page's own table is still a list of actual release cycles
        assertThat(page("/products/react")).contains("aria-label=\"Release cycles\"", ">Cycle</th>");
    }

    @Test
    void detailPagesKeepTheirNavigationActionsVisible() throws Exception {
        long main = branchId("https://gitlab.com/acme/alpha", "main");
        assertThat(visible(page("/repos/" + main))).contains(">View history</a>", "aria-label=\"Dependencies\"", "Source control");
        assertThat(visible(page("/products/react?cycle=18"))).contains(">Show all versions</a>", "Installed versions", "aria-label=\"Affected code repo branches\"");
        assertThat(visible(page("/repos/" + main + "/history"))).contains("As of the end of", ">Show snapshot</button>", "today's endoflife.date data",
                "aria-label=\"Snapshot timeline\"");
    }
}
