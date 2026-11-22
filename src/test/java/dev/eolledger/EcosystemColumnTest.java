package dev.eolledger;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EcosystemColumnTest extends WebTestBase {
    /** React published under two package ecosystems, as endoflife.date products can list several purl identifiers. */
    static final String TWO_ECOSYSTEMS = """
            {"result":[
             {"name":"react","label":"React","identifiers":[{"type":"purl","id":"pkg:npm/react"},{"type":"purl","id":"pkg:nuget/react"}],
              "releases":[{"name":"19","releaseDate":"2024-12-05","isEol":false,"eolFrom":null},
                          {"name":"18","releaseDate":"2022-03-29","isEol":true,"eolFrom":"2025-01-01"}]}
            ]}""";

    String page(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    static List<String> cells(String html, String key) {
        return Pattern.compile("<td data-column=\"" + key + "\"[^>]*>([^<]*)</td>").matcher(html).results().map(m -> m.group(1)).toList();
    }
    long importMixed() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(TWO_ECOSYSTEMS));
        catalog.sync();
        imports.importJson(JsonImportTest.array(JsonImportTest.entry("eco", "2026-11-20T10:00:00Z",
                JsonImportTest.REACT,
                "{\"name\":\"react\",\"version\":\"18.1.0\",\"purl\":\"pkg:nuget/react@18.1.0\",\"ecosystem\":\"NuGet\"}",
                JsonImportTest.dep("core", "1.0.0", "pkg:maven/com.example/core@1.0.0", "backend-maven", "compile", "direct", 1, ""),
                "{\"name\":\"requests\",\"version\":\"2.31.0\",\"purl\":\"pkg:pypi/requests@2.31.0\"}",
                "{\"name\":\"unstated\",\"version\":\"1\"}")), "test");
        return branchId(JsonImportTest.REPO, "eco");
    }

    @Test void latestAndHistoryRowsShowEachImportedEcosystemOrUnknown() throws Exception {
        long id = importMixed();
        for (String path : List.of("/repos/" + id, "/repos/" + id + "/history")) {
            String table = AcceptanceRepairTest.depsTable(page(path));
            assertThat(cells(table, "ecosystem")).as(path).containsExactlyInAnyOrder("npm", "nuget", "maven", "pypi", "Unknown");
            // the combined Ecosystem / Package URL cell is unchanged
            assertThat(table).as(path).contains("<td data-column=\"purl\"><span>npm</span><br><code class=\"small muted\">pkg:npm/react@18.2.0</code></td>",
                    "<td data-column=\"purl\"><span>n/a</span><br></td>");
        }
    }

    @Test void aggregateRowJoinsDistinctSortedEcosystemsAndUnusedRowsAreUnknown() throws Exception {
        importMixed();
        String html = page("/?unused=true");
        int start = html.indexOf("data-row=\"react 18\"");
        assertThat(cells(html.substring(start, html.indexOf("</tr>", start)), "ecosystem")).containsExactly("npm; nuget");
        int unused = html.indexOf("data-row=\"react 19\"");
        assertThat(unused).isPositive();
        assertThat(cells(html.substring(unused, html.indexOf("</tr>", unused)), "ecosystem")).containsExactly("Unknown");
    }

    @Test void cycleAffectedBranchesShowEcosystemAndEmptyStateSpansAllColumns() throws Exception {
        importMixed();
        String used = page("/dependencies/react?version=18");
        assertThat(used).contains("<th scope=\"col\">Package</th>\n                <th scope=\"col\">Ecosystem</th>");
        assertThat(Pattern.compile("<td>(npm|nuget)</td>").matcher(used).results().map(m -> m.group(1)).toList())
                .containsExactlyInAnyOrder("npm", "nuget");
        assertThat(page("/dependencies/react?version=19")).contains("<td colspan=\"6\" class=\"empty\">No branch uses this cycle");
    }
}
