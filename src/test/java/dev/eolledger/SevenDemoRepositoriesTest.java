package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.eolledger.AdditionalDemoRepositoriesTest.ORIGINAL_FULL;
import static dev.eolledger.AdditionalDemoRepositoriesTest.key;
import static dev.eolledger.AdditionalDemoRepositoriesTest.mavenNodes;
import static dev.eolledger.AdditionalDemoRepositoriesTest.norm;
import static dev.eolledger.AdditionalDemoRepositoriesTest.resource;
import static dev.eolledger.AdditionalDemoRepositoriesTest.rows;
import static dev.eolledger.AdditionalDemoRepositoriesTest.sha256;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seven real Git repositories (DEMO-1005..1011) built and resolved natively by
 * eol-ledger-ops/shareable-views/seven-demo/generate.py. Each maps one pinned package per branch to a distinct
 * endoflife.date product; the catalogue is the pinned copy of the real v1 metadata the generator fetched.
 */
class SevenDemoRepositoriesTest extends WebTestBase {

    @Autowired Ledger ledger;

    static final ObjectMapper JSON = new ObjectMapper();
    static final String EVIDENCE = "seven-demo-evidence/";
    /** SHA-256 of the whole eight-entry sample as shipped by additional-demo (additional-demo-evidence/run.json sampleSha256After). */
    static final String SAMPLE_BEFORE_SHA256 = "4eef317be7602271ef4586d9c9c24a51b207d8e228bd624c5f0ad023510b7e1b";
    static final Map<String, String> APP_IDS = Map.of(
            "checkout-gateway", "DEMO-1005", "storefront-ui", "DEMO-1006", "docs-portal", "DEMO-1007", "admin-console", "DEMO-1008",
            "risk-analytics", "DEMO-1009", "ledger-batch", "DEMO-1010", "inventory-persistence", "DEMO-1011");
    static final Map<String, Set<String>> MODULES = Map.of(
            "checkout-gateway", Set.of("gateway-api", "gateway-auth"),
            "storefront-ui", Set.of("storefront-web", "storefront-format"),
            "docs-portal", Set.of("docs-web", "docs-search"),
            "admin-console", Set.of("console-core", "console-streams"),
            "risk-analytics", Set.of("risk-model", "risk-report"),
            "ledger-batch", Set.of("batch-audit", "batch-import"),
            "inventory-persistence", Set.of("inventory-orm", "inventory-store"));
    /** repository branch -> module, direct package, version, endoflife.date product, cycle. */
    static final Map<String, String> PINS = Map.ofEntries(
            entry("checkout-gateway main", "gateway-api express 5.1.0 express 5"),
            entry("checkout-gateway release/legacy", "gateway-api express 4.21.2 express 4"),
            entry("storefront-ui main", "storefront-web vue 3.5.13 vue 3.5"),
            entry("storefront-ui release/legacy", "storefront-web vue 2.7.16 vue 2.7"),
            entry("docs-portal main", "docs-web next 15.3.3 nextjs 15"),
            entry("docs-portal release/legacy", "docs-web next 14.2.28 nextjs 14"),
            entry("admin-console main", "console-core @angular/core 19.2.14 angular 19"),
            entry("admin-console release/legacy", "console-core @angular/core 17.3.12 angular 17"),
            entry("risk-analytics main", "risk-model numpy 2.2.6 numpy 2.2"),
            entry("risk-analytics release/legacy", "risk-model numpy 1.26.4 numpy 1.26"),
            entry("ledger-batch main", "batch-audit org.apache.logging.log4j:log4j-core 2.24.3 log4j 2"),
            entry("ledger-batch release/legacy", "batch-audit org.apache.logging.log4j:log4j-core 2.12.4 log4j 2.12"),
            entry("inventory-persistence main", "inventory-orm org.hibernate.orm:hibernate-core 6.6.13.Final hibernate-orm 6.6"),
            entry("inventory-persistence release/legacy", "inventory-orm org.hibernate:hibernate-core 5.6.15.Final hibernate-orm 5.6"));
    static final Pattern TREE_LINE = Pattern.compile("^([│├└─ ]*)(\\S+) v(\\S+)");
    static final Pattern LOCK_PATH = Pattern.compile("npm ls path (\\S+);");

