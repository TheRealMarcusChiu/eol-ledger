package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payments-service and reporting-service are real Git repositories built and resolved natively by
 * eol-ledger-ops/drag-preview/additional-demo/generate.py; their rows must be exactly the shipped resolver output.
 */
class AdditionalDemoRepositoriesTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String EVIDENCE = "samples/additional-demo-evidence/";
    /** Full compact-JSON SHA-256 of the four original entries, pinned independently of the generator (release.md). */
    static final Map<String, String> ORIGINAL_FULL = Map.of(
            "enterprise-suite main", "e47e7ce798d04652e949535fb9960e922e0bbfc52477cf8f14558de2a45875da",
            "enterprise-suite release/legacy", "7ac42082d57de3bd44c06ebfb22132314ab29ff3e2f080813bbb0e10300350ed",
            "web-platform main", "e4217d50d3a85edb3b56c335aca3c9283302a1fdc76d4d522e8255768a7de1e2",
            "web-platform release/legacy", "8896da912333a59c06abf64b839ef410a09588f36c3c6d01fde7e2cf0e5a96c9");
    static final Map<String, String> APP_IDS = Map.of("payments-service", "DEMO-1003", "reporting-service", "DEMO-1004");
    static final Map<String, Set<String>> MODULES = Map.of(
            "payments-service", Set.of("payments-api", "payments-common"),
            "reporting-service", Set.of("report-api", "report-worker"));
    /** The direct framework pin per branch, the same versions as the lifecycle-maven / lifecycle-uv demo modules. */
    static final Map<String, String> PINNED = Map.of(
            "payments-service main", "payments-api org.springframework:spring-context 7.0.11",
            "payments-service release/legacy", "payments-api org.springframework:spring-context 5.3.39",
            "reporting-service main", "report-api django 5.2.19",
            "reporting-service release/legacy", "report-api django 3.2.25");
    static final Pattern TREE_LINE = Pattern.compile("^([│├└─ ]*)(\\S+) v(\\S+)");

    @Test
    void originalFourEntriesAreUnchangedAndFourNewBranchesAreAppended() throws Exception {
        JsonNode sample = sample();
        assertThat(sample).hasSize(8);
        Map<String, String> originals = new HashMap<>();
        Set<String> originalApps = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            JsonNode e = sample.get(i);
            originals.put(key(e), sha256(JSON.writeValueAsString(e)));
            originalApps.add(e.path("appId").asText());
        }
        assertThat(originals).isEqualTo(ORIGINAL_FULL);
        String release = text("release.md");
        ORIGINAL_FULL.values().forEach(h -> assertThat(release).contains(h));

        List<String> added = new ArrayList<>();
        for (int i = 4; i < 8; i++) {
            JsonNode e = sample.get(i);
            String name = e.path("name").asText();
            added.add(key(e));
            assertThat(e.path("appId").asText()).isEqualTo(APP_IDS.get(name)).isNotIn(originalApps);
            assertThat(e.path("url").asText()).isEqualTo("https://example.invalid/eol-demo/" + name);
            assertThat(e.path("provider").asText()).isEqualTo("other");
            assertThat(e.path("demo").asBoolean()).isTrue();
            assertThat(e.path("sbom").path("url").isNull()).isTrue();
            assertThat(e.path("sbom").path("format").asText()).isEqualTo("inline-dependency-inventory");
            Instant.parse(e.path("sbom").path("generatedAt").asText());
            List<String> paths = new ArrayList<>();
            e.path("projects").forEach(p -> paths.add(p.path("path").asText()));
            assertThat(paths).containsExactlyInAnyOrderElementsOf(MODULES.get(name));
            String[] pin = PINNED.get(key(e)).split(" ");
            assertThat(e.path("dependencies")).anySatisfy(d -> {
                assertThat(d.path("projectPath").asText()).isEqualTo(pin[0]);
                assertThat(d.path("name").asText()).isEqualTo(pin[1]);
                assertThat(d.path("version").asText()).isEqualTo(pin[2]);
                assertThat(d.path("dependencyType").asText()).isEqualTo("direct");
            });
        }
        assertThat(added).containsExactlyInAnyOrderElementsOf(PINNED.keySet());
        assertThat(InventoryJson.parse(ImportService.sample(ImportService.SAMPLE))).hasSize(8);
    }

    @Test
    void mavenRowsAreExactlyTheResolvedTreeNodes() throws Exception {
        int checked = 0;
        for (JsonNode e : added()) {
            for (JsonNode p : e.path("projects")) {
                if (!p.path("packageManager").asText().equals("Maven")) continue;
                checked++;
                assertThat(p.path("resolution").path("status").asText()).isEqualTo("resolved");
                String treePath = p.path("resolution").path("tree").asText();
                String dir = treePath.substring(0, treePath.lastIndexOf('/') + 1);
                assertThat(resource(dir + "pom.xml").exists()).isTrue();
                assertThat(resource(dir + "tree.txt").exists()).isTrue();
                JsonNode tree = JSON.readTree(resource(treePath).getContentAsByteArray());
                String project = tree.path("groupId").asText() + ":" + tree.path("artifactId").asText() + ":" + tree.path("version").asText();
                assertThat(p.path("coordinate").asText()).isEqualTo(project);
                List<String> expected = new ArrayList<>();
                mavenNodes(tree, 1, project, expected);
                assertThat(expected).isNotEmpty();
                List<String> actual = new ArrayList<>();
                for (JsonNode d : rows(e, p.path("path").asText())) {
                    assertThat(d.path("purl").asText()).isEqualTo("pkg:maven/" + d.path("groupId").asText() + "/" + d.path("artifactId").asText() + "@" + d.path("version").asText());
                    assertThat(d.path("projectCoordinate").asText()).isEqualTo(project);
                    assertThat(d.path("verification").path("source").asText()).isEqualTo(treePath);
                    assertThat(d.path("recordSource").asText()).isEqualTo("resolved-dependency-tree");
                    actual.add(String.join("|", d.path("depth").asText(), d.path("parentCoordinate").asText(), d.path("groupId").asText(),
                            d.path("artifactId").asText(), d.path("version").asText(), d.path("type").asText(), d.path("scope").asText(),
                            d.path("optional").asText(), d.path("dependencyType").asText()));
                }
                assertThat(actual).as(key(e) + " " + p.path("path").asText()).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
        assertThat(checked).isEqualTo(4);
    }

    /**
     * Python rows are every uv.lock reachability edge from the project root, all markers kept (universal lock, not just
     * the active environment): they equal the shipped `uv tree --universal --no-dedupe` nodes and cover every locked package.
     */
    @Test
    void pythonRowsCoverTheFullLockAndMatchTheUniversalTree() throws Exception {
        int checked = 0;
        for (JsonNode e : added()) {
            for (JsonNode p : e.path("projects")) {
                if (!p.path("packageManager").asText().equals("uv")) continue;
                checked++;
                assertThat(p.path("resolution").path("status").asText()).isEqualTo("resolved");
                String treePath = p.path("resolution").path("tree").asText();
                String dir = treePath.substring(0, treePath.lastIndexOf('/') + 1);
                assertThat(resource(dir + "pyproject.toml").exists()).isTrue();
                assertThat(resource(dir + "uv-tree-no-dedupe.txt").exists()).isTrue();
                String project = p.path("coordinate").asText();

                List<String> expected = new ArrayList<>();
                List<String> stack = new ArrayList<>();
                for (String line : text(treePath.substring("additional-demo-evidence/".length())).split("\n")) {
                    var m = TREE_LINE.matcher(line);
                    if (!m.find()) continue;
                    int depth = m.group(1).length() / 4;
                    String node = norm(m.group(2).replaceAll("\\[.*]$", "")) + "==" + m.group(3);
                    while (stack.size() > depth) stack.remove(stack.size() - 1);
                    stack.add(node);
                    if (depth == 0) assertThat(node).isEqualTo(project);
                    else expected.add(depth + "|" + stack.get(depth - 1) + "|" + node);
                }
                assertThat(expected).isNotEmpty();

                Set<String> locked = new HashSet<>();
                for (String pkg : text(dir.substring("additional-demo-evidence/".length()) + "uv.lock").split("\\[\\[package]]")) {
                    var name = Pattern.compile("(?m)^name = \"([^\"]+)\"").matcher(pkg);
                    var version = Pattern.compile("(?m)^version = \"([^\"]+)\"").matcher(pkg);
                    if (!name.find() || !version.find() || pkg.contains("source = { virtual = \".\" }")) continue;
                    locked.add(name.group(1) + "==" + version.group(1));
                }

                List<String> actual = new ArrayList<>();
                Set<String> covered = new HashSet<>();
                for (JsonNode d : rows(e, p.path("path").asText())) {
                    String node = d.path("name").asText() + "==" + d.path("version").asText();
                    assertThat(d.path("purl").asText()).isEqualTo("pkg:pypi/" + d.path("name").asText() + "@" + d.path("version").asText());
                    assertThat(d.path("dependencyType").asText()).isEqualTo(d.path("depth").asInt() == 1 ? "direct" : "transitive");
                    assertThat(d.path("projectCoordinate").asText()).isEqualTo(project);
                    assertThat(d.path("verification").path("source").asText()).isEqualTo(dir + "uv.lock");
                    actual.add(d.path("depth").asText() + "|" + d.path("parentCoordinate").asText() + "|" + norm(d.path("name").asText()) + "==" + d.path("version").asText());
                    covered.add(node);
                }
                assertThat(actual).as(key(e) + " " + p.path("path").asText()).containsExactlyInAnyOrderElementsOf(expected);
                assertThat(covered).as("every locked package of " + key(e) + " " + p.path("path").asText()).isEqualTo(locked);
            }
        }
        assertThat(checked).isEqualTo(4);
    }

    @Test
    void branchCommitsMatchBundleHeadsReceiptsAndRun() throws Exception {
        JsonNode run = JSON.readTree(resource("additional-demo-evidence/run.json").getContentAsByteArray());
        Map<String, String> runHashes = new HashMap<>();
        run.path("originalEntrySha256").fields().forEachRemaining(f -> runHashes.put(f.getKey(), f.getValue().asText()));
        assertThat(runHashes).isEqualTo(ORIGINAL_FULL);
        for (JsonNode e : added()) {
            String commit = e.path("provenance").path("commit").asText();
            String branch = e.path("branch").asText();
            assertThat(commit).matches("[0-9a-f]{40}");
            assertThat(e.path("provenance").path("branch").asText()).isEqualTo(branch);
            assertThat(e.path("localRepository").asText()).isEqualTo("additional-demo-evidence/sources/" + e.path("name").asText() + ".bundle");
            assertThat(e.path("provenance").path("bundle").asText()).isEqualTo(e.path("localRepository").asText());

            byte[] bundle = resource(e.path("localRepository").asText()).getContentAsByteArray();
            String header = new String(bundle, StandardCharsets.ISO_8859_1);
            header = header.substring(0, header.indexOf("\n\n"));
            assertThat(header).matches("(?s)# v[23] git bundle\n.*").contains(commit + " refs/heads/" + branch);
            assertThat(header).containsPattern("[0-9a-f]{40} refs/heads/main").containsPattern("[0-9a-f]{40} refs/heads/release/legacy");

            String receiptsPath = e.path("sbom").path("evidence").asText();
            assertThat(e.path("provenance").path("receipts").asText()).isEqualTo(receiptsPath);
            JsonNode receipts = JSON.readTree(resource(receiptsPath).getContentAsByteArray());
            assertThat(receipts).isNotEmpty().allSatisfy(r -> {
                assertThat(r.path("exit").asInt()).isZero();
                assertThat(r.path("commit").asText()).isEqualTo(commit);
                assertThat(r.path("branch").asText()).isEqualTo(branch);
                Instant.parse(r.path("startedAt").asText());
            });
            assertThat(receipts.findValuesAsText("command")).anyMatch(c -> c.contains(e.path("projects").get(0).path("packageManager").asText().equals("uv") ? "uv sync --frozen" : "clean package"));

            assertThat(run.path("entries")).anySatisfy(r -> {
                assertThat(r.path("name").asText()).isEqualTo(e.path("name").asText());
                assertThat(r.path("branch").asText()).isEqualTo(branch);
                assertThat(r.path("commit").asText()).isEqualTo(commit);
            });
            assertThat(run.path("bundles").path(e.path("name").asText()).path("heads").path("refs/heads/" + branch).asText()).isEqualTo(commit);
        }
    }

    static void mavenNodes(JsonNode node, int depth, String parent, List<String> out) {
        for (JsonNode c : node.path("children")) {
            String coord = c.path("groupId").asText() + ":" + c.path("artifactId").asText() + ":" + c.path("version").asText();
            out.add(String.join("|", String.valueOf(depth), parent, c.path("groupId").asText(), c.path("artifactId").asText(), c.path("version").asText(),
                    c.path("type").asText(), c.path("scope").asText(), c.path("optional").asText(), depth == 1 ? "direct" : "transitive"));
            mavenNodes(c, depth + 1, coord, out);
        }
    }

    static List<JsonNode> added() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        sample().forEach(e -> {
            if (APP_IDS.containsKey(e.path("name").asText())) out.add(e);
        });
        assertThat(out).hasSize(4);
        return out;
    }

    static List<JsonNode> rows(JsonNode e, String projectPath) {
        List<JsonNode> out = new ArrayList<>();
        e.path("dependencies").forEach(d -> {
            if (d.path("projectPath").asText().equals(projectPath)) out.add(d);
        });
        return out;
    }

    static JsonNode sample() throws Exception {
        return JSON.readTree(ImportService.sample(ImportService.SAMPLE));
    }

    static ClassPathResource resource(String relativeToSamples) {
        return new ClassPathResource("samples/" + relativeToSamples);
    }

    static String text(String relativeToEvidence) throws Exception {
        return new String(new ClassPathResource(EVIDENCE + relativeToEvidence).getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    static String key(JsonNode e) {
        return e.path("name").asText() + " " + e.path("branch").asText();
    }

    static String norm(String name) {
        return name.replaceAll("[-_.]+", "-").toLowerCase(java.util.Locale.ROOT);
    }

    static String sha256(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
