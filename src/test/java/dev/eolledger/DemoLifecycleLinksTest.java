package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo sample links a few natively resolved packages to endoflife.date products on every branch,
 * without touching the original resolved graphs. The catalogue is a pinned copy of the real
 * endoflife.date v1 spring-framework and django products (fetched 2026-11-21), not the network.
 */
class DemoLifecycleLinksTest extends WebTestBase {

    @Autowired Ledger ledger;

    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> ADDED_MODULES = Set.of("lifecycle-maven", "lifecycle-uv");
    /** SHA-256 of each entry as shipped before the lifecycle modules were added (compact JSON, key order as in the file). */
    static final Map<String, String> ORIGINAL = Map.of(
            "enterprise-suite main", "edf3434d3f31049b15410447c30adfbc62f46cd4757b11481f62d8ad12b1ac49",
            "enterprise-suite release/legacy", "2ac6fe10917c04f72215a7f44b4886b8fbc7cf79656c111e8f683d1488694cd3",
            "web-platform main", "b9d5e0ce907b1647370c83f451f3152a7425b9866321be2cf8af306b8060a32f",
            "web-platform release/legacy", "82779f9dee56d42b388de23606328bce85986e2c1110d00fcb6a05d60aec8607");
    /** The matched cycle per branch, with its isEol flag as published on 2026-11-21. */
    static final Map<String, Map<String, Boolean>> LINKED = Map.of(
            "enterprise-suite main", Map.of("spring-framework 7.0", false),
            "enterprise-suite release/legacy", Map.of("spring-framework 5.3", true),
            "web-platform main", Map.of("django 5.2", false),
            "web-platform release/legacy", Map.of("django 3.2", true),
            "payments-service main", Map.of("spring-framework 7.0", false),
            "payments-service release/legacy", Map.of("spring-framework 5.3", true),
            "reporting-service main", Map.of("django 5.2", false),
            "reporting-service release/legacy", Map.of("django 3.2", true));

    @Test
    void everyBranchLinksToARealCatalogueCycleAndKeepsItsOriginalGraph() throws Exception {
        String body = ImportService.sample(ImportService.SAMPLE);
        JsonNode sample = JSON.readTree(body);
        assertThat(sample).hasSize(8);
        int checked = 0;
        for (JsonNode e : sample) {
            String key = e.path("name").asText() + " " + e.path("branch").asText();
            if (!ORIGINAL.containsKey(key)) continue;
            checked++;
            ObjectNode original = e.deepCopy();
            original.set("projects", withoutAddedModules(e.path("projects"), "path"));
            original.set("dependencies", withoutAddedModules(e.path("dependencies"), "projectPath"));
            assertThat(sha256(JSON.writeValueAsString(original))).as("original graph of " + key).isEqualTo(ORIGINAL.get(key));
        }
        assertThat(checked).isEqualTo(4);

        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(
                new String(new ClassPathResource("catalogue/demo-linked-products.json").getContentAsByteArray(), StandardCharsets.UTF_8)));
        assertThat(catalog.sync().productsSynced()).isEqualTo(2);
        assertThat(imports.importJson(body, "test").newSnapshots()).isEqualTo(8);
        assertThat(imports.importJson(body, "again").newSnapshots()).isZero();

        for (JsonNode e : sample) {
            String key = e.path("name").asText() + " " + e.path("branch").asText();
            List<Ledger.Dep> deps = ledger.deps(ledger.branch(branchId(e.path("url").asText(), e.path("branch").asText())).snapshotId());
            List<Ledger.Dep> linked = deps.stream().filter(d -> d.cycle() != null).toList();
            assertThat(linked).as(key).isNotEmpty();
            assertThat(linked).as(key).allSatisfy(d -> {
                assertThat(d.match().evidence()).contains("is an endoflife.date identifier of");
                assertThat(LINKED.get(key)).containsEntry(d.cycle().key(), d.cycle().isEol());
            });
            assertThat(linked.stream().map(d -> d.cycle().key()).distinct()).as(key).containsExactlyElementsOf(LINKED.get(key).keySet());
            // everything else stays honestly unmatched: no product guessed
            assertThat(deps).filteredOn(d -> d.cycle() == null).allSatisfy(d -> assertThat(d.match().product()).isNull());
        }
    }

    @Test
    void addedLifecycleRowsAreBackedByShippedNativeResolverOutput() throws Exception {
        JsonNode sample = JSON.readTree(ImportService.sample(ImportService.SAMPLE));
        int added = 0;
        for (JsonNode e : sample) {
            for (JsonNode p : e.path("projects")) {
                if (!ADDED_MODULES.contains(p.path("path").asText())) continue;
                assertThat(p.path("resolution").path("status").asText()).isEqualTo("resolved");
                assertThat(new ClassPathResource("samples/" + p.path("resolution").path("tree").asText()).exists()).isTrue();
            }
            for (JsonNode d : e.path("dependencies")) {
                if (!ADDED_MODULES.contains(d.path("projectPath").asText())) continue;
                added++;
                assertThat(d.path("recordSource").asText()).isEqualTo("resolved-dependency-tree");
                String tree = new String(new ClassPathResource("samples/" + d.path("verification").path("source").asText())
                        .getContentAsByteArray(), StandardCharsets.UTF_8);
                String artifact = d.path("artifactId").isTextual() ? d.path("artifactId").asText() : d.path("name").asText();
                assertThat(tree).as(d.path("purl").asText()).contains(artifact).contains(d.path("version").asText());
            }
        }
        assertThat(added).isPositive();
    }

    static ArrayNode withoutAddedModules(JsonNode rows, String field) {
        ArrayNode kept = JSON.createArrayNode();
        rows.forEach(r -> {
            if (!ADDED_MODULES.contains(r.path(field).asText())) kept.add(r);
        });
        return kept;
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
