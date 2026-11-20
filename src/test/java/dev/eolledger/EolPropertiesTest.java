package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EolPropertiesTest {

    EolProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("eol", EolProperties.class);
    }

    @Test
    void shippedDefaultsBindWithNoHostsProductsOrMappings() {
        EolProperties p = bind(Map.of("eol.zone", "", "eol.catalog.products", "", "eol.mappings", ""));
        assertThat(p.sbom().hosts()).isEmpty();
        assertThat(p.catalog().products()).allMatch(String::isBlank);
        assertThat(p.mappingTable()).isEmpty();
        assertThat(p.catalog().baseUrl()).isEqualTo("https://endoflife.date/api/v1");
    }

    @Test
    void hostsAndMappingsBindFromIndexedProperties() {
        EolProperties p = bind(Map.of("eol.sbom.hosts[0].host", "artifactory.example.com", "eol.sbom.hosts[0].token", "t",
                "eol.mappings", "pkg:maven/com.acme/acme-bom=spring-boot,my-image=ubuntu"));
        assertThat(p.sbom().hosts()).containsExactly(new EolProperties.Host("artifactory.example.com", "t", false));
        assertThat(p.mappingTable()).containsEntry("pkg:maven/com.acme/acme-bom", "spring-boot").containsEntry("my-image", "ubuntu");
    }
}
