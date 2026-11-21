package dev.eolledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HistoryTest extends WebTestBase {

    long id;
    long otherId;

    void observe(String instant, String reactVersion) {
        CLOCK.now = Instant.parse(instant);
        imports.importJson(jsonInventory(jsonEntry("Hist", "https://github.com/acme/hist", "github", "feature/x",
                "pkg:npm/react@" + reactVersion, "pkg:npm/left-pad@1.3.0")), "test");
    }

    @BeforeEach
    void data() {
        seed();
        observe("2026-11-20T12:00:00Z", "18.2.0");
        observe("2026-11-21T10:00:00Z", "19.0.0");
        observe("2026-11-21T22:30:00Z", "19.1.0"); // 23:30 in Amsterdam: still 21 Nov
        observe("2026-11-21T23:30:00Z", "19.1.0"); // unchanged: no new snapshot
        id = branchId("https://github.com/acme/hist", "feature/x");
        otherId = branchId("https://gitlab.com/acme/alpha", "main");
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void beforeFirstObservationShowsNoSnapshotRatherThanCurrentData() throws Exception {
        String html = page("/repos/" + id + "/history?asOf=2026-11-19");
        assertThat(html).contains("No snapshot had been observed").doesNotContain("pkg:npm/react@19.1.0").doesNotContain("pkg:npm/react@18.2.0");
    }

    @Test
    void asOfDayPicksLatestSnapshotAtEndOfThatDayInConfiguredZone() throws Exception {
        assertThat(page("/repos/" + id + "/history?asOf=2026-11-20")).contains("pkg:npm/react@18.2.0").doesNotContain("pkg:npm/react@19.0.0\"");
        String day2 = page("/repos/" + id + "/history?asOf=2026-11-21");
        assertThat(day2).contains("pkg:npm/react@19.1.0");
    }

    @Test
    void timelineKeepsSameDaySnapshotsInOrderWithDiffs() throws Exception {
        String full = page("/repos/" + id + "/history?asOf=2026-11-21");
        String html = full.substring(full.indexOf("Snapshot timeline"));
        assertThat(db.sql("SELECT count(*) FROM snapshot WHERE branch_id = ?").param(id).query(Integer.class).single()).isEqualTo(3);
        int a = html.indexOf("2026-11-20 13:00:00");
        int b = html.indexOf("2026-11-21 11:00:00");
        int c = html.indexOf("2026-11-21 23:30:00");
        assertThat(a).isPositive();
        assertThat(b).isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
        assertThat(html).contains("pkg:npm/react 18.2.0 → 19.0.0", "pkg:npm/react 19.0.0 → 19.1.0");
    }

    @Test
    void specificSnapshotCanBeViewedAndIsLabelledWithCurrentEolData() throws Exception {
        long first = db.sql("SELECT min(id) FROM snapshot WHERE branch_id = ?").param(id).query(Long.class).single();
        String html = page("/repos/" + id + "/history?snapshot=" + first);
        assertThat(html).contains("pkg:npm/react@18.2.0").contains("today's endoflife.date data");
    }

    @Test
    void snapshotOfAnotherBranchIsNotFound() throws Exception {
        long foreign = db.sql("SELECT min(id) FROM snapshot WHERE branch_id = ?").param(otherId).query(Long.class).single();
        mvc.perform(get("/repos/" + id + "/history?snapshot=" + foreign)).andExpect(status().isNotFound());
    }

    @Test
    void malformedAsOfIs400() throws Exception {
        mvc.perform(get("/repos/" + id + "/history?asOf=yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void revertedInventoryShowsAsNewTimelineEntry() throws Exception {
        observe("2026-11-22T09:00:00Z", "18.2.0");
        String html = page("/repos/" + id + "/history?asOf=2026-11-22");
        assertThat(html).contains("pkg:npm/react 19.1.0 → 18.2.0");
        assertThat(db.sql("SELECT count(*) FROM snapshot WHERE branch_id = ?").param(id).query(Integer.class).single()).isEqualTo(4);
    }
}