    @Test
    void originalEightEntriesAreByteForByteKeptAndFourteenBranchesAreAppended() throws Exception {
        String text = ImportService.sample(ImportService.SAMPLE);
        JsonNode sample = JSON.readTree(text);
        assertThat(sample).hasSize(22);
        // the generator appends after the eighth entry's closing brace; everything before it is the shipped file
        int cut = text.indexOf(",\n  {\n    \"schemaVersion\": 1,\n    \"name\": \"checkout-gateway\"");
        assertThat(cut).isPositive();
        String before = text.substring(0, cut) + "\n]\n";
        assertThat(sha256(before)).isEqualTo(SAMPLE_BEFORE_SHA256);
        JsonNode old = JSON.readTree(before);
        assertThat(old).hasSize(8);
        Map<String, String> hashes = new HashMap<>();
        Set<String> oldUrls = new HashSet<>();
        Set<String> oldApps = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            assertThat(sample.get(i)).isEqualTo(old.get(i));
            hashes.put(key(sample.get(i)), sha256(JSON.writeValueAsString(sample.get(i))));
            oldUrls.add(sample.get(i).path("url").asText());
            oldApps.add(sample.get(i).path("appId").asText());
        }
        assertThat(hashes).hasSize(8).containsAllEntriesOf(ORIGINAL_FULL);
        JsonNode run = evidence("run.json");
        Map<String, String> runHashes = new HashMap<>();
        run.path("originalEntrySha256").fields().forEachRemaining(f -> runHashes.put(f.getKey(), f.getValue().asText()));
        // Python's json.dumps and Jackson print some numbers differently, so the generator's hashes are its own pins, not Jackson's
        assertThat(runHashes.keySet()).isEqualTo(hashes.keySet());
        assertThat(runHashes.values()).allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(run.path("originalEntrySha256AfterAppend")).isEqualTo(run.path("originalEntrySha256"));
        assertThat(run.path("sampleSha256Before").asText()).isEqualTo(SAMPLE_BEFORE_SHA256);

