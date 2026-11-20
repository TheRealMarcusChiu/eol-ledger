package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MatcherTest {

    final Matcher matcher = new Matcher(
            Map.of("pkg:npm/@adonisjs/core", Set.of("adonisjs"),
                    "pkg:maven/org.springframework.boot/spring-boot", Set.of("spring-boot"),
                    "pkg:npm/react", Set.of("react"),
                    "pkg:generic/python", Set.of("python"),
                    "pkg:npm/shared", Set.of("a", "b")),
            Map.of("python", List.of("3.1", "3.10", "3.9", "2.7"),
                    "react", List.of("19", "18", "17"),
                    "spring-boot", List.of("3.5", "3.4"),
                    "adonisjs", List.of("6", "5"),
                    "ubuntu", List.of("24.04", "jammy")),
            Map.of("pkg:maven/com.acme/acme-boot-bom", "spring-boot", "my-ubuntu-image", "ubuntu"));

    @Test
    void normalizesPurlDroppingVersionQualifiersAndDecodingNamespace() {
        assertThat(Matcher.normalizePurl("pkg:npm/%40adonisjs/core@6.2.0?foo=bar#sub")).isEqualTo("pkg:npm/@adonisjs/core");
        assertThat(Matcher.normalizePurl("pkg:npm/@angular/core@17.0.0")).isEqualTo("pkg:npm/@angular/core");
        assertThat(Matcher.normalizePurl("PKG:Maven/Org.Foo/Bar@1")).isEqualTo("pkg:maven/org.foo/bar");
        assertThat(Matcher.normalizePurl("not a purl")).isNull();
    }

    @Test
    void cycleMatchUsesLongestNumericPrefixOnComponentBoundaries() {
        assertThat(Matcher.matchCycle("3.10.5", List.of("3.1", "3.10", "3"))).isEqualTo("3.10");
        assertThat(Matcher.matchCycle("3.1.4", List.of("3.1", "3.10"))).isEqualTo("3.1");
        assertThat(Matcher.matchCycle("18.2.0", List.of("19", "18"))).isEqualTo("18");
        assertThat(Matcher.matchCycle("v18.2.0", List.of("18"))).isEqualTo("18");
        assertThat(Matcher.matchCycle("3.11.0", List.of("3.1", "3.10"))).isNull();
        assertThat(Matcher.matchCycle("jammy", List.of("jammy", "22"))).isNull();
        assertThat(Matcher.matchCycle(null, List.of("1"))).isNull();
    }

    @Test
    void exactPurlMatchesProductAndCycle() {
        Matcher.Match m = matcher.match("core", "6.2.0", "pkg:npm/%40adonisjs/core@6.2.0");
        assertThat(m.product()).isEqualTo("adonisjs");
        assertThat(m.cycle()).isEqualTo("6");
        assertThat(m.evidence()).contains("purl");
    }

    @Test
    void genericNameCoreIsNotMatchedToUnrelatedProduct() {
        Matcher.Match m = matcher.match("core", "1.0.0", "pkg:maven/com.example/core@1.0.0");
        assertThat(m.product()).isNull();
        assertThat(m.evidence()).contains("No catalogue identifier");
    }

    @Test
    void javaPackagesAreNotAssumedToBeJava() {
        assertThat(matcher.match("guava", "33.0", "pkg:maven/com.google.guava/guava@33.0").product()).isNull();
    }

    @Test
    void explicitMappingAppliesWhenNoCatalogueIdentifier() {
        Matcher.Match m = matcher.match("acme-boot-bom", "3.5.2", "pkg:maven/com.acme/acme-boot-bom@3.5.2");
        assertThat(m.product()).isEqualTo("spring-boot");
        assertThat(m.cycle()).isEqualTo("3.5");
        assertThat(m.evidence()).contains("explicit mapping");
        assertThat(matcher.match("my-ubuntu-image", "24.04", null).product()).isEqualTo("ubuntu");
    }

    @Test
    void ambiguousPurlNeedsExplicitMapping() {
        Matcher.Match m = matcher.match("shared", "1", "pkg:npm/shared@1");
        assertThat(m.product()).isNull();
        assertThat(m.evidence()).contains("several products");
    }

    @Test
    void nameFallbackOnlyWithoutPurl() {
        assertThat(matcher.match("react", "18.3.1", null).product()).isEqualTo("react");
        assertThat(matcher.match("react", "18.3.1", null).evidence()).contains("name");
        assertThat(matcher.match("python", "3.10.2", "pkg:pypi/python@3.10.2").product()).isNull();
    }

    @Test
    void knownProductButUnmatchedCycleStaysUnknown() {
        Matcher.Match m = matcher.match("react", "nightly", "pkg:npm/react@nightly");
        assertThat(m.product()).isEqualTo("react");
        assertThat(m.cycle()).isNull();
        assertThat(m.evidence()).contains("no release cycle");
    }
}
