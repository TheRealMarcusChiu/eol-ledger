package dev.eolledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardTest extends WebTestBase {

    @BeforeEach
    void data() {
        seed();
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void defaultListsAllTrackedVersionsWithNoDateCutoffAndHidesUnused() throws Exception {
        String html = page("/");
        // past, today, future and unknown EoL dates alike; unused versions only on request
        assertThat(html).contains("data-row=\"react 18\"", "data-row=\"python 3.1\"", "data-row=\"python 3.10\"", "data-row=\"django 4.2\"", "data-row=\"python 2.7\"")
                .doesNotContain("data-row=\"react 19\"", "data-row=\"python 3.9\"");
        assertThat(shown(html)).isEqualTo(rows(html)).hasSize(5);
        // blank initial state: no bounds, no today, empty row hidden
        assertThat(html).contains("<table id=\"dependencies-table\" data-sort=\"asc\" data-legacy=\"false\" data-dependency-table=\"dashboard\">", "<tr data-empty hidden=\"hidden\">")
                .doesNotContain(" value=\"2026-11-20\"");
        assertThat(rows(html)).isEqualTo(rows(page("/?start=&end=")));
    }

    static java.util.List<String> rows(String html) {
        return java.util.regex.Pattern.compile("data-row=\"[^\"]+\"").matcher(html).results().map(java.util.regex.MatchResult::group).toList();
    }

    /** Rows a no-JS reader sees: every row is rendered, the ones outside a legacy range carry hidden. */
    static java.util.List<String> shown(String html) {
        return java.util.regex.Pattern.compile("<tr ([^>]*)>").matcher(html).results().map(m -> m.group(1))
                .filter(a -> a.contains("data-row=") && !a.contains(" hidden=\"hidden\""))
                .map(a -> a.replaceAll(".*(data-row=\"[^\"]+\").*", "$1")).toList();
    }

    @Test
    void oldTopControlsAreGoneAndTheSyncEndpointStays() throws Exception {
        String html = page("/");
        assertThat(html).doesNotContain("<form", "id=\"start\"", "id=\"end\"", "id=\"sort\"", "id=\"unused\"", ">Apply</button>", "Reset filters",
                "Update EoL data", "action=\"/catalog/sync\"", "EoL from", "EoL until", "Include unused", "filter-help", "update-help");
        // removed from the dashboard only: the CSRF-protected sync action still exists (Imports keeps its button)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/catalog/sync"))
                .andExpect(status().isForbidden());
    }

    @Test
    void eachCellCarriesItsMachineValue() throws Exception {
        String html = page("/");
        int row = html.indexOf("data-row=\"python 2.7\"");
        String tr = html.substring(row, html.indexOf("</tr>", row));
        assertThat(tr).contains("data-column=\"dependency\" data-value=\"python\"", "data-column=\"version\" data-value=\"2.7.18\"",
                "data-column=\"eol\" class=\"date\" data-value=\"\">Not published<", "data-column=\"repos\" class=\"num\" data-value=\"1\"");
        int r310 = html.indexOf("data-row=\"python 3.10\"");
        assertThat(html.substring(r310, html.indexOf("</tr>", r310))).contains("data-value=\"2026-11-20\">2026-11-20<");
    }

    @Test
    void dependencyTableHasTheColumnContractAndProgressiveSettings() throws Exception {
        String html = page("/");
        String table = html.substring(html.indexOf("<table id=\"dependencies-table\""), html.indexOf("</table>"));
        for (String key : java.util.List.of("dependency", "version", "context", "purl", "ecosystem", "cycle", "eol", "status", "repos", "branches", "evidence", "source", "sbom")) {
            assertThat(table).as(key).contains("<col data-column=\"" + key + "\">", "<th scope=\"col\"", "data-column=\"" + key + "\">");
            // one header cell plus one cell per row (5 used versions)
            assertThat(table.split("data-column=\"" + key + "\"", -1)).as(key).hasSize(1 + 1 + 1 + 5);
        }
        assertThat(table).contains(">Dependency Name</th>", ">Version</th>", ">EOL Date</th>", ">Status</th>", "># Code Repos Using It</th>", "># Code Repo Branches Using It</th>", ">Ecosystem / Package URL</th>", ">Ecosystem</th>")
                .doesNotContain(">Product</th>", ">Cycle</th>", "Ecosystem / purl");
        assertThat(html).contains("role=\"region\" aria-label=\"EoL Dependencies\"", "<details id=\"table-settings\" class=\"help\" hidden>",
                "<summary>Table settings</summary>", "<button type=\"button\" data-columns-reset>Reset columns</button>");
        // the no-JS table keeps every column and link; resize handles, per-column and header sort/filter controls only exist once app.js runs
        assertThat(html).doesNotContain("data-column-resize", "data-column-toggle", "data-column-width", "data-column-sort", "data-filter-open",
                "data-column-filter", "data-filters-clear-all", "data-filter-count");
    }

    @Test
    void emptyTableRowSpansAllColumns() throws Exception {
        String html = page("/?start=2000-01-01&end=2000-01-02");
        assertThat(html).contains("<tr data-empty>\n                <td colspan=\"13\" class=\"empty\">No dependency versions match.</td>");
        assertThat(shown(html)).isEmpty();
        assertThat(rows(html)).hasSize(5);
    }

    @Test
    void explicitlyClearedStartMeansAllTimeIncludingUnknownDates() throws Exception {
        String html = page("/?start=&end=");
        assertThat(shown(html)).containsExactlyInAnyOrder("data-row=\"react 18\"", "data-row=\"python 3.10\"", "data-row=\"python 3.1\"",
                "data-row=\"python 2.7\"", "data-row=\"django 4.2\"");
    }

    @Test
    void endOnlyRunsFromBeginningAndExcludesUnknownDates() throws Exception {
        String html = page("/?start=&end=2025-01-01");
        assertThat(shown(html)).contains("data-row=\"react 18\"", "data-row=\"python 3.1\"")
                .doesNotContain("data-row=\"python 2.7\"", "data-row=\"python 3.10\"", "data-row=\"django 4.2\"");
    }

    @Test
    void startOnlyRunsForever() throws Exception {
        String html = page("/?start=2026-11-21");
        assertThat(shown(html)).contains("data-row=\"django 4.2\"").doesNotContain("data-row=\"python 3.10\"");
    }

    @Test
    void bothBoundsAreInclusive() throws Exception {
        String html = page("/?start=2026-11-20&end=2026-11-20");
        assertThat(shown(html)).containsExactly("data-row=\"python 3.10\"");
    }

    @Test
    void legacyFilteredLinkRendersTheFullRowSetSoClearingRestoresEverything() throws Exception {
        String html = page("/?start=2026-11-20&end=2026-11-20&sort=desc");
        // hidden, not dropped: app.js turns the bounds into the EoL date header filter and can clear it in place
        assertThat(rows(html)).containsExactlyInAnyOrderElementsOf(rows(page("/")));
        assertThat(html).contains("data-start=\"2026-11-20\" data-end=\"2026-11-20\" data-sort=\"desc\" data-legacy=\"true\"");
        assertThat(page("/?unused=true")).contains("data-legacy=\"true\"", "data-row=\"react 19\"");
    }

    @Test
    void repositoriesAreCountedOnceAcrossBranches() throws Exception {
        String html = page("/?start=&end=");
        assertThat(html).contains("data-row=\"react 18\" data-repos=\"1\" data-branches=\"2\"");
        assertThat(html).contains("data-row=\"django 4.2\" data-repos=\"2\" data-branches=\"2\"");
        // 3.1.4 must not be counted under 3.10, nor 3.10.5 under 3.1
        assertThat(html).contains("data-row=\"python 3.10\" data-repos=\"1\" data-branches=\"1\"");
        assertThat(html).contains("data-row=\"python 3.1\" data-repos=\"1\" data-branches=\"1\"");
    }

    @Test
    void unusedCyclesCanBeShown() throws Exception {
        String html = page("/?start=&end=&unused=true");
        assertThat(html).contains("data-row=\"react 19\" data-repos=\"0\"", "data-row=\"python 3.9\" data-repos=\"0\"");
    }

    @Test
    void sortsByEolDateBothWays() throws Exception {
        String asc = page("/?start=&end=&sort=asc");
        assertThat(asc.indexOf("data-row=\"react 18\"")).isLessThan(asc.indexOf("data-row=\"django 4.2\""));
        String desc = page("/?start=&end=&sort=desc");
        assertThat(desc.indexOf("data-row=\"django 4.2\"")).isLessThan(desc.indexOf("data-row=\"react 18\""));
    }

    @Test
    void malformedDateIsHelpful400() throws Exception {
        mvc.perform(get("/?start=2026-13-45")).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("YYYY-MM-DD")));
    }

    @Test
    void reversedRangeIs400() throws Exception {
        mvc.perform(get("/?start=2026-12-01&end=2026-01-01")).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("on or before")));
    }

    @Test
    void invalidLegacyLinkOffersAWayBackToTheDefaultView() throws Exception {
        mvc.perform(get("/?start=2026-13-45&unused=true")).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("href=\"/\">Show all</a>")));
    }

    @Test
    void rowsHaveRealLinksToCycleDetail() throws Exception {
        assertThat(page("/?start=&end=")).contains("href=\"/dependencies/python?version=3.10\"");
    }

    @Test
    void defaultViewHasNoDateCutoffWhateverTheClock() throws Exception {
        CLOCK.now = java.time.Instant.parse("2030-01-01T00:00:00Z");
        String html = page("/");
        assertThat(shown(html)).contains("data-row=\"python 3.10\"", "data-row=\"django 4.2\"");
        assertThat(html).doesNotContain("data-start=", "data-end=", " value=\"2030-01-01\"");
    }
}
