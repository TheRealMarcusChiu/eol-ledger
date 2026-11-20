package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SbomParserTest {

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void cycloneDxIncludesNestedComponentsAndDeduplicates() {
        var deps = SbomParser.parse(b("""
                {"bomFormat":"CycloneDX","specVersion":"1.5","components":[
                  {"type":"library","group":"org.springframework.boot","name":"spring-boot","version":"3.5.3",
                   "purl":"pkg:maven/org.springframework.boot/spring-boot@3.5.3",
                   "components":[{"name":"inner","version":"1.0","purl":"pkg:npm/inner@1.0"}]},
                  {"name":"spring-boot","group":"org.springframework.boot","version":"3.5.3","purl":"pkg:maven/org.springframework.boot/spring-boot@3.5.3"},
                  {"name":"no-purl","version":"2"}
                ]}"""));
        assertThat(deps).containsExactly(
                new SbomParser.Dep("inner", "1.0", "pkg:npm/inner@1.0"),
                new SbomParser.Dep("no-purl", "2", null),
                new SbomParser.Dep("org.springframework.boot:spring-boot", "3.5.3", "pkg:maven/org.springframework.boot/spring-boot@3.5.3"));
    }

    @Test
    void spdxReadsPackagesAndPurlExternalRefsButSkipsDescribedRoot() {
        var deps = SbomParser.parse(b("""
                {"spdxVersion":"SPDX-2.3","documentDescribes":["SPDXRef-root"],"packages":[
                  {"SPDXID":"SPDXRef-root","name":"storefront","versionInfo":"main"},
                  {"SPDXID":"SPDXRef-1","name":"react","versionInfo":"18.2.0","externalRefs":[
                    {"referenceCategory":"PACKAGE-MANAGER","referenceType":"purl","referenceLocator":"pkg:npm/react@18.2.0"}]},
                  {"SPDXID":"SPDXRef-2","name":"left-pad","versionInfo":"NOASSERTION"}
                ]}"""));
        assertThat(deps).containsExactly(
                new SbomParser.Dep("left-pad", null, null),
                new SbomParser.Dep("react", "18.2.0", "pkg:npm/react@18.2.0"));
    }

    @Test
    void spdxRelationshipDescribesAlsoMarksRoot() {
        var deps = SbomParser.parse(b("""
                {"spdxVersion":"SPDX-2.2","relationships":[{"spdxElementId":"SPDXRef-DOCUMENT","relationshipType":"DESCRIBES","relatedSpdxElement":"SPDXRef-app"}],
                 "packages":[{"SPDXID":"SPDXRef-app","name":"app"},{"SPDXID":"SPDXRef-x","name":"x","versionInfo":"1"}]}"""));
        assertThat(deps).containsExactly(new SbomParser.Dep("x", "1", null));
    }

    @Test
    void rejectsXmlAndUnknownJson() {
        assertThatThrownBy(() -> SbomParser.parse(b("<bom/>"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON");
        assertThatThrownBy(() -> SbomParser.parse(b("{\"hello\":1}"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CycloneDX");
        assertThatThrownBy(() -> SbomParser.parse(b("{\"spdxVersion\":\"SPDX-3.0\"}"))).isInstanceOf(IllegalArgumentException.class);
    }
}
