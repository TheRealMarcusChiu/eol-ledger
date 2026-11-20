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
        assertThat(Matcher.normalizePurl("not a purl")).isNull();
        assertThat(Matcher.normalizePurl("pkg:npm")).isNull();
        assertThat(Matcher.normalizePurl("pkg:/")).isNull();
    }

    @Test
    void normalizationIsTypeAwareAndPreservesMavenCase() {
        assertThat(Matcher.normalizePurl("PKG:Maven/Org.Foo/Bar@1")).isEqualTo("pkg:maven/Org.Foo/Bar");
        assertThat(Matcher.normalizePurl("pkg:maven/org.foo/bar@1")).isNotEqualTo(Matcher.normalizePurl("pkg:maven/Org.Foo/Bar@1"));
        assertThat(Matcher.normalizePurl("pkg:npm/%40Angular/Core@17")).isEqualTo("pkg:npm/@angular/core");
        assertThat(Matcher.normalizePurl("pkg:pypi/Django_Rest@1")).isEqualTo("pkg:pypi/django-rest");
        assertThat(Matcher.normalizePurl("pkg:generic/Python@3")).isEqualTo("pkg:generic/Python");
        // an encoded slash inside a segment must not collide with a real path separator
        assertThat(Matcher.normalizePurl("pkg:generic/a%2Fb@1")).isNotEqualTo(Matcher.normalizePurl("pkg:generic/a/b@1"));
    }

    @Test
    void mavenCaseDifferenceIsNotMatched() {
        Matcher m = new Matcher(Map.of("pkg:maven/log4j/log4j", Set.of("log4j")), Map.of("log4j", List.of("1")), Map.of());
        assertThat(m.match("log4j", "1.2.17", "pkg:maven/log4j/log4j@1.2.17").product()).isEqualTo("log4j");
        assertThat(m.match("log4j", "1.2.17", "pkg:maven/Log4j/log4j@1.2.17").product()).isNull();
    }

    @Test
    void malformedPurlDoesNotFallBackToNameMatch() {
        Matcher.Match m = matcher.match("react", "18.2.0", "react@18.2.0");
        assertThat(m.product()).isNull();
        assertThat(m.evidence()).contains("malformed purl");
    }

    @Test
    void cycleMatchRejectsJunkAndPrereleaseButAllowsDocumentedSuffixes() {
        assertThat(Matcher.matchCycle("3.10evil", List.of("3.10", "3"))).isNull();
        assertThat(Matcher.matchCycle("3.10.0-rc1", List.of("3.10"))).isNull();
        assertThat(Matcher.matchCycle("6.0.0-SNAPSHOT", List.of("6.0"))).isNull();
        assertThat(Matcher.matchCycle("3.10.0+build5", List.of("3.10"))).isNull();
        assertThat(Matcher.matchCycle("6.4.4.Final", List.of("6.4"))).isEqualTo("6.4");
        assertThat(Matcher.matchCycle("4.3.2.RELEASE", List.of("4.3"))).isEqualTo("4.3");
        assertThat(Matcher.matchCycle("V2.7.18", List.of("2.7"))).isEqualTo("2.7");
        Matcher.Match m = matcher.match("react", "19.0.0-rc.1", "pkg:npm/react@19.0.0-rc.1");
        assertThat(m.cycle()).isNull();
        assertThat(m.evidence()).contains("not a plain release version");
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
        assertThat(m.evidence()).contains("not a plain release version");
        Matcher.Match unknownCycle = matcher.match("react", "99.0.0", "pkg:npm/react@99.0.0");
        assertThat(unknownCycle.product()).isEqualTo("react");
        assertThat(unknownCycle.cycle()).isNull();
        assertThat(unknownCycle.evidence()).contains("no release cycle");
    }
}
