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
    void shippedDefaultsBindWithNoProductsOrMappings() {
        EolProperties p = bind(Map.of("eol.zone", "", "eol.catalog.products", "", "eol.mappings", ""));
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
                        "EOL_CATALOG_BASEURL", "https://mirror.example.com/api/v1",
                        "EOL_MAPPINGS", "pkg:maven/com.acme/acme-bom=spring-boot")));
        ConfigurationPropertySources.attach(env);
        assertThat(env.resolvePlaceholders("${eol.jobs.catalog-sync.cron:-}")).isEqualTo("0 0 3 * * *");
        EolProperties p = Binder.get(env).bindOrCreate("eol", EolProperties.class);
        assertThat(p.catalog().baseUrl()).isEqualTo("https://mirror.example.com/api/v1");
        assertThat(p.mappingTable()).containsEntry("pkg:maven/com.acme/acme-bom", "spring-boot");
    }

    @Test
    void mappingKeysUseTypeAwarePurlNormalization() {
        EolProperties p = bind(Map.of("eol.mappings", "pkg:maven/Com.Acme/Acme-BOM@1.0=spring-boot,My-Image=ubuntu"));
        assertThat(p.mappingTable()).containsEntry("pkg:maven/Com.Acme/Acme-BOM", "spring-boot").containsEntry("my-image", "ubuntu");
    }

    @Test
    void mappingsBindFromACommaList() {
        EolProperties p = bind(Map.of("eol.mappings", "pkg:maven/com.acme/acme-bom=spring-boot,my-image=ubuntu"));
        assertThat(p.mappingTable()).containsEntry("pkg:maven/com.acme/acme-bom", "spring-boot").containsEntry("my-image", "ubuntu");
    }
}
