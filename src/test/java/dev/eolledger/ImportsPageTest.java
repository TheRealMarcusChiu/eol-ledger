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

    MockMultipartFile json(String body) {
        return new MockMultipartFile("file", "inventory.json", "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    int count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void pageDocumentsTheJsonFormatAndHasCsrfProtectedForms() throws Exception {
        mvc.perform(get("/imports")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Dependency-inventory JSON")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("enctype=\"multipart/form-data\"")))
                .andExpect(content().string(not(containsString("repo_name,repo_url,provider,branch,sbom_url"))))
                .andExpect(content().string(not(containsString("Demo data"))))
                .andExpect(content().string(not(containsString("Import demo registry"))))
                .andExpect(content().string(not(containsString("Refresh all now"))));
    }

    @Test
    void demoJsonIsDownloadableFromTheUploadSectionAndUploadable() throws Exception {
        String html = mvc.perform(get("/imports")).andReturn().getResponse().getContentAsString();
        String upload = html.substring(html.indexOf("aria-labelledby=\"upload-h\""), html.indexOf("</section>", html.indexOf("aria-labelledby=\"upload-h\"")));
        assertThat(upload).contains("href=\"/imports/samples/demo-dependency-inventory.json\"", ">Download demo JSON</a>", ">Import file</button>");

        byte[] body = mvc.perform(get("/imports/samples/demo-dependency-inventory.json")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        mvc.perform(multipart("/imports").file(json(new String(body, StandardCharsets.UTF_8))).with(csrf()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/imports"));
        assertThat(count("repo_branch")).isEqualTo(4);
        assertThat(count("snapshot")).isEqualTo(4);
    }

    @Test
    void actionRowsWrapSoNarrowScreensDoNotOverflow() throws Exception {
        String css = mvc.perform(get("/app.css")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(css).containsPattern("\\.actions \\{[^}]*flex-wrap: wrap");
    }

    @Test
    void unknownOrRemovedSampleNameIsNotFound() throws Exception {
        mvc.perform(get("/imports/samples/application.properties")).andExpect(status().isNotFound());
        mvc.perform(get("/imports/samples/demo-registry.csv")).andExpect(status().isNotFound());
    }

    @Test
    void removedCsvDemoAndRefreshEndpointsAreGone() throws Exception {
        mvc.perform(post("/imports/demo").param("name", "demo-registry.csv").with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(post("/imports/refresh").with(csrf())).andExpect(status().is4xxClientError());
        assertThat(count("repo_branch")).isZero();
    }

    @Test
    void uploadWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(multipart("/imports").file(json(ImportService.sample("demo-dependency-inventory.json")))).andExpect(status().isForbidden());
        assertThat(count("repo_branch")).isZero();
    }

    @Test
    void csvUploadIsRejectedAndKeepsExistingData() throws Exception {
        mvc.perform(multipart("/imports").file(json(ImportService.sample("demo-dependency-inventory.json"))).with(csrf()));
        MockMultipartFile csv = new MockMultipartFile("file", "registry.csv", "text/csv",
                "repo_name,repo_url,provider,branch,sbom_url\nX,https://github.com/a/b,github,main,https://example.com/x.json\n".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/imports").file(csv).with(csrf())).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("not valid JSON")))
                .andExpect(content().string(containsString("Nothing was changed")));
        assertThat(count("repo_branch")).isEqualTo(4);
    }

    @Test
    void emptyUploadIsHelpful400() throws Exception {
        mvc.perform(multipart("/imports").file(json("")).with(csrf())).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Choose a dependency-inventory JSON file")));
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
    void unknownPageGetsFriendly404() throws Exception {
        mvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
    }
}
