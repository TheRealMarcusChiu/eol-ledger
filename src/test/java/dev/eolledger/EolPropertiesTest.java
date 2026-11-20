package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

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

    /** The README's environment variable names, resolved the way Spring Boot resolves real OS environment variables. */
    @Test
    void documentedEnvironmentVariablesBind() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "EOL_JOBS_CATALOGSYNC_CRON", "0 0 3 * * *",
                        "EOL_JOBS_REPOREFRESH_CRON", "0 30 3 * * *",
                        "EOL_CATALOG_BASEURL", "https://mirror.example.com/api/v1",
                        "EOL_REFRESHCSVPATH", "/srv/registry.csv",
                        "EOL_SBOM_HOSTS_0_HOST", "artifactory.example.com",
                        "EOL_SBOM_HOSTS_0_TOKEN", "t",
                        "EOL_MAPPINGS", "pkg:maven/com.acme/acme-bom=spring-boot")));
        ConfigurationPropertySources.attach(env);
        assertThat(env.resolvePlaceholders("${eol.jobs.catalog-sync.cron:-}")).isEqualTo("0 0 3 * * *");
        assertThat(env.resolvePlaceholders("${eol.jobs.repo-refresh.cron:-}")).isEqualTo("0 30 3 * * *");
        EolProperties p = Binder.get(env).bindOrCreate("eol", EolProperties.class);
        assertThat(p.catalog().baseUrl()).isEqualTo("https://mirror.example.com/api/v1");
        assertThat(p.refreshCsvPath()).isEqualTo("/srv/registry.csv");
        assertThat(p.sbom().hosts()).containsExactly(new EolProperties.Host("artifactory.example.com", "t", false));
        assertThat(p.mappingTable()).containsEntry("pkg:maven/com.acme/acme-bom", "spring-boot");
    }

    @Test
    void mappingKeysUseTypeAwarePurlNormalization() {
        EolProperties p = bind(Map.of("eol.mappings", "pkg:maven/Com.Acme/Acme-BOM@1.0=spring-boot,My-Image=ubuntu"));
        assertThat(p.mappingTable()).containsEntry("pkg:maven/Com.Acme/Acme-BOM", "spring-boot").containsEntry("my-image", "ubuntu");
    }

    @Test
    void hostsAndMappingsBindFromIndexedProperties() {
        EolProperties p = bind(Map.of("eol.sbom.hosts[0].host", "artifactory.example.com", "eol.sbom.hosts[0].token", "t",
                "eol.mappings", "pkg:maven/com.acme/acme-bom=spring-boot,my-image=ubuntu"));
        assertThat(p.sbom().hosts()).containsExactly(new EolProperties.Host("artifactory.example.com", "t", false));
        assertThat(p.mappingTable()).containsEntry("pkg:maven/com.acme/acme-bom", "spring-boot").containsEntry("my-image", "ubuntu");
    }
}