        List<String> added = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        Set<String> apps = new HashSet<>();
        for (int i = 8; i < 22; i++) {
            JsonNode e = sample.get(i);
            String name = e.path("name").asText();
            added.add(key(e));
            assertThat(e.path("appId").asText()).isEqualTo(APP_IDS.get(name)).isNotIn(oldApps);
            assertThat(e.path("url").asText()).isEqualTo("https://example.invalid/eol-demo/" + name).isNotIn(oldUrls);
            urls.add(e.path("url").asText());
            apps.add(e.path("appId").asText());
            assertThat(e.path("provider").asText()).isEqualTo("other");
            assertThat(e.path("demo").asBoolean()).isTrue();
            assertThat(e.path("sbom").path("url").isNull()).isTrue();
            assertThat(e.path("sbom").path("format").asText()).isEqualTo("inline-dependency-inventory");
            Instant.parse(e.path("sbom").path("generatedAt").asText());
            List<String> paths = new ArrayList<>();
            e.path("projects").forEach(p -> paths.add(p.path("path").asText()));
            assertThat(paths).containsExactlyInAnyOrderElementsOf(MODULES.get(name));
            String[] pin = PINS.get(key(e)).split(" ");
            assertThat(e.path("dependencies")).anySatisfy(d -> {
                assertThat(d.path("projectPath").asText()).isEqualTo(pin[0]);
                assertThat(d.path("name").asText()).isEqualTo(pin[1]);
                assertThat(d.path("version").asText()).isEqualTo(pin[2]);
                assertThat(d.path("dependencyType").asText()).isEqualTo("direct");
            });
        }
        assertThat(added).containsExactlyInAnyOrderElementsOf(PINS.keySet());
        assertThat(urls).hasSize(7);
        assertThat(apps).hasSize(7);
        assertThat(InventoryJson.parse(text)).hasSize(22);
    }

    @Test
    void rowsAreExactlyTheNativeResolverTreesAndLocks() throws Exception {
        Map<String, Integer> checked = new HashMap<>();
        for (JsonNode e : added()) {
            for (JsonNode p : e.path("projects")) {
                String manager = p.path("packageManager").asText();
                checked.merge(manager, 1, Integer::sum);
                assertThat(p.path("resolution").path("status").asText()).isEqualTo("resolved");
                String treePath = p.path("resolution").path("tree").asText();
                assertThat(treePath).startsWith(EVIDENCE + e.path("name").asText() + "/");
                String dir = treePath.substring(0, treePath.lastIndexOf('/') + 1);
                String project = p.path("coordinate").asText();
                List<String> expected = new ArrayList<>();
                List<String> actual = new ArrayList<>();
                List<JsonNode> rows = rows(e, p.path("path").asText());
                switch (manager) {
                    case "Maven" -> {
                        assertThat(resource(dir + "pom.xml").exists()).isTrue();
                        assertThat(resource(dir + "tree.txt").exists()).isTrue();
                        JsonNode tree = JSON.readTree(resource(treePath).getContentAsByteArray());
                        assertThat(project).isEqualTo(tree.path("groupId").asText() + ":" + tree.path("artifactId").asText() + ":" + tree.path("version").asText());
                        mavenNodes(tree, 1, project, expected);
                        for (JsonNode d : rows) {
                            assertThat(d.path("purl").asText()).isEqualTo("pkg:maven/" + d.path("groupId").asText() + "/" + d.path("artifactId").asText() + "@" + d.path("version").asText());
                            assertThat(d.path("verification").path("source").asText()).isEqualTo(treePath);
                            actual.add(String.join("|", d.path("depth").asText(), d.path("parentCoordinate").asText(), d.path("groupId").asText(),
                                    d.path("artifactId").asText(), d.path("version").asText(), d.path("type").asText(), d.path("scope").asText(),
                                    d.path("optional").asText(), d.path("dependencyType").asText()));
                        }
                    }
                    case "uv" -> {
                        assertThat(resource(dir + "pyproject.toml").exists()).isTrue();
                        assertThat(resource(dir + "uv-tree-no-dedupe.txt").exists()).isTrue();
                        List<String> stack = new ArrayList<>();
                        for (String line : text(treePath).split("\n")) {
                            var m = TREE_LINE.matcher(line);
                            if (!m.find()) continue;
                            int depth = m.group(1).length() / 4;
                            String node = norm(m.group(2).replaceAll("\\[.*]$", "")) + "==" + m.group(3);
                            while (stack.size() > depth) stack.remove(stack.size() - 1);
                            stack.add(node);
                            if (depth == 0) assertThat(node).isEqualTo(project);
                            else expected.add(depth + "|" + stack.get(depth - 1) + "|" + node);
                        }
                        Set<String> locked = new HashSet<>();
                        for (String pkg : text(dir + "uv.lock").split("\\[\\[package]]")) {
                            var name = Pattern.compile("(?m)^name = \"([^\"]+)\"").matcher(pkg);
                            var version = Pattern.compile("(?m)^version = \"([^\"]+)\"").matcher(pkg);
                            if (!name.find() || !version.find() || pkg.contains("source = { virtual = \".\" }")) continue;
                            locked.add(name.group(1) + "==" + version.group(1));
                        }
                        Set<String> covered = new HashSet<>();
                        for (JsonNode d : rows) {
                            assertThat(d.path("purl").asText()).isEqualTo("pkg:pypi/" + d.path("name").asText() + "@" + d.path("version").asText());
                            assertThat(d.path("verification").path("source").asText()).isEqualTo(dir + "uv.lock");
                            actual.add(d.path("depth").asText() + "|" + d.path("parentCoordinate").asText() + "|" + norm(d.path("name").asText()) + "==" + d.path("version").asText());
                            covered.add(d.path("name").asText() + "==" + d.path("version").asText());
                        }
                        assertThat(covered).as("every locked package of " + key(e) + " " + p.path("path").asText()).isEqualTo(locked);
                    }
                    case "npm" -> {
                        assertThat(resource(dir + "package.json").exists()).isTrue();
                        JsonNode tree = JSON.readTree(resource(treePath).getContentAsByteArray());
                        JsonNode lock = JSON.readTree(resource(dir + "package-lock.json").getContentAsByteArray()).path("packages");
                        assertThat(project).isEqualTo(tree.path("name").asText() + "@" + tree.path("version").asText());
                        npmNodes(tree, 1, project, Set.of(project), expected);
                        for (JsonNode d : rows) {
                            String name = d.path("name").asText();
                            String version = d.path("version").asText();
                            assertThat(d.path("purl").asText()).isEqualTo("pkg:npm/" + name.replace("@", "%40") + "@" + version);
                            assertThat(d.path("verification").path("source").asText()).isEqualTo(treePath);
                            var m = LOCK_PATH.matcher(d.path("verification").path("context").asText());
                            assertThat(m.find()).isTrue();
                            assertThat(lock.path(m.group(1)).path("version").asText()).as(name + " in package-lock.json").isEqualTo(version);
                            actual.add(String.join("|", d.path("depth").asText(), d.path("parentCoordinate").asText(), name, version,
                                    d.path("optional").asText(), d.path("dependencyType").asText()));
                        }
                    }
                    default -> throw new AssertionError("unexpected package manager " + manager);
                }
                assertThat(expected).isNotEmpty();
                for (JsonNode d : rows) {
                    assertThat(d.path("projectCoordinate").asText()).isEqualTo(project);
                    assertThat(d.path("recordSource").asText()).isEqualTo("resolved-dependency-tree");
                    assertThat(d.path("dependencyType").asText()).isEqualTo(d.path("depth").asInt() == 1 ? "direct" : "transitive");
                }
                assertThat(actual).as(key(e) + " " + p.path("path").asText()).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
        assertThat(checked).isEqualTo(Map.of("npm", 16, "uv", 4, "Maven", 8));
    }

    @Test
    void branchCommitsMatchBundleHeadsReceiptsAndRun() throws Exception {
        JsonNode run = evidence("run.json");
        for (JsonNode e : added()) {
            String commit = e.path("provenance").path("commit").asText();
            String branch = e.path("branch").asText();
            assertThat(commit).matches("[0-9a-f]{40}");
            assertThat(e.path("provenance").path("branch").asText()).isEqualTo(branch);
            assertThat(e.path("localRepository").asText()).isEqualTo(EVIDENCE + "sources/" + e.path("name").asText() + ".bundle");
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
            String resolve = Map.of("uv", "uv sync --frozen", "Maven", "clean package", "npm", "npm ci")
                    .get(e.path("projects").get(0).path("packageManager").asText());
            assertThat(receipts.findValuesAsText("command")).anyMatch(c -> c.contains(resolve));

            assertThat(run.path("entries")).anySatisfy(r -> {
                assertThat(r.path("name").asText()).isEqualTo(e.path("name").asText());
                assertThat(r.path("branch").asText()).isEqualTo(branch);
                assertThat(r.path("commit").asText()).isEqualTo(commit);
            });
            assertThat(run.path("bundles").path(e.path("name").asText()).path("heads").path("refs/heads/" + branch).asText()).isEqualTo(commit);
        }
    }

    /** The pinned endoflife.date metadata goes through the real CatalogService and import; every pin lands in its own cycle. */
    @Test
    void everyPinMatchesItsOwnCatalogueProductAndCycle() throws Exception {
        String products = text(EVIDENCE + "catalog/products-full.json");
        Map<String, JsonNode> byName = new HashMap<>();
        JSON.readTree(products).path("result").forEach(r -> byName.put(r.path("name").asText(), r));
        Set<String> expectedProducts = new HashSet<>();
        PINS.values().forEach(v -> expectedProducts.add(v.split(" ")[3]));
        assertThat(expectedProducts).hasSize(7).doesNotContain("spring-framework", "django");
        assertThat(byName.keySet()).isEqualTo(expectedProducts);
        for (String product : expectedProducts) {
            assertThat(JSON.readTree(resource(EVIDENCE + "catalog/" + product + ".json").getContentAsByteArray()).path("result"))
                    .as("fetched " + product).isEqualTo(byName.get(product));
        }
        assertThat(evidence("run.json").path("catalogMapping")).hasSize(14).allSatisfy(m -> {
            String[] pin = PINS.get(m.path("repository").asText() + " " + m.path("branch").asText()).split(" ");
            assertThat(m.path("package").asText() + " " + m.path("version").asText() + " " + m.path("product").asText() + " " + m.path("cycle").asText())
                    .isEqualTo(pin[1] + " " + pin[2] + " " + pin[3] + " " + pin[4]);
        });

        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(products));
        assertThat(catalog.sync().productsSynced()).isEqualTo(7);
        String body = ImportService.sample(ImportService.SAMPLE);
        assertThat(imports.importJson(body, "test").newSnapshots()).isEqualTo(22);
        assertThat(imports.importJson(body, "again").newSnapshots()).isZero();

        Set<String> matched = new HashSet<>();
        for (JsonNode e : added()) {
            String[] pin = PINS.get(key(e)).split(" ");
            String pinPurl = null;
            for (JsonNode d : rows(e, pin[0])) {
                if (d.path("name").asText().equals(pin[1]) && d.path("version").asText().equals(pin[2]) && d.path("depth").asInt() == 1) pinPurl = d.path("purl").asText();
            }
            assertThat(pinPurl).as(key(e)).isNotNull();
            String identity = Matcher.normalizePurl(pinPurl);
            List<Ledger.Dep> deps = ledger.deps(ledger.branch(branchId(e.path("url").asText(), e.path("branch").asText())).snapshotId());
            assertThat(deps).filteredOn(d -> d.cycle() != null).as(key(e)).isNotEmpty().allSatisfy(d -> {
                assertThat(d.match().evidence()).contains("is an endoflife.date identifier of " + pin[3]);
                assertThat(d.cycle().product()).isEqualTo(pin[3]);
            });
            List<Ledger.Dep> pinned = deps.stream().filter(d -> identity.equals(Matcher.normalizePurl(d.purl())) && pin[2].equals(d.version())).toList();
            assertThat(pinned).as(key(e)).hasSize(1);
            Ledger.Cycle cycle = pinned.get(0).cycle();
            assertThat(cycle).as(key(e)).isNotNull();
            assertThat(cycle.key()).isEqualTo(pin[3] + " " + pin[4]);
            JsonNode release = null;
            for (JsonNode r : byName.get(pin[3]).path("releases")) {
                if (r.path("name").asText().equals(pin[4])) release = r;
            }
            assertThat(release).as(pin[3] + " " + pin[4] + " in fetched metadata").isNotNull();
            assertThat(cycle.isEol()).isEqualTo(release.path("isEol").isBoolean() ? release.path("isEol").asBoolean() : null);
            matched.add(cycle.key());
        }
        assertThat(matched).hasSize(14);
    }

    static void npmNodes(JsonNode node, int depth, String parent, Set<String> seen, List<String> out) {
        node.path("dependencies").fields().forEachRemaining(f -> {
            JsonNode c = f.getValue();
            if (c.path("missing").asBoolean()) {
                assertThat(c.path("optional").asBoolean()).as("required dependency " + f.getKey() + " missing under " + parent).isTrue();
                return;
            }
            if (c.isObject() && c.isEmpty()) { // npm ls prints an unmet optional peer/optional dependency as {}
                boolean optionalPeer = node.path("peerDependencies").has(f.getKey())
                        && node.path("peerDependenciesMeta").path(f.getKey()).path("optional").asBoolean();
                assertThat(optionalPeer || node.path("optionalDependencies").has(f.getKey()))
                        .as(f.getKey() + " under " + parent + " is empty but not declared optional").isTrue();
                return;
            }
            assertThat(c.path("version").isTextual()).as(f.getKey() + " under " + parent + " has a version").isTrue();
            String coord = f.getKey() + "@" + c.path("version").asText();
            out.add(String.join("|", String.valueOf(depth), parent, f.getKey(), c.path("version").asText(),
                    String.valueOf(c.path("optional").asBoolean()), depth == 1 ? "direct" : "transitive"));
            if (seen.contains(coord)) return;
            Set<String> next = new HashSet<>(seen);
            next.add(coord);
            npmNodes(c, depth + 1, coord, next, out);
        });
    }

    static List<JsonNode> added() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        AdditionalDemoRepositoriesTest.sample().forEach(e -> {
            if (APP_IDS.containsKey(e.path("name").asText())) out.add(e);
        });
        assertThat(out).hasSize(14);
        return out;
    }

    static JsonNode evidence(String relativeToEvidence) throws Exception {
        return JSON.readTree(resource(EVIDENCE + relativeToEvidence).getContentAsByteArray());
    }

    static String text(String relativeToSamples) throws Exception {
        return new String(resource(relativeToSamples).getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
