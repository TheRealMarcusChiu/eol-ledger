package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON root-array dependency inventory upload: inline per-branch inventories with graph context. */
class JsonImportTest extends WebTestBase {

    @Autowired Ledger ledger;

    static final ObjectMapper JSON = new ObjectMapper();
    static final String REPO = "https://example.invalid/eol-demo/enterprise-suite";

    static String dep(String name, String version, String purl, String project, String scope, String type, int depth, String parent) {
        return """
                {"name":"%s","version":"%s","purl":"%s","type":"jar","scope":"%s","dependencyType":"%s","optional":false,"depth":%d,
                 "parentCoordinate":"%s","projectCoordinate":"com.example:%s:1.0","ecosystem":"Maven","packageManager":"Maven","projectPath":"%s",
                 "recordSource":"resolved-dependency-tree","verification":{"status":"resolved","source":"evidence/%s-tree.txt","context":"%s"}}"""
                .formatted(name, version, purl, scope, type, depth, parent, project, project, project, scope);
    }

    static String entry(String branch, String generatedAt, String... deps) {
        return """
                {"schemaVersion":1,"name":"enterprise-suite","appId":"DEMO-1001","url":"%s","provider":"other","branch":"%s","demo":true,
                 "localRepository":"sources/enterprise-suite.bundle",
                 "sbom":{"url":null,"format":"inline-dependency-inventory","generatedAt":"%s","evidence":"evidence/summary.json"},
                 "projects":[{"path":"backend-maven","ecosystem":"Maven","packageManager":"Maven","coordinate":"com.example:backend-maven:1.0",
                              "resolution":{"status":"resolved","command":"mvn -B dependency:tree","toolVersion":"Apache Maven 3.9.9","tree":"evidence/backend-maven-tree.txt"}},
                             {"path":"worker-gradle","ecosystem":"Maven","packageManager":"Gradle","coordinate":"com.example:worker-gradle:1.0"}],
                 "dependencies":[%s]}""".formatted(REPO, branch, generatedAt, String.join(",", deps));
    }

    static final String GUAVA_BACKEND = dep("com.google.guava:guava", "33.0.0-jre", "pkg:maven/com.google.guava/guava@33.0.0-jre",
            "backend-maven", "compile", "direct", 1, "com.example:backend-maven:1.0");
    static final String GUAVA_WORKER = dep("com.google.guava:guava", "33.0.0-jre", "pkg:maven/com.google.guava/guava@33.0.0-jre",
            "worker-gradle", "runtimeClasspath", "transitive", 2, "org.acme:lib:2.0");
    static final String REACT = """
            {"name":"react","version":"18.2.0","purl":"pkg:npm/react@18.2.0","ecosystem":"npm","packageManager":"npm","scope":"prod",
             "dependencyType":"direct","depth":1,"projectPath":"backend-maven"}""";

    static String array(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    int count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    List<String> payloads() {
        return db.sql("SELECT payload FROM snapshot_inventory ORDER BY snapshot_id").query(String.class).list();
    }

    @Test
    void rootArrayCreatesInlineBranchesWithFlattenedPackagesAndFullGraphPayload() throws Exception {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE));
        catalog.sync();
        ImportService.Result r = imports.importJson(array(
                entry("main", "2026-11-20T10:00:00Z", GUAVA_BACKEND, GUAVA_WORKER, REACT),
                entry("release/legacy", "2026-11-20T10:00:00Z", REACT)), "test");

