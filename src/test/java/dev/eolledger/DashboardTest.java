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
        assertThat(html).contains("id=\"start\" name=\"start\" type=\"date\" value=\"\"", "id=\"end\" name=\"end\" type=\"date\" value=\"\"")
                .doesNotContain("value=\"2026-11-20\"", "id=\"unused\" name=\"unused\" type=\"checkbox\" value=\"true\" checked");
        assertThat(rows(html)).isEqualTo(rows(page("/?start=&end=")));
    }

    static java.util.List<String> rows(String html) {
        return java.util.regex.Pattern.compile("data-row=\"[^\"]+\"").matcher(html).results().map(java.util.regex.MatchResult::group).toList();
    }

    @Test
    void dependencyTableHasTheColumnContractAndProgressiveSettings() throws Exception {
        String html = page("/");
        String table = html.substring(html.indexOf("<table id=\"dependencies-table\">"), html.indexOf("</table>"));
        for (String key : java.util.List.of("dependency", "version", "eol", "status", "repos", "branches")) {
            assertThat(table).as(key).contains("<col data-column=\"" + key + "\">", "<th scope=\"col\"", "data-column=\"" + key + "\">");
            // one header cell plus one cell per row (5 used versions)
            assertThat(table.split("data-column=\"" + key + "\"", -1)).as(key).hasSize(1 + 1 + 1 + 5);
        }
        assertThat(table).contains(">Dependency Name</th>", ">Version</th>", ">EoL date</th>", ">Status</th>", ">Code repos</th>", ">Branches</th>")
                .doesNotContain(">Product</th>", ">Cycle</th>");
        assertThat(html).contains("role=\"region\" aria-label=\"EoL Dependencies\"", "<details id=\"table-settings\" class=\"help\" hidden>",
                "<summary>Table settings</summary>", "<button type=\"button\" data-columns-reset>Reset columns</button>",
                ">Include unused dependencies</label>").doesNotContain("Include unused cycles");
        // the no-JS table keeps every column and link; resize handles and per-column controls only exist once app.js runs
        assertThat(html).doesNotContain("data-column-resize", "data-column-toggle", "data-column-width");
    }

    @Test
    void emptyTableRowSpansAllColumns() throws Exception {
        String html = page("/?start=2000-01-01&end=2000-01-02");
        assertThat(html).contains("<td colspan=\"6\" class=\"empty\">No dependency versions in this range.</td>");
    }

    @Test
    void explicitlyClearedStartMeansAllTimeIncludingUnknownDates() throws Exception {
        String html = page("/?start=&end=");
        assertThat(html).contains("data-row=\"react 18\"", "data-row=\"python 3.10\"", "data-row=\"python 3.1\"", "data-row=\"python 2.7\"", "data-row=\"django 4.2\"")
                .doesNotContain("data-row=\"react 19\"", "data-row=\"python 3.9\"");
    }

    @Test
    void endOnlyRunsFromBeginningAndExcludesUnknownDates() throws Exception {
        String html = page("/?start=&end=2025-01-01");
        assertThat(html).contains("data-row=\"react 18\"", "data-row=\"python 3.1\"")
                .doesNotContain("data-row=\"python 2.7\"", "data-row=\"python 3.10\"", "data-row=\"django 4.2\"");
    }

    @Test
    void startOnlyRunsForever() throws Exception {
        String html = page("/?start=2026-11-21");
        assertThat(html).contains("data-row=\"django 4.2\"").doesNotContain("data-row=\"python 3.10\"");
    }

    @Test
    void bothBoundsAreInclusive() throws Exception {
        String html = page("/?start=2026-11-20&end=2026-11-20");
        assertThat(html).contains("data-row=\"python 3.10\"").doesNotContain("data-row=\"django 4.2\"");
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
    void resetLinkPointsToDefaultView() throws Exception {
        mvc.perform(get("/?start=2026-01-01&unused=true")).andExpect(content().string(containsString("href=\"/\">Reset filters</a>")));
    }

    @Test
    void rowsHaveRealLinksToCycleDetail() throws Exception {
        assertThat(page("/?start=&end=")).contains("href=\"/products/python?cycle=3.10\"");
    }

    @Test
    void updateEolDataButtonPostsToTheExistingCsrfProtectedSyncAction() throws Exception {
        String html = page("/");
        int form = html.indexOf("action=\"/catalog/sync\"");
        assertThat(form).isPositive();
        String formHtml = html.substring(form, html.indexOf("</form>", form));
        assertThat(formHtml).contains("method=\"post\"", "name=\"_csrf\"", "Update EoL data");
        assertThat(html).contains("href=\"https://github.com/endoflife-date/endoflife.date\"", "href=\"https://endoflife.date/docs/api/v1/\"");
        // the visible button really works without JS: same endpoint, 303 back to the status page; no CSRF token = refused
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/catalog/sync"))
                .andExpect(status().isForbidden());
    }

    @Test
    void defaultViewHasNoDateCutoffWhateverTheClock() throws Exception {
        CLOCK.now = java.time.Instant.parse("2030-01-01T00:00:00Z");
        assertThat(page("/")).contains("data-row=\"python 3.10\"", "data-row=\"django 4.2\"")
                .doesNotContain("value=\"2030-01-01\"");
    }
}
