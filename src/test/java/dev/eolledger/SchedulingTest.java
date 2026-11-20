package dev.eolledger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingTest {

    @Test
    void shippedDefaultsDisableBothJobsAndBindLocalhost() throws Exception {
        Properties p = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            p.load(in);
        }
        assertThat(p.getProperty("eol.jobs.catalog-sync.cron")).isEqualTo("-");
        assertThat(p.getProperty("eol.jobs.repo-refresh.cron")).isEqualTo("-");
        assertThat(p.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(p.getProperty("server.port")).isEqualTo("8092");
    }

    @Nested
    @SpringBootTest
    class Disabled {
        @Autowired ScheduledAnnotationBeanPostProcessor scheduler;

        @Test
        void noJobsRegistered() {
            assertThat(scheduler.getScheduledTasks()).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = "eol.jobs.repo-refresh.cron=0 0 4 * * *")
    class OnlyRefreshEnabled {
        @Autowired ScheduledAnnotationBeanPostProcessor scheduler;

        @Test
        void exactlyOneJobRegistered() {
            assertThat(scheduler.getScheduledTasks()).hasSize(1);
        }
    }
}
