package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Final-acceptance regressions: source/SBOM columns with historical provenance, and CSP-compatible templates. */
class AcceptanceRepairTest extends WebTestBase {

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("style-src 'self'")))
                .andReturn().getResponse().getContentAsString();
    }

    /** The dependency table only (the page facts above it also show the URLs). */
    static String depsTable(String html) {
        int start = html.indexOf("aria-label=\"Dependencies\"");
        return html.substring(start, html.indexOf("</table>", start));
    }

    @Test
    void templatesHaveNoInlineStylesOrHandlers() throws Exception {
        try (var files = Files.list(Path.of("src/main/resources/templates"))) {
            for (Path f : files.toList()) {
                String src = Files.readString(f);
                assertThat(src).as(f.toString()).doesNotContain("style=").doesNotContainPattern("\\son[a-z]+=\"");
                assertThat(src).as(f.toString()).doesNotContainPattern("<script(?![^>]*\\bsrc=)");
            }
        }
    }

    @Test
    void renderedPagesHaveNoInlineStyles() throws Exception {
        seed();
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        for (String url : List.of("/", "/?start=&end=", "/imports", "/repos", "/repos/" + id, "/repos/" + id + "/history", "/products/react?cycle=18")) {
            assertThat(page(url)).as(url).doesNotContain("style=");
        }
    }

    @Test
    void branchDependencyRowsCarrySourceAndSbomLinksAfterThePackageLink() throws Exception {
        seed();
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        db.sql("UPDATE repo_branch SET sbom_url = 'https://artifactory.example.com/alpha/main.cdx.json' WHERE id = ?").param(id).update();

        String table = depsTable(page("/repos/" + id));

        assertThat(table).contains("<th scope=\"col\">Source control</th>", "<th scope=\"col\">SBOM</th>");
        assertThat(table).contains("<td class=\"col-source\"><a href=\"https://gitlab.com/acme/alpha\"");
        assertThat(table).contains("<td class=\"col-sbom\"><a href=\"https://artifactory.example.com/alpha/main.cdx.json\"");
        // each row's first link is still the package link (to the cycle, or to the unknown-package detail)
        String reactRow = table.substring(table.indexOf("data-href=\"/products/react?cycle=18\""));
        assertThat(reactRow.indexOf("href=\"/products/react?cycle=18\">")).isLessThan(reactRow.indexOf("href=\"https://gitlab.com/acme/alpha\""));
    }

    @Test
    void historicalTableShowsTheSnapshotsOwnSbomNotTheBranchesCurrentOne() throws Exception {
        seed();
        long id = branchId("https://gitlab.com/acme/alpha", "main");
        long first = db.sql("SELECT id FROM snapshot WHERE branch_id = ?").param(id).query(Long.class).single();
        db.sql("UPDATE snapshot SET sbom_url = 'https://artifactory.example.com/alpha/old.cdx.json' WHERE id = ?").param(first).update();
        db.sql("UPDATE repo_branch SET sbom_url = 'https://artifactory.example.com/alpha/new.cdx.json' WHERE id = ?").param(id).update();

        String table = depsTable(page("/repos/" + id + "/history?snapshot=" + first));

        assertThat(table).contains("<td class=\"col-sbom\"><a href=\"https://artifactory.example.com/alpha/old.cdx.json\"")
                .doesNotContain("new.cdx.json");
        assertThat(depsTable(page("/repos/" + id))).contains("new.cdx.json").doesNotContain("old.cdx.json");
    }

    @Test
    void bundledSampleSbomIsShownAsTextNotALink() throws Exception {
        mvc.perform(post("/imports/demo").param("name", "demo-registry.csv").with(csrf())).andExpect(status().isSeeOther());
        long id = db.sql("SELECT id FROM repo_branch WHERE branch = 'develop'").query(Long.class).single();

        String table = depsTable(page("/repos/" + id));

        assertThat(table).contains("<td class=\"col-sbom\"><code>classpath:samples/ledger-service-develop.cdx.json</code>")
                .doesNotContain("href=\"classpath:");
    }
}