        assertThat(r.rows()).isEqualTo(2);
        assertThat(r.newSnapshots()).isEqualTo(2);
        assertThat(r.message()).isEqualTo("2 code-repos imported");
        long main = branchId(REPO, "main");
        assertThat(db.sql("SELECT sbom_url FROM repo_branch WHERE id = ?").param(main).query(String.class).single()).isEqualTo(ImportService.INLINE);
        Ledger.Branch b = ledger.branch(main);
        // guava appears in two graph contexts but is one inventory package identity
        assertThat(ledger.deps(b.snapshotId())).extracting(d -> d.name() + "@" + d.version())
                .containsExactlyInAnyOrder("com.google.guava:guava@33.0.0-jre", "react@18.2.0");
        JsonNode payload = JSON.readTree(db.sql("SELECT payload FROM snapshot_inventory WHERE snapshot_id = ?").param(b.snapshotId()).query(String.class).single());
        assertThat(payload.path("appId").asText()).isEqualTo("DEMO-1001");
        assertThat(payload.path("dependencies")).hasSize(3);
        assertThat(payload.path("dependencies").findValuesAsText("scope")).containsExactlyInAnyOrder("compile", "runtimeClasspath", "prod");
        assertThat(payload.path("dependencies").findValuesAsText("parentCoordinate")).contains("org.acme:lib:2.0");
        assertThat(payload.path("projects").findValuesAsText("path")).containsExactlyInAnyOrder("backend-maven", "worker-gradle");
        assertThat(payload.path("sbom").path("url").isNull()).isTrue();
        // inline packages match the catalogue like SBOM packages do
        assertThat(ledger.deps(b.snapshotId())).filteredOn(d -> d.name().equals("react")).extracting(d -> d.cycle().name()).containsExactly("18");
    }

    @Test
    void reorderedKeysAndArraysOrNewGeneratedAtAddNoSnapshot() throws Exception {
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", GUAVA_BACKEND, GUAVA_WORKER, REACT)), "test");
        String original = payloads().get(0);
        JsonNode reordered = reverse(JSON.readTree(array(entry("main", "2026-11-21T09:30:00Z", GUAVA_BACKEND, GUAVA_WORKER, REACT))));
        CLOCK.now = CLOCK.now.plusSeconds(60);

        ImportService.Result r = imports.importJson(JSON.writeValueAsString(reordered), "test");

        assertThat(r.newSnapshots()).isZero();
        assertThat(count("snapshot")).isEqualTo(1);
        // generatedAt is when this inventory was first observed; a regenerated identical inventory keeps it
        assertThat(payloads()).containsExactly(original);
        assertThat(JSON.readTree(original).path("sbom").path("generatedAt").asText()).isEqualTo("2026-11-20T10:00:00Z");
    }

    @Test
    void graphContextOnlyChangeIsANewSnapshotWithTheSamePackages() {
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", GUAVA_BACKEND, GUAVA_WORKER)), "test");
        CLOCK.now = CLOCK.now.plusSeconds(60);
        String rescoped = GUAVA_WORKER.replace("\"scope\":\"runtimeClasspath\"", "\"scope\":\"compileClasspath\"");

        assertThat(imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", GUAVA_BACKEND, rescoped)), "test").newSnapshots()).isEqualTo(1);

        List<Ledger.Snap> t = ledger.timeline(branchId(REPO, "main"));
        assertThat(t).hasSize(2);
        assertThat(t.get(1).diff().empty()).isTrue();
        assertThat(t).extracting(Ledger.Snap::depCount).containsExactly(1, 1);
        assertThat(payloads().get(1)).contains("\"scope\":\"compileClasspath\"").doesNotContain("\"scope\":\"runtimeClasspath\"");
    }

    @Test
    void flatteningMergesOnlyIdentitiesThePurlSpecTreatsAsEqual() {
        String deps = """
                {"name":"Django","version":"4.2.1","purl":"pkg:pypi/Django@4.2.1","projectPath":"backend-maven"},
                {"name":"django","version":"4.2.1","purl":"pkg:pypi/django@4.2.1","projectPath":"worker-gradle"},
                {"name":"Org.Acme:x","version":"1.0","purl":"pkg:maven/Org.Acme/x@1.0"},
                {"name":"org.acme:x","version":"1.0","purl":"pkg:maven/org.acme/x@1.0"},
                {"name":"django","version":"5.0","purl":"pkg:pypi/django@5.0"}""";

        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", deps)), "test");

        // pypi names are case-insensitive (one package); Maven coordinates are case-sensitive (two packages)
        assertThat(db.sql("SELECT purl FROM dependency ORDER BY purl").query(String.class).list())
                .containsExactly("pkg:maven/Org.Acme/x@1.0", "pkg:maven/org.acme/x@1.0", "pkg:pypi/Django@4.2.1", "pkg:pypi/django@5.0");
        assertThat(ledger.inline(ledger.branch(branchId(REPO, "main")).snapshotId()).contexts())
                .containsEntry("pkg:pypi/django\n4.2.1", "backend-maven; worker-gradle");
    }

    @Test
    void purlFreeRowsWithTheSameNameAndVersionFromDifferentEcosystemsRejectTheFile() {
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT)), "test");
        CLOCK.now = CLOCK.now.plusSeconds(60);
        String ambiguous = """
                {"name":"requests","version":"2.31.0","ecosystem":"npm","packageManager":"npm","projectPath":"backend-maven"},
                {"name":"requests","version":"2.31.0","ecosystem":"Python","packageManager":"pip","projectPath":"worker-gradle"}""";

        assertThatThrownBy(() -> imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", ambiguous)), "test"))
                .isInstanceOf(ImportService.ImportFailedException.class).hasMessageContaining("add a purl");

        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(db.sql("SELECT name FROM dependency").query(String.class).list()).containsExactly("react");
    }

    @Test
    void purlFreeRowsOfOnePackageInSeveralModulesStillListOnce() {
        String repeated = """
                {"name":"requests","version":"2.31.0","ecosystem":"Python","packageManager":"pip","projectPath":"backend-maven","scope":"main"},
                {"name":"requests","version":"2.31.0","ecosystem":"Python","packageManager":"pip","projectPath":"worker-gradle","scope":"main"}""";

        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", repeated)), "test");

        assertThat(db.sql("SELECT name FROM dependency").query(String.class).list()).containsExactly("requests");
        assertThat(ledger.inline(ledger.branch(branchId(REPO, "main")).snapshotId()).contexts())
                .containsEntry("requests\n2.31.0", "backend-maven: main; worker-gradle: main");
    }

    @Test
    void appIdOrProjectMetadataChangeIsANewSnapshot() {
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT)), "test");
        CLOCK.now = CLOCK.now.plusSeconds(60);

        assertThat(imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT).replace("DEMO-1001", "DEMO-2002")), "test").newSnapshots()).isEqualTo(1);
        assertThat(imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT).replace("DEMO-1001", "DEMO-2002")
                .replace("Apache Maven 3.9.9", "Apache Maven 3.9.10")), "test").newSnapshots()).isEqualTo(1);
    }

    @Test
    void codeReposShowTheLatestSnapshotsAppIdPerBranch() throws Exception {
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT), entry("release/legacy", "2026-11-20T10:00:00Z", REACT)), "test");
        CLOCK.now = CLOCK.now.plusSeconds(60);
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT).replace("DEMO-1001", "DEMO-2002")), "test");
        long main = branchId(REPO, "main"), legacy = branchId(REPO, "release/legacy");
        assertThat(ledger.appId(ledger.branch(main).snapshotId())).isEqualTo("DEMO-2002");
        assertThat(ledger.appId(ledger.branch(legacy).snapshotId())).isEqualTo("DEMO-1001");
        assertThat(ledger.appId(null)).isNull();
        String html = mvc.perform(get("/repos")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(appIdCell(html, main)).isEqualTo("DEMO-2002");
        assertThat(appIdCell(html, legacy)).isEqualTo("DEMO-1001");

    }

    static String appIdCell(String html, long branchId) {
        int row = html.indexOf("data-href=\"/repos/" + branchId + "\"");
        int cell = html.indexOf("data-column=\"appid\">", row) + "data-column=\"appid\">".length();
        return html.substring(cell, html.indexOf("</td>", cell));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "root is not an array|{}|root must be a JSON array",
            "empty array|[]|no entries",
            "not JSON|[{|not valid JSON",
            "duplicate key|[{\"name\":\"a\",\"name\":\"b\"}]|not valid JSON",
            "missing branch|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"dependencies\":[]}]|branch is required",
            "missing dependencies|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\"}]|\"dependencies\" array",
            "credentials in url|[{\"name\":\"x\",\"url\":\"https://u:p@github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[]}]|url must be an http(s) URL",
            "unsafe sbom url|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"sbom\":{\"url\":\"javascript:alert(1)\"},\"dependencies\":[]}]|sbom.url",
            "bad generatedAt|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"sbom\":{\"generatedAt\":\"yesterday\"},\"dependencies\":[]}]|generatedAt",
            "unknown provider|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"provider\":\"svn\",\"dependencies\":[]}]|provider must be one of",
            "dependency without version|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"react\"}]}]|dependencies[0] has no \"version\"",
            "purl version conflict|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"react\",\"version\":\"18.2.0\",\"purl\":\"pkg:npm/react@17.0.2\"}]}]|conflicts with purl version",
            "malformed purl|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"react\",\"version\":\"1\",\"purl\":\"npm/react@1\"}]}]|malformed purl",
            "purl type vs ecosystem|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"react\",\"version\":\"1\",\"purl\":\"pkg:pypi/react@1\",\"ecosystem\":\"npm\"}]}]|purl type",
            "fractional depth|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"a\",\"version\":\"1\",\"depth\":1.5}]}]|depth",
            "direct at depth 2|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"a\",\"version\":\"1\",\"dependencyType\":\"direct\",\"depth\":2}]}]|direct dependencies have depth 1",
            "string optional flag|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"a\",\"version\":\"1\",\"optional\":\"no\"}]}]|optional",
            "numeric scope|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[{\"name\":\"a\",\"version\":\"1\",\"scope\":3}]}]|\"scope\" must be a string",
            "undeclared projectPath|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"projects\":[{\"path\":\"api\"}],\"dependencies\":[{\"name\":\"a\",\"version\":\"1\",\"projectPath\":\"web\"}]}]|not a declared project path",
            "duplicate project path|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"projects\":[{\"path\":\"api\"},{\"path\":\"api\"}],\"dependencies\":[]}]|duplicate project path",
            "duplicate url and branch|[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"branch\":\"main\",\"dependencies\":[]},{\"name\":\"y\",\"url\":\"https://GitHub.com/acme/x.git\",\"branch\":\"main\",\"dependencies\":[]}]|duplicate url + branch",
    })
    void invalidUploadRejectsEverythingAndKeepsTheGoodInventory(String label, String json, String expected) {
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT)), "test");
        CLOCK.now = CLOCK.now.plusSeconds(60);
        // a valid change for the existing branch in the same file must not be applied either
        String withGoodEntry = json.startsWith("[{") ? "[" + entry("main", "2026-11-20T10:00:00Z", GUAVA_BACKEND) + "," + json.substring(1) : json;

        assertThatThrownBy(() -> imports.importJson(withGoodEntry, "test"))
                .isInstanceOf(ImportService.ImportFailedException.class).hasMessageContaining(expected);

        assertThat(count("repo_branch")).isEqualTo(1);
        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(db.sql("SELECT name FROM dependency").query(String.class).list()).containsExactly("react");
        assertThat(db.sql("SELECT ok FROM import_run ORDER BY id DESC LIMIT 1").query(Boolean.class).single()).isFalse();
    }

    @ParameterizedTest(name = "trailing {0}")
    @CsvSource(delimiter = '|', value = {"empty array|[]", "object|{\"name\":\"x\"}", "garbage|xyz"})
    void trailingDataAfterTheRootArrayRejectsEverything(String label, String trailing) {
        imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", REACT)), "test");
        CLOCK.now = CLOCK.now.plusSeconds(60);

        assertThatThrownBy(() -> imports.importJson(array(entry("main", "2026-11-20T10:00:00Z", GUAVA_BACKEND)) + "\n" + trailing, "test"))
                .isInstanceOf(ImportService.ImportFailedException.class).hasMessageContaining("not valid JSON");

        assertThat(count("snapshot")).isEqualTo(1);
        assertThat(db.sql("SELECT name FROM dependency").query(String.class).list()).containsExactly("react");
        assertThat(db.sql("SELECT ok FROM import_run ORDER BY id DESC LIMIT 1").query(Boolean.class).single()).isFalse();
    }

    @Test
    void referencedUrlsAreProvenanceAndNeverFetched() {
        String b = TestSupport.base();
        String e = entry("main", "2026-11-20T10:00:00Z", REACT.replace("\"depth\":1", "\"depth\":1,\"verification\":{\"source\":\"" + b + "/evidence.txt\"}"))
                .replace("\"url\":null", "\"url\":\"https://127.0.0.1/declared.cdx.json\"");

        imports.importJson(array(e), "test");

        assertThat(TestSupport.AUTH_HEADERS).isEmpty();
        assertThat(db.sql("SELECT version FROM dependency").query(String.class).list()).containsExactly("18.2.0");
        assertThat(payloads().get(0)).contains("https://127.0.0.1/declared.cdx.json");
        assertThat(db.sql("SELECT sbom_url FROM snapshot").query(String.class).single()).isEqualTo(ImportService.INLINE);
    }

    @Test
    void uploadPageAcceptsJsonAndPagesShowInlineMetadataAndContext() throws Exception {
        seed();
        mvc.perform(get("/imports")).andExpect(status().isOk())
                .andExpect(content().string(containsString("accept=\".json,application/json\"")))
                .andExpect(content().string(containsString("demo-dependency-inventory.json")));
        String body = array(entry("main", "2026-11-20T10:00:00Z", GUAVA_BACKEND, GUAVA_WORKER, REACT));

        mvc.perform(multipart("/imports").file(new MockMultipartFile("file", "deps.json", "application/json", body.getBytes(StandardCharsets.UTF_8)))
                .with(csrf())).andExpect(status().isSeeOther())
                .andExpect(flash().attribute("message", "1 code-repos imported"));

        long id = branchId(REPO, "main");
        mvc.perform(get("/repos/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("DEMO-1001")))
                .andExpect(content().string(containsString("worker-gradle")))
                .andExpect(content().string(containsString("Gradle")))
                .andExpect(content().string(containsString("Inline JSON upload")))
                .andExpect(content().string(containsString("synthetic demo")))
                .andExpect(content().string(containsString("worker-gradle: runtimeClasspath, transitive (depth 2)")))
                .andExpect(content().string(containsString("backend-maven: compile, direct")))
                .andExpect(content().string(not(containsString("inline:json-upload"))));
        mvc.perform(get("/repos/" + id + "/history")).andExpect(status().isOk())
                .andExpect(content().string(containsString("DEMO-1001")))
                .andExpect(content().string(containsString("Inline JSON upload")));
    }

    @Test
    void rejectedJsonUploadShowsErrorsThroughTheSamePage() throws Exception {
        mvc.perform(multipart("/imports").file(new MockMultipartFile("file", "deps.json", "application/json", "[{\"name\":\"x\",\"url\":\"https://github.com/acme/x\",\"dependencies\":[]}]".getBytes(StandardCharsets.UTF_8)))
                .with(csrf())).andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Import rejected")))
                .andExpect(content().string(containsString("branch is required")));
        assertThat(count("repo_branch")).isZero();
    }

    /** The downloadable sample is the real resolver-derived demo; every expectation below is computed from its own JSON. */
    @Test
    void realResolvedDemoSampleDownloadsAndImportsIdempotentlyWithGraphContextsKept() throws Exception {
        byte[] body = mvc.perform(get("/imports/samples/demo-dependency-inventory.json")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(ImportService.sample("demo-dependency-inventory.json").getBytes(StandardCharsets.UTF_8));
        JsonNode sample = JSON.readTree(body);
        assertThat(sample).hasSize(4);
        List<String> urls = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        sample.forEach(e -> {
            urls.add(e.path("url").asText());
            branches.add(e.path("branch").asText());
        });
        assertThat(urls.stream().distinct()).hasSize(2);
        assertThat(branches.stream().distinct()).containsExactlyInAnyOrder("main", "release/legacy");
        List<String> managers = new ArrayList<>();
        sample.forEach(e -> e.path("projects").forEach(p -> managers.add(p.path("packageManager").asText())));
        assertThat(managers.stream().distinct()).containsExactlyInAnyOrder("Maven", "Gradle", "NuGet", "pip", "pip3", "uv", "npm", "Yarn");
        assertThat(sample.findValuesAsText("recordSource")).containsOnly("resolved-dependency-tree");

        mvc.perform(multipart("/imports").file(new MockMultipartFile("file", "demo.json", "application/json", body)).with(csrf()))
                .andExpect(status().isSeeOther())
                .andExpect(flash().attribute("message", "4 code-repos imported"));
        assertThat(count("snapshot")).isEqualTo(4);
        ImportService.Result repeated = imports.importJson(new String(body, StandardCharsets.UTF_8), "reupload");
        assertThat(repeated.rows()).isEqualTo(4);
        assertThat(repeated.newSnapshots()).isZero();
        assertThat(repeated.message()).isEqualTo("4 code-repos imported");
        assertThat(db.sql("SELECT message FROM import_run ORDER BY id").query(String.class).list())
                .containsExactly("4 code-repos imported", "4 code-repos imported");
        assertThat(count("snapshot")).isEqualTo(4);

        boolean sawMultiModulePackage = false;
        java.util.Map<String, java.util.Set<String>> inventories = new java.util.HashMap<>();
        for (JsonNode e : sample) {
            long snap = ledger.branch(branchId(e.path("url").asText(), e.path("branch").asText())).snapshotId();
            java.util.Map<String, java.util.Set<String>> modulesByPackage = new java.util.TreeMap<>();
            e.path("dependencies").forEach(d -> modulesByPackage.computeIfAbsent(Matcher.normalizePurl(d.path("purl").asText()) + "@" + d.path("version").asText(),
                    k -> new java.util.TreeSet<>()).add(d.path("projectPath").asText()));
            // one inventory row per package identity + version; every raw graph row stays in the payload
            assertThat(ledger.deps(snap)).hasSize(modulesByPackage.size());
            JsonNode stored = JSON.readTree(db.sql("SELECT payload FROM snapshot_inventory WHERE snapshot_id = ?").param(snap).query(String.class).single());
            assertThat(stored.path("dependencies")).hasSize(e.path("dependencies").size());
            assertThat(stored.path("appId").asText()).isEqualTo(e.path("appId").asText());
            for (var m : modulesByPackage.entrySet()) {
                if (m.getValue().size() < 2) continue;
                sawMultiModulePackage = true;
                String identity = m.getKey();
                assertThat(ledger.deps(snap)).filteredOn(d -> (Matcher.normalizePurl(d.purl()) + "@" + d.version()).equals(identity)).hasSize(1);
            }
            inventories.computeIfAbsent(e.path("url").asText(), k -> new java.util.HashSet<>())
                    .add(String.join(",", new java.util.TreeSet<>(modulesByPackage.keySet())));
        }
        assertThat(sawMultiModulePackage).as("the real sample has a package resolved in more than one module").isTrue();
        // main and release/legacy differ in packages for both repositories
        assertThat(inventories.values()).allMatch(s -> s.size() == 2);
    }

    /** Reverses every object's key order and every array, recursively. */
    static JsonNode reverse(JsonNode n) {
        if (n.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            n.forEach(x -> items.add(reverse(x)));
            Collections.reverse(items);
            ArrayNode a = JSON.createArrayNode();
            items.forEach(a::add);
            return a;
        }
        if (n.isObject()) {
            List<String> names = new ArrayList<>();
            n.fieldNames().forEachRemaining(names::add);
            Collections.reverse(names);
            ObjectNode o = JSON.createObjectNode();
            names.forEach(k -> o.set(k, reverse(n.get(k))));
            return o;
        }
        return n;
    }
}
