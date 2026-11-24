package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /imports/sbom: standard SBOM files (test data below) become one repository branch inventory through the JSON import path. */
class SbomImportTest extends IntegrationTest {

    static final String REPO = "https://gitlab.com/acme/alpha";

    ResultActions upload(String fileName, String body, String branch) throws Exception {
        var req = multipart("/imports/sbom").file(new MockMultipartFile("file", fileName, "application/octet-stream", body.getBytes(StandardCharsets.UTF_8)))
                .param("name", "Alpha").param("url", REPO).param("provider", "gitlab").with(csrf());
        if (branch != null) req.param("branch", branch);
        return mvc.perform(req);
    }

    /** name version purl of the branch's latest snapshot, sorted. */
    List<String> latest() {
        return db.sql("""
                SELECT d.name || ' ' || d.version || ' ' || COALESCE(d.purl, '-') FROM dependency d
                WHERE d.snapshot_id = (SELECT max(s.id) FROM snapshot s JOIN repo_branch b ON b.id = s.branch_id WHERE b.repo_url = ? AND b.branch = 'main')
                ORDER BY 1""").param(REPO).query(String.class).list();
    }

    int count(String table) {
        return db.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    // CycloneDX 1.5 JSON test data: a metadata root (not a dependency), a nested component, a component no
    // dependency graph reaches, a file component, a service and a purl without version.
    static final String CDX_JSON = """
            {"bomFormat":"CycloneDX","specVersion":"1.5","version":1,
             "metadata":{"component":{"type":"application","name":"alpha-app","version":"9.9.9","purl":"pkg:npm/alpha-app@9.9.9"}},
             "components":[
              {"type":"library","bom-ref":"r1","name":"react","version":"18.2.0","purl":"pkg:npm/react@18.2.0",
               "components":[{"type":"library","bom-ref":"r2","group":"org.springframework","name":"spring-core","version":"6.1.0","purl":"pkg:maven/org.springframework/spring-core"}]},
              {"type":"library","bom-ref":"lonely","name":"left-pad","version":"1.3.0"},
              {"type":"file","name":"README.md"}],
             "services":[{"name":"billing-api","version":"2"}],
             "dependencies":[{"ref":"r1","dependsOn":["r2"]}]}""";

    @Test
    void cycloneDxJsonImportsEveryComponentButNotTheRootFilesOrServicesAndIsIdempotent() throws Exception {
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther()).andExpect(redirectedUrl("/imports"))
                .andExpect(flash().attribute("message", "1 code-repos imported"));
        assertThat(latest()).containsExactly(
                "left-pad 1.3.0 -",
                "react 18.2.0 pkg:npm/react@18.2.0",
                "spring-core 6.1.0 pkg:maven/org.springframework/spring-core@6.1.0");
        String payload = db.sql("SELECT payload FROM snapshot_inventory").query(String.class).single();
        assertThat(payload).contains("\"format\":\"CycloneDX 1.5 JSON\"", "\"bomRef\":\"lonely\"", "\"ecosystem\":\"maven\"", "\"groupId\":\"org.springframework\"", "bom.json");
        assertThat(db.sql("SELECT provider FROM repo_branch").query(String.class).single()).isEqualTo("gitlab");
        // a native SBOM upload is never described as an inline JSON upload
        long branch = db.sql("SELECT id FROM repo_branch").query(Long.class).single();
        String page = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/repos/" + branch)).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Inline upload").doesNotContain("Inline JSON upload");
        // the same file again is no change; a changed version is one new snapshot
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther());
        assertThat(count("snapshot")).isEqualTo(1);
        upload("bom.json", CDX_JSON.replace("1.3.0", "1.3.1"), "main").andExpect(status().isSeeOther());
        assertThat(count("snapshot")).isEqualTo(2);
        assertThat(latest()).contains("left-pad 1.3.1 -");
    }

    @Test
    void anyInvalidFileOrMetadataChangesNothingButTheFailedHistoryRow() throws Exception {
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther());
        List<String> before = latest();
        int snapshots = count("snapshot"), deps = count("dependency"), runs = count("import_run");
        String noVersion = CDX_JSON.replace("\"name\":\"left-pad\",\"version\":\"1.3.0\"", "\"name\":\"left-pad\"");
        upload("bom.json", noVersion, "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("components[1] (lonely) left-pad has no version")));
        upload("bom.json", "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\"}", "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("has no &quot;components&quot; array")));
        upload("bom.json", CDX_JSON.replace("\"1.5\"", "\"1.3\""), "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("specVersion 1.3 is not supported")));
        upload("bom.json", "{\"bomFormat\":\"CycloneDX\",\"bomFormat\":\"x\"}", "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("repeats a key")));
        upload("bom.json", CDX_JSON.replace("pkg:npm/react@18.2.0", "pkg:npm/react@17.0.0"), "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("conflicts with its purl version 17.0.0")));
        upload("bom.json", CDX_JSON, null).andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("branch is required")));
        upload("notes.txt", "hello", "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not a supported SBOM")));
        // no CSRF token: refused before anything runs
        mvc.perform(multipart("/imports/sbom").file(new MockMultipartFile("file", "bom.json", "application/json", CDX_JSON.getBytes(StandardCharsets.UTF_8)))
                .param("name", "Alpha").param("url", REPO).param("branch", "main")).andExpect(status().isForbidden());
        assertThat(latest()).isEqualTo(before);
        assertThat(List.of(count("snapshot"), count("dependency"))).containsExactly(snapshots, deps);
        assertThat(count("import_run")).isEqualTo(runs + 7);
        assertThat(db.sql("SELECT count(*) FROM import_run WHERE ok = FALSE AND source = 'sbom bom.json'").query(Integer.class).single()).isEqualTo(6);
    }

    // SPDX 2.3 JSON test data: the described root package is a package too; files and relationships are not packages.
    static final String SPDX_JSON = """
            {"spdxVersion":"SPDX-2.3","dataLicense":"CC0-1.0","SPDXID":"SPDXRef-DOCUMENT","name":"alpha",
             "documentDescribes":["SPDXRef-app"],
             "packages":[
              {"SPDXID":"SPDXRef-app","name":"alpha-app","versionInfo":"9.9.9"},
              {"SPDXID":"SPDXRef-django","name":"Django","versionInfo":"4.2.1",
               "externalRefs":[{"referenceCategory":"PACKAGE-MANAGER","referenceType":"purl","referenceLocator":"pkg:pypi/django@4.2.1"}]},
              {"SPDXID":"SPDXRef-odd","name":"odd","versionInfo":"1.0+build.5",
               "externalRefs":[{"referenceCategory":"PACKAGE-MANAGER","referenceType":"purl","referenceLocator":"pkg:golang/example.com/odd?arch=x86#sub/dir"}]}],
             "files":[{"SPDXID":"SPDXRef-f","fileName":"./setup.py"}],
             "relationships":[{"spdxElementId":"SPDXRef-app","relationshipType":"DEPENDS_ON","relatedSpdxElement":"SPDXRef-django"}]}""";

    @Test
    void spdxJsonImportsEveryPackageIncludingTheRootAndCompletesVersionlessPurls() throws Exception {
        upload("alpha.spdx.json", SPDX_JSON, "main").andExpect(status().isSeeOther());
        assertThat(latest()).containsExactly(
                "Django 4.2.1 pkg:pypi/django@4.2.1",
                "alpha-app 9.9.9 -",
                "odd 1.0+build.5 pkg:golang/example.com/odd@1.0%2Bbuild.5?arch=x86#sub/dir");
        assertThat(db.sql("SELECT payload FROM snapshot_inventory").query(String.class).single())
                .contains("\"format\":\"SPDX-2.3 JSON\"", "\"bomRef\":\"SPDXRef-django\"", "\"ecosystem\":\"python\"");
        upload("x.spdx.json", SPDX_JSON.replace("SPDX-2.3", "SPDX-2.1"), "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SPDX-2.1 is not supported")));
        upload("x.spdx.json", "{\"spdxVersion\":\"SPDX-2.3\",\"files\":[]}", "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("has no &quot;packages&quot; array")));
    }

    // SPDX 2.2 tag-value test data: multi-line <text> fields and comments mention a fake package; file sections sit between packages.
    static final String SPDX_TV = """
            SPDXVersion: SPDX-2.2
            DataLicense: CC0-1.0
            SPDXID: SPDXRef-DOCUMENT
            DocumentName: alpha
            DocumentComment: <text>This document was generated for a test.
            PackageName: fake-from-document-comment
            PackageVersion: 0.0.1</text>
            # PackageName: fake-from-hash-comment

            PackageName: requests
            SPDXID: SPDXRef-requests
            PackageVersion: 2.31.0
            PackageComment: <text>pinned
            PackageName: fake-from-package-comment</text>
            ExternalRef: PACKAGE-MANAGER purl pkg:pypi/requests@2.31.0

            FileName: ./requirements.txt
            SPDXID: SPDXRef-file
            FileComment: <text>PackageVersion: 9.9.9</text>

            PackageName: urllib3
            SPDXID: SPDXRef-urllib3
            PackageVersion: 2.0.7
            ExternalRef: PACKAGE_MANAGER purl pkg:pypi/urllib3
            """;

    @Test
    void spdxTagValueHonoursSectionsTextBlocksAndComments() throws Exception {
        upload("alpha.spdx", SPDX_TV, "main").andExpect(status().isSeeOther());
        assertThat(latest()).containsExactly("requests 2.31.0 pkg:pypi/requests@2.31.0", "urllib3 2.0.7 pkg:pypi/urllib3@2.0.7");
        assertThat(db.sql("SELECT payload FROM snapshot_inventory").query(String.class).single()).contains("\"format\":\"SPDX-2.2 tag-value\"");
        upload("alpha.spdx", SPDX_TV.replace("PackageVersion: 2.0.7\n", ""), "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("urllib3 has no version")));
        upload("alpha.spdx", "SPDXVersion: SPDX-2.3\nDataLicense: CC0-1.0\n", "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("has no PackageName")));
    }

    // CycloneDX 1.4 XML test data: metadata root, a nested component and a file component.
    static final String CDX_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bom xmlns="http://cyclonedx.org/schema/bom/1.4" version="1">
              <metadata><component type="application"><name>alpha-app</name><version>9.9.9</version></component></metadata>
              <components>
                <component type="library" bom-ref="g1">
                  <group>com.google.guava</group><name>guava</name><version>32.1.2-jre</version>
                  <purl>pkg:maven/com.google.guava/guava@32.1.2-jre</purl>
                  <components><component type="library"><name>failureaccess</name><version>1.0.1</version></component></components>
                </component>
                <component type="file"><name>LICENSE</name></component>
              </components>
            </bom>""";

    // ISO/IEC 19770-2:2015 SWID test data: one software root; payload files and links are not packages and never fetched.
    static final String SWID = """
            <SoftwareIdentity xmlns="http://standards.iso.org/iso/19770/-2/2015/schema.xsd"
                name="Alpha Agent" version="5.4.1" tagId="example.com-alpha-agent-5.4.1">
              <Entity name="Example" regid="example.com" role="tagCreator softwareCreator"/>
              <Link rel="component" href="https://example.invalid/never-fetched.swidtag"/>
              <Payload><File name="agent.exe" version="5.4.1.7"/></Payload>
            </SoftwareIdentity>""";

    @Test
    void cycloneDxXmlAndSwidImportTheirPackagesAndHostileXmlIsRefused() throws Exception {
        upload("bom.xml", CDX_XML, "main").andExpect(status().isSeeOther());
        assertThat(latest()).containsExactly("failureaccess 1.0.1 -", "guava 32.1.2-jre pkg:maven/com.google.guava/guava@32.1.2-jre");
        assertThat(db.sql("SELECT payload FROM snapshot_inventory").query(String.class).single()).contains("\"format\":\"CycloneDX 1.4 XML\"");

        upload("agent.swidtag", SWID, "main").andExpect(status().isSeeOther());
        assertThat(latest()).containsExactly("Alpha Agent 5.4.1 -");
        upload("agent.swidtag", SWID.replace("<Payload>", "<SoftwareIdentity name=\"inner\" version=\"1\" tagId=\"x\"/><Payload>"), "main")
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.containsString("one SoftwareIdentity")));

        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE bom [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<bom xmlns=\"http://cyclonedx.org/schema/bom/1.5\"><components><component type=\"library\"><name>&x;</name><version>1</version></component></components></bom>";
        upload("bom.xml", xxe, "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DOCTYPE")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("root:"))));
        upload("bom.xml", CDX_XML.replace("bom/1.4", "bom/1.3"), "main").andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("not a supported SBOM")));
        assertThat(latest()).containsExactly("Alpha Agent 5.4.1 -");
    }

    @Test
    void malformedNestingForeignElementsXIncludeAndDeepXmlAreRejectedAndChangeNothing() throws Exception {
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther());
        List<String> before = latest();
        int snapshots = count("snapshot"), deps = count("dependency"), failed = 0;
        String deep = "<SoftwareIdentity xmlns=\"http://standards.iso.org/iso/19770/-2/2015/schema.xsd\" name=\"a\" version=\"1\">"
                + "<Meta>".repeat(40) + "</Meta>".repeat(40) + "</SoftwareIdentity>";
        java.util.Map<String, String> hostile = new java.util.LinkedHashMap<>();
        // a nested component list that is not an array must not be skipped silently
        hostile.put(CDX_JSON.replace("\"components\":[{\"type\":\"library\",\"bom-ref\":\"r2\"", "\"components\":{\"x\":[{\"type\":\"library\",\"bom-ref\":\"r2\"")
                .replace("\"purl\":\"pkg:maven/org.springframework/spring-core\"}]}", "\"purl\":\"pkg:maven/org.springframework/spring-core\"}]}}"), "must be an array");
        // something other than <component> inside <components> must not empty the branch
        hostile.put("<bom xmlns=\"http://cyclonedx.org/schema/bom/1.5\"><components><thing/></components></bom>", "unexpected element");
        hostile.put("<bom xmlns=\"http://cyclonedx.org/schema/bom/1.5\"><components><x:component xmlns:x=\"urn:other\"><x:name>a</x:name><x:version>1</x:version></x:component></components></bom>",
                "unexpected element");
        hostile.put("<bom xmlns=\"http://cyclonedx.org/schema/bom/1.5\"><components><xi:include xmlns:xi=\"http://www.w3.org/2001/XInclude\" href=\"file:///etc/passwd\"/></components></bom>",
                "XInclude");
        hostile.put(deep, "nested more than");
        for (var h : hostile.entrySet()) {
            upload("bad.sbom", h.getKey(), "main").andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(h.getValue())));
            failed++;
        }
        assertThat(latest()).isEqualTo(before);
        assertThat(List.of(count("snapshot"), count("dependency"))).containsExactly(snapshots, deps);
        assertThat(db.sql("SELECT count(*) FROM import_run WHERE ok = FALSE AND source = 'sbom bad.sbom'").query(Integer.class).single()).isEqualTo(failed);
    }

    @Test
    void presentButMistypedListsAndIdentityFieldsAreRejectedNotIgnored() throws Exception {
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther());
        List<String> before = latest();
        int snapshots = count("snapshot"), deps = count("dependency");
        String lonely = "{\"type\":\"library\",\"bom-ref\":\"lonely\",\"name\":\"left-pad\",\"version\":\"1.3.0\"";
        java.util.Map<String, String> bad = new java.util.LinkedHashMap<>();
        for (String nested : List.of("null", "\"x\"", "{}")) bad.put(CDX_JSON.replace(lonely, lonely + ",\"components\":" + nested), "components must be an array");
        bad.put(CDX_JSON.replace("\"purl\":\"pkg:npm/react@18.2.0\"", "\"purl\":123"), "\"purl\" must be a string");
        bad.put(CDX_JSON.replace("\"group\":\"org.springframework\"", "\"group\":[1]"), "\"group\" must be a string");
        bad.put(CDX_JSON.replace("\"bom-ref\":\"lonely\"", "\"bom-ref\":true"), "\"bom-ref\" must be a string");
        bad.put(SPDX_JSON.replace("\"externalRefs\":[{\"referenceCategory\":\"PACKAGE-MANAGER\",\"referenceType\":\"purl\",\"referenceLocator\":\"pkg:pypi/django@4.2.1\"}]",
                "\"externalRefs\":{\"x\":1}"), "externalRefs must be an array");
        bad.put(SPDX_JSON.replace("\"referenceLocator\":\"pkg:pypi/django@4.2.1\"", "\"referenceLocator\":42"), "\"referenceLocator\" must be a string");
        bad.put(SPDX_JSON.replace("\"externalRefs\":[{\"referenceCategory\":\"PACKAGE-MANAGER\",\"referenceType\":\"purl\",\"referenceLocator\":\"pkg:pypi/django@4.2.1\"}]",
                "\"externalRefs\":null"), "externalRefs must be an array");
        bad.put(SPDX_JSON.replace("\"externalRefs\":[{\"referenceCategory\"", "\"externalRefs\":[\"x\",{\"referenceCategory\""), "externalRefs[0] must be an object");
        bad.put(SPDX_TV.replace("ExternalRef: PACKAGE-MANAGER purl pkg:pypi/requests@2.31.0", "ExternalRef: PACKAGE-MANAGER purl pkg:pypi/requests@2.31.0\nExternalRef: PACKAGE-MANAGER purl pkg:pypi/other@2.31.0"),
                "more than one purl");
        bad.put(SPDX_JSON.replace("\"referenceLocator\":\"pkg:pypi/django@4.2.1\"}",
                "\"referenceLocator\":\"pkg:pypi/django@4.2.1\"},{\"referenceType\":\"purl\",\"referenceLocator\":\"pkg:pypi/flask@4.2.1\"}"), "more than one purl");
        for (var b : bad.entrySet()) {
            upload("bad.json", b.getKey(), "main").andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(b.getValue().replace("\"", "&quot;"))));
        }
        assertThat(latest()).isEqualTo(before);
        assertThat(List.of(count("snapshot"), count("dependency"))).containsExactly(snapshots, deps);
    }

    /** The /repos row cells of the one branch: column key -> visible text. */
    java.util.Map<String, String> reposRow() throws Exception {
        String html = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/repos")).andReturn().getResponse().getContentAsString();
        java.util.Map<String, String> cells = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern.compile("<td data-column=\"([a-z]+)\"[^>]*>(.*?)</td>", java.util.regex.Pattern.DOTALL).matcher(html).results()
                .forEach(m -> cells.put(m.group(1), m.group(2).replaceAll("<[^>]+>", "").trim()));
        return cells;
    }

    @Test
    void reposShowsTheApplicationVersionAndSbomFormatFromTheLatestSnapshotOnly() throws Exception {
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "9.9.9").containsEntry("format", "CycloneDX 1.5 JSON");
        upload("bom.xml", CDX_XML, "main").andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "9.9.9").containsEntry("format", "CycloneDX 1.4 XML");
        upload("alpha.spdx.json", SPDX_JSON, "main").andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "9.9.9").containsEntry("format", "SPDX-2.3 JSON");
        // two described packages: ambiguous, so no version is claimed
        upload("alpha.spdx.json", SPDX_JSON.replace("\"documentDescribes\":[\"SPDXRef-app\"]", "\"documentDescribes\":[\"SPDXRef-app\",\"SPDXRef-django\"]"), "main")
                .andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "—");
        upload("alpha.spdx", SPDX_TV.replace("DocumentName: alpha\n", "DocumentName: alpha\nRelationship: SPDXRef-DOCUMENT DESCRIBES SPDXRef-requests\n"), "main")
                .andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "2.31.0").containsEntry("format", "SPDX-2.2 tag-value");
        upload("alpha.spdx", SPDX_TV, "main").andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "—");
        // the described SPDXID appears twice with different versions: ambiguous, never the last one
        upload("alpha.spdx.json", SPDX_JSON.replace("{\"SPDXID\":\"SPDXRef-django\",\"name\":\"Django\"", "{\"SPDXID\":\"SPDXRef-app\",\"name\":\"alpha-app\",\"versionInfo\":\"1.0.0\"},"
                + "{\"SPDXID\":\"SPDXRef-django\",\"name\":\"Django\""), "main").andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "—");
        upload("agent.swidtag", SWID, "main").andExpect(status().isSeeOther());
        assertThat(reposRow()).containsEntry("version", "5.4.1").containsEntry("format", "ISO/IEC 19770-2:2015 SWID");
        // dependency-inventory JSON: optional root version; the format is the inventory JSON itself
        imports.importJson("[{\"name\":\"Alpha\",\"url\":\"" + REPO + "\",\"branch\":\"main\",\"version\":\" 3.0.1 \",\"dependencies\":[]}]", "test");
        assertThat(reposRow()).containsEntry("version", "3.0.1").containsEntry("format", "Inventory JSON");
        // a declared sbom.format in the latest payload is shown as it is, whoever wrote it
        imports.importJson("[{\"name\":\"Alpha\",\"url\":\"" + REPO + "\",\"branch\":\"main\",\"sbom\":{\"format\":\"CycloneDX 1.5 JSON\"},\"dependencies\":[]}]", "test");
        assertThat(reposRow()).containsEntry("format", "CycloneDX 1.5 JSON").containsEntry("version", "—");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imports.importJson("[{\"name\":\"A\",\"url\":\"" + REPO + "\",\"branch\":\"x\",\"version\":7,\"dependencies\":[]}]", "test"))
                .hasMessageContaining("\"version\" must be a string");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imports.importJson("[{\"name\":\"A\",\"url\":\"" + REPO + "\",\"branch\":\"x\",\"version\":\"" + "9".repeat(201) + "\",\"dependencies\":[]}]", "test"))
                .hasMessageContaining("longer than 200");
    }

    @Autowired ImportService imports;

    @Test
    void reposTableHasEightAlignedColumnsWithStablePreferenceIndexesAndNoSubtitle() throws Exception {
        String html = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/repos")).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("class=\"lede\"").contains("<td colspan=\"8\" class=\"empty\">");
        assertThat(java.util.regex.Pattern.compile("<th scope=\"col\" data-preference-index=\"(\\d)\"[^>]*>([^<]+)</th>").matcher(html).results()
                .map(m -> m.group(1) + ":" + m.group(2)).toList())
                .containsExactly("0:App ID", "1:Code repo", "2:Branch", "6:Version", "3:Provider", "7:SBOM Format", "4:Packages", "5:Latest snapshot");
        // the application version sorts and range-filters as a version, like every other Version column
        assertThat(html).contains("<th scope=\"col\" data-preference-index=\"6\" data-type=\"version\">Version</th>");
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther());
        assertThat(reposRow().keySet()).containsExactly("appid", "repo", "branch", "version", "provider", "format", "packages", "snapshot");
    }

    @Test
    void importsLinkIsOutsideTheMainNavAsTheNoScriptFallbackForTheSettingsPanel() throws Exception {
        for (String url : List.of("/", "/imports")) {
            String html = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andReturn().getResponse().getContentAsString();
            String nav = html.substring(html.indexOf("<nav aria-label=\"Main\">"), html.indexOf("</nav>"));
            assertThat(nav).as(url).contains(">EoL Dependencies</a>", ">Code repos</a>").doesNotContain("Imports");
            assertThat(html).as(url).contains(url.equals("/imports")
                    ? "<a class=\"button-quiet\" data-settings-imports href=\"/imports\" aria-current=\"page\">Imports</a>"
                    : "<a class=\"button-quiet\" data-settings-imports href=\"/imports\">Imports</a>");
        }
    }

    /** Each bundled demo SBOM: file name -> media type, SBOM Format shown, application version shown, imported packages. */
    static final java.util.Map<String, List<Object>> DEMOS = new java.util.LinkedHashMap<>();
    static {
        DEMOS.put("demo-cyclonedx-1.6.json", List.of("application/json", "CycloneDX 1.6 JSON", "1.0.0", List.of("django 3.2.25 pkg:pypi/django@3.2.25")));
        DEMOS.put("demo-cyclonedx-1.6.xml", List.of("application/xml", "CycloneDX 1.6 XML", "1.0.0", List.of("django 3.2.25 pkg:pypi/django@3.2.25")));
        // SPDX: the described root application is a package too
        DEMOS.put("demo-spdx-2.3.json", List.of("application/json", "SPDX-2.3 JSON", "1.0.0",
                List.of("demo-sbom-app 1.0.0 -", "django 3.2.25 pkg:pypi/django@3.2.25")));
        DEMOS.put("demo-spdx-2.3.spdx", List.of("text/plain", "SPDX-2.3 tag-value", "1.0.0",
                List.of("demo-sbom-app 1.0.0 -", "django 3.2.25 pkg:pypi/django@3.2.25")));
        // SWID: the tag describes Django itself, so its version is the shown version and it carries no purl
        DEMOS.put("demo-swid-2015.swidtag", List.of("application/xml", "ISO/IEC 19770-2:2015 SWID", "3.2.25", List.of("django 3.2.25 -")));
    }

    @Test
    void individualImportListsEveryDemoSbomAsAVisibleDownloadLink() throws Exception {
        String html = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/imports")).andReturn().getResponse().getContentAsString();
        String individual = html.substring(html.indexOf("aria-labelledby=\"sbom-upload-h\""), html.indexOf("</section>", html.indexOf("aria-labelledby=\"sbom-upload-h\"")));
        assertThat(individual).contains("<h3>Demo SBOM files</h3>", "example.invalid");
        for (String name : DEMOS.keySet()) assertThat(individual).contains("href=\"/imports/samples/" + name + "\" download>");
        assertThat(individual).doesNotContain("<table");
    }

    @Test
    void everyDemoSbomDownloadsAsAnAttachmentAndImportsIdempotently() throws Exception {
        for (var demo : DEMOS.entrySet()) {
            String name = demo.getKey();
            var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/imports/samples/" + name))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(res.getContentType()).as(name).startsWith((String) demo.getValue().get(0));
            assertThat(res.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"" + name + "\"");
            String body = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
            // the SWID tag carries no purl (the adapter reads none from SWID); the other four name Django by purl
            if (name.endsWith(".swidtag")) assertThat(body).as(name).doesNotContain("pkg:");
            else assertThat(body).as(name).contains("pkg:pypi/django@3.2.25");
            upload(name, body, "main").andExpect(status().isSeeOther());
            assertThat(db.sql("SELECT branch FROM repo_branch").query(String.class).single()).as(name).isEqualTo("main");
            assertThat(db.sql("SELECT source FROM import_run ORDER BY id DESC LIMIT 1").query(String.class).single()).as(name).isEqualTo("sbom " + name);
            assertThat(latest()).as(name).isEqualTo(demo.getValue().get(3));
            assertThat(reposRow()).as(name).containsEntry("format", (String) demo.getValue().get(1)).containsEntry("version", (String) demo.getValue().get(2));
            int snapshots = count("snapshot");
            upload(name, body, "main").andExpect(status().isSeeOther());
            assertThat(count("snapshot")).as(name + " re-import").isEqualTo(snapshots);
        }
    }

    @Test
    void importsPageOffersTheSbomFormNextToTheUnchangedJsonForm() throws Exception {
        String html = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/imports"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<button type=\"button\" popovertarget=\"sbom-dialog\">Import SBOM</button>",
                "<div id=\"sbom-dialog\" class=\"import-dialog\" popover role=\"dialog\" aria-labelledby=\"sbom-dialog-h\">",
                "action=\"/imports/sbom\" enctype=\"multipart/form-data\"", "name=\"_csrf\"",
                "<input id=\"sbom-file\" name=\"file\" type=\"file\" required",
                "<input id=\"sbom-name\" name=\"name\" type=\"text\" required",
                "<input id=\"sbom-url\" name=\"url\" type=\"url\" required",
                "<input id=\"sbom-branch\" name=\"branch\" type=\"text\" required",
                "<select id=\"sbom-provider\" name=\"provider\">", "<input id=\"sbom-app-id\" name=\"appId\" type=\"text\"",
                "<details class=\"help\">", "one SoftwareIdentity");
        // the dependency-inventory JSON form is untouched
        assertThat(html).contains("<div id=\"import-dialog\" class=\"import-dialog\" popover role=\"dialog\" aria-labelledby=\"import-dialog-h\">",
                "<input id=\"file\" name=\"file\" type=\"file\" accept=\".json,application/json\" required aria-describedby=\"json-h\">");
    }

    @Test
    void anExplicitlyEmptyComponentListIsADeliberateEmptyInventory() throws Exception {
        upload("bom.json", CDX_JSON, "main").andExpect(status().isSeeOther());
        upload("bom.json", "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[]}", "main").andExpect(status().isSeeOther());
        assertThat(latest()).isEmpty();
        assertThat(count("snapshot")).isEqualTo(2);
    }
}
