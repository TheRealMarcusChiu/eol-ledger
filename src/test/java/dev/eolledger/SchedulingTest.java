package dev.eolledger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingTest {

    @Test
    void shippedDefaultsDisableTheCatalogueJobAndBindLocalhost() throws Exception {
        Properties p = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            p.load(in);
        }
        assertThat(p.getProperty("eol.jobs.catalog-sync.cron")).isEqualTo("-");
        assertThat(p.getProperty("eol.jobs.repo-refresh.cron")).isNull();
        assertThat(p.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(p.getProperty("server.port")).isEqualTo("8092");
    }

    @Nested
    class Disabled extends IntegrationTest {
        @Autowired ScheduledAnnotationBeanPostProcessor scheduler;

        @Test
        void noJobsRegistered() {
            assertThat(scheduler.getScheduledTasks()).isEmpty();
        }
    }

    @Nested
    @TestPropertySource(properties = "eol.jobs.catalog-sync.cron=0 0 4 * * *")
    class CatalogueSyncEnabled extends IntegrationTest {
        @Autowired ScheduledAnnotationBeanPostProcessor scheduler;

        @Test
        void exactlyOneJobRegistered() {
            assertThat(scheduler.getScheduledTasks()).hasSize(1);
        }
    }
}
