package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImportsPageTest extends IntegrationTest {

    MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "registry.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    int count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void pageDocumentsHeaderAndHasCsrfProtectedForms() throws Exception {
        mvc.perform(get("/imports")).andExpect(status().isOk())
                .andExpect(content().string(containsString("repo_name,repo_url,provider,branch,sbom_url")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("enctype=\"multipart/form-data\"")))
                .andExpect(content().string(not(containsString("test-secret-token"))));
    }

    @Test
    void sampleCsvIsDownloadableAndUploadable() throws Exception {
        byte[] body = mvc.perform(get("/imports/samples/demo-registry.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text).startsWith(RegistryCsv.HEADER).contains("DEMO", "gitlab", "github", "azure-devops", "release/2.x");

        mvc.perform(multipart("/imports").file(csv(text)).with(csrf())).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/imports"));
        assertThat(count("repo_branch")).isEqualTo(4);
        assertThat(count("snapshot")).isEqualTo(4);
    }

    @Test
    void actionRowsWrapSoNarrowScreensDoNotOverflow() throws Exception {
        // the demo-data row holds two download links and two forms side by side; at 400px they must wrap
        String css = mvc.perform(get("/app.css")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(css).containsPattern("\\.actions \\{[^}]*flex-wrap: wrap");
    }

    @Test
    void unknownSampleNameIsNotFound() throws Exception {
        mvc.perform(get("/imports/samples/application.properties")).andExpect(status().isNotFound());
    }

    @Test
    void uploadWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(multipart("/imports").file(csv(ImportService.sampleCsv("demo-registry.csv")))).andExpect(status().isForbidden());
        assertThat(count("repo_branch")).isZero();
    }

    @Test
    void invalidUploadShowsAllErrorsAndKeepsExistingData() throws Exception {
        mvc.perform(multipart("/imports").file(csv(ImportService.sampleCsv("demo-registry.csv"))).with(csrf()));
        String bad = RegistryCsv.HEADER + "\nX,https://github.com/a/b,github,main,https://evil.example.net/x.json\nY,ftp://x,github,main,classpath:samples/x.json\n";

        mvc.perform(multipart("/imports").file(csv(bad)).with(csrf())).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("not in the SBOM host allowlist")))
                .andExpect(content().string(containsString("line 3: repo_url")))
                .andExpect(content().string(containsString("Nothing was changed")));
        assertThat(count("repo_branch")).isEqualTo(4);
    }

    @Test
    void emptyUploadIsHelpful400() throws Exception {
        mvc.perform(multipart("/imports").file(csv("")).with(csrf())).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Choose a CSV file")));
    }

    @Test
    void demoRegistryCanBeLoadedThenUpdatedToShowHistory() throws Exception {
        mvc.perform(post("/imports/demo").param("name", "demo-registry.csv").with(csrf())).andExpect(status().isSeeOther());
        CLOCK.now = CLOCK.now.plusSeconds(3600);
        mvc.perform(post("/imports/demo").param("name", "demo-registry-update.csv").with(csrf())).andExpect(status().isSeeOther());
        assertThat(count("snapshot")).isEqualTo(5);
        assertThat(count("repo_branch")).isEqualTo(4);
    }

    @Test
    void manualCatalogueSyncWorksWithSchedulersDisabled() throws Exception {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(
                "{\"result\":[{\"name\":\"react\",\"label\":\"React\",\"releases\":[{\"name\":\"18\",\"eolFrom\":\"2025-01-01\",\"isEol\":true}]}]}"));
        mvc.perform(post("/catalog/sync").with(csrf())).andExpect(status().isSeeOther());
        assertThat(count("release_cycle")).isEqualTo(1);
        mvc.perform(get("/imports")).andExpect(content().string(containsString("Synced 1 product(s)")));
    }

    @Test
    void failedCatalogueSyncIsShownOnImportsPage() throws Exception {
        mvc.perform(post("/catalog/sync").with(csrf())).andExpect(status().isSeeOther());
        mvc.perform(get("/imports")).andExpect(content().string(containsString("catalogue fetch failed")));
    }

    @Test
    void registryRefreshButtonRefetches() throws Exception {
        mvc.perform(post("/imports/demo").param("name", "demo-registry.csv").with(csrf()));
        mvc.perform(post("/imports/refresh").with(csrf())).andExpect(status().isSeeOther());
        assertThat(db.sql("SELECT message FROM import_run ORDER BY id DESC LIMIT 1").query(String.class).single()).contains("4 unchanged");
    }

    @Test
    void unknownPageGetsFriendly404() throws Exception {
        mvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
    }
}
