package dev.eolledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DetailPagesTest extends WebTestBase {

    @BeforeEach
    void data() {
        seed();
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void cycleDetailShowsEolSourceInstalledVersionsAndBranchRows() throws Exception {
        long main = branchId("https://gitlab.com/acme/alpha", "main");
        long rel = branchId("https://gitlab.com/acme/alpha", "release/1.x");
        String html = page("/products/react?cycle=18");
        assertThat(html).contains("2025-01-01", "https://endoflife.date/react", "18.2.0", "18.3.0", "release/1.x")
                .contains("href=\"/repos/" + main + "\"", "href=\"/repos/" + rel + "\"")
                .contains("Installed version").contains("Show all versions");
    }

    @Test
    void productPageListsAllCyclesWithLatestPatchAndLinksBack() throws Exception {
        String html = page("/products/python");
        assertThat(html).contains("href=\"/products/python?cycle=3.10\"", "href=\"/products/python?cycle=2.7\"")
                .contains("Not published");
        assertThat(page("/products/react")).contains("18.3.1");
    }

    @Test
    void unknownProductOrCycleIs404() throws Exception {
        mvc.perform(get("/products/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/products/react?cycle=99")).andExpect(status().isNotFound());
        mvc.perform(get("/repos/999999")).andExpect(status().isNotFound());
    }

    @Test
    void repositoryListShowsEachBranchSeparately() throws Exception {
        String html = page("/repos");
        assertThat(html).contains("release/1.x", "https://dev.azure.com/acme/P/_git/beta", "azure-devops");
        assertThat(html).contains("href=\"/repos/" + branchId("https://gitlab.com/acme/alpha", "release/1.x") + "\"");
    }

    @Test
    void branchPageShowsDependencyTableWithEvidenceAndLinks() throws Exception {
        long main = branchId("https://gitlab.com/acme/alpha", "main");
        String html = page("/repos/" + main);
        assertThat(html).contains("https://gitlab.com/acme/alpha", "main", "pkg:npm/react@18.2.0", "npm")
                .contains("href=\"/products/react?cycle=18\"")
                .contains("href=\"https://gitlab.com/acme/alpha\"")
                .contains("is an endoflife.date identifier of react");
        // the unmatched generic "core" package gets its own detail page, not a dead row
        long coreId = db.sql("SELECT d.id FROM dependency d JOIN snapshot s ON s.id = d.snapshot_id WHERE s.branch_id = ? AND d.name = 'core'")
                .param(main).query(Long.class).single();
        assertThat(html).contains("href=\"/repos/" + main + "/deps/" + coreId + "\"");
        assertThat(page("/repos/" + main + "/deps/" + coreId)).contains("No catalogue identifier or mapping for pkg:maven/com.example/core")
                .contains("eol.mappings");
    }

    @Test
    void branchesDoNotShareSnapshots() throws Exception {
        String rel = page("/repos/" + branchId("https://gitlab.com/acme/alpha", "release/1.x"));
        assertThat(rel).contains("pkg:npm/react@18.3.0").doesNotContain("pkg:npm/react@18.2.0");
    }

    @Test
    void unsafeStoredRepoUrlIsNotRenderedAsLink() throws Exception {
        db.sql("UPDATE repo_branch SET repo_url = 'javascript:alert(1)' WHERE branch = 'main' AND repo_url LIKE '%beta'").update();
        long id = db.sql("SELECT id FROM repo_branch WHERE repo_url = 'javascript:alert(1)'").query(Long.class).single();
        assertThat(page("/repos/" + id)).doesNotContain("href=\"javascript:");
    }
}
