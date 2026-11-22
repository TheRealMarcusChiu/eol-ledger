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
    void importHistoryIsOnePopoverOpenedBesideImportFileAndTheDemoDownloadSitsInFormatHelp() throws Exception {
        String html = mvc.perform(get("/imports")).andReturn().getResponse().getContentAsString();
        String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
        String upload = main.substring(main.indexOf("aria-labelledby=\"upload-h\""), main.indexOf("</section>", main.indexOf("aria-labelledby=\"upload-h\"")));
        // the actions row: Import file, then Import History right after it, nothing else
        String actions = upload.substring(upload.indexOf("<div class=\"actions\">"), upload.indexOf("</div>", upload.indexOf("<div class=\"actions\">")));
        assertThat(actions.replaceAll("\\s+", " ")).isEqualTo("<div class=\"actions\"> <button type=\"button\" popovertarget=\"import-dialog\">Import file</button>"
                + " <button type=\"button\" class=\"button-quiet\" popovertarget=\"history-dialog\">Import History</button> ");
        // exactly one history table, inside the closed native popover the button targets, with a title and a Close button
        assertThat(main.split("data-noun=\"import runs\"", -1)).hasSize(2);
        assertThat(main.split("aria-label=\"Import history\"", -1)).hasSize(2);
        String dialog = upload.substring(upload.indexOf("<div id=\"history-dialog\""), upload.indexOf("<details"));
        assertThat(dialog).contains("class=\"import-dialog history-dialog\" popover role=\"dialog\" aria-labelledby=\"history-dialog-h\"",
                "<h3 id=\"history-dialog-h\">Import History</h3>", "popovertarget=\"history-dialog\" popovertargetaction=\"hide\">Close</button>",
                "data-noun=\"import runs\"", ">When</th>", ">New snapshots</th>", ">Details</th>", "No imports yet.");
        assertThat(dialog).doesNotContain(" open");
        // the demo download is only inside the closed Format help, same endpoint
        String help = upload.substring(upload.indexOf("<summary>Format help</summary>"));
        assertThat(help).contains("<a class=\"button-quiet\" href=\"/imports/samples/demo-dependency-inventory.json\" download>Download demo JSON</a>");
        assertThat(help).contains("four fictional DEMO code repos");
        assertThat(main.split("Download demo JSON", -1)).hasSize(2);
        // Clear all data stays the last section
        assertThat(main.lastIndexOf("<section")).isEqualTo(main.indexOf("aria-labelledby=\"clear-h\"") - "<section class=\"panel\" ".length());
        assertThat(main.indexOf("data-noun=\"import runs\"")).isLessThan(main.lastIndexOf("<section"));
    }

    @Test
    void demoJsonIsDownloadableFromTheUploadSectionAndUploadable() throws Exception {
        String html = mvc.perform(get("/imports")).andReturn().getResponse().getContentAsString();
        String upload = html.substring(html.indexOf("aria-labelledby=\"upload-h\""), html.indexOf("</section>", html.indexOf("aria-labelledby=\"upload-h\"")));
        assertThat(upload).contains("href=\"/imports/samples/demo-dependency-inventory.json\"", ">Download demo JSON</a>", ">Import file</button>");
        // the file chooser lives only in the popup that "Import file" opens (native popover, no script needed)
        assertThat(upload).contains("<h2 id=\"upload-h\">Code Repo &amp; dependency Data</h2>");
        assertThat(upload).contains("<button type=\"button\" popovertarget=\"import-dialog\">Import file</button>");
        String dialog = upload.substring(upload.indexOf("id=\"import-dialog\""));
        assertThat(dialog).contains("popover", "type=\"file\"", ">Upload and import</button>", "popovertargetaction=\"hide\"");
        assertThat(upload.substring(0, upload.indexOf("id=\"import-dialog\""))).doesNotContain("type=\"file\"");

        byte[] body = mvc.perform(get("/imports/samples/demo-dependency-inventory.json")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        mvc.perform(multipart("/imports").file(json(new String(body, StandardCharsets.UTF_8))).with(csrf()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/imports"));
        assertThat(count("repo_branch")).isEqualTo(22);
        assertThat(count("snapshot")).isEqualTo(22);
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
        assertThat(count("repo_branch")).isEqualTo(22);
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
