package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The browser's catalogue sync runs as one background job with live status; clear and duplicate syncs are refused while it runs. */
class BackgroundSyncTest extends WebTestBase {

    static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void release() {
        catalog.endWork();
    }

    JsonNode syncStatus() throws Exception {
        return JSON.readTree(mvc.perform(get("/catalog/sync/status")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode awaitDone() throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode s = syncStatus();
            if (!s.path("running").asBoolean() && s.hasNonNull("finishedAt")) return s;
            Thread.sleep(25);
        }
        throw new AssertionError("sync did not finish");
    }

    @Test
    void startReturnsImmediatelyAndStatusReportsRealCounts() throws Exception {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE));
        JsonNode started = JSON.readTree(mvc.perform(post("/catalog/sync/start").with(csrf())).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        assertThat(started.path("started").asBoolean()).isTrue();
        JsonNode done = awaitDone();
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(done.path("productsDone").asInt()).isEqualTo(3);
        assertThat(done.path("productsTotal").asInt()).isEqualTo(3);
        assertThat(done.path("cyclesDone").asInt()).isEqualTo(7);
        assertThat(done.path("cyclesTotal").asInt()).isEqualTo(7);
        assertThat(done.path("message").asText()).isEqualTo("Synced 3 product(s)");
        assertThat(db.sql("SELECT count(*) FROM release_cycle").query(Integer.class).single()).isEqualTo(7);
    }

    @Test
    void fetchFailureIsATerminalVisibleFailure() throws Exception {
        mvc.perform(post("/catalog/sync/start").with(csrf())).andExpect(status().isAccepted());
        JsonNode done = awaitDone();
        assertThat(done.path("ok").asBoolean()).isFalse();
        assertThat(done.path("message").asText()).contains("catalogue fetch failed");
        assertThat(done.path("productsTotal").asInt()).isZero();
    }

    @Test
    void theGateIsReleasedOnlyAfterTheTerminalStateIsPublished() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE));
        assertThat(catalog.startAsync()).isTrue();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!catalog.beginWork()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.onSpinWait();
        }
        // whoever acquires the gate next sees the finished job, never one still marked running
        assertThat(catalog.progress().running()).isFalse();
        assertThat(catalog.progress().finishedAt()).isNotNull();
        assertThat(catalog.progress().ok()).isTrue();
    }

    @Test
    void anUnexpectedRuntimeFailureStillEndsTheJobVisibly() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE));
        db.sql("ALTER TABLE sync_status RENAME TO sync_status_hidden").update();
        try {
            CatalogService.SyncResult r = catalog.sync();
            assertThat(r.ok()).isFalse();
            assertThat(r.message()).startsWith("Sync failed:");
        } finally {
            db.sql("ALTER TABLE sync_status_hidden RENAME TO sync_status").update();
        }
        assertThat(catalog.progress().running()).isFalse();
        assertThat(catalog.progress().ok()).isFalse();
        assertThat(catalog.beginWork()).isTrue(); // the gate was released too
    }

    @Test
    void startNeedsCsrf() throws Exception {
        mvc.perform(post("/catalog/sync/start")).andExpect(status().isForbidden());
    }

    @Test
    void whileWorkIsActiveSyncsAndClearAreRefusedWithoutBlocking() throws Exception {
        seed();
        assertThat(catalog.beginWork()).isTrue();
        // a second manual, scheduled or browser sync is refused at once, never queued behind the first
        assertThat(catalog.sync().ok()).isFalse();
        assertThat(catalog.sync().message()).contains("already running");
        JsonNode refused = JSON.readTree(mvc.perform(post("/catalog/sync/start").with(csrf())).andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(refused.path("started").asBoolean()).isFalse();
        mvc.perform(post("/catalog/sync").with(csrf())).andExpect(status().isSeeOther())
                .andExpect(flash().attribute("warning", org.hamcrest.Matchers.containsString("already running")));
        // clear is refused at the service boundary too, and nothing is deleted
        assertThatThrownBy(() -> imports.clearAll()).isInstanceOf(ImportService.ImportFailedException.class)
                .hasMessageContaining("catalogue sync is running");
        mvc.perform(post("/imports/clear").param("confirm", "true").with(csrf())).andExpect(status().isSeeOther())
                .andExpect(flash().attribute("warning", org.hamcrest.Matchers.containsString("catalogue sync is running")));
        assertThat(db.sql("SELECT count(*) FROM product").query(Integer.class).single()).isEqualTo(3);
        // status and pages still answer while it runs
        assertThat(syncStatus().has("phase")).isTrue();
        mvc.perform(get("/")).andExpect(status().isOk());
        catalog.endWork();
        assertThat(catalog.sync().ok()).isTrue();
    }
}
