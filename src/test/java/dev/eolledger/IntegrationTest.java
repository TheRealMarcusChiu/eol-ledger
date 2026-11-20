package dev.eolledger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;

/** Base for Spring integration tests: one shared context, loopback catalogue, settable clock, clean DB. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.ClockConfig.class)
abstract class IntegrationTest {

    static final TestSupport.MutableClock CLOCK = new TestSupport.MutableClock();

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("eol.catalog.base-url", () -> TestSupport.base() + "/api/v1");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;

    @BeforeEach
    void resetState() {
        TestSupport.reset(db);
        CLOCK.now = java.time.Instant.parse("2026-11-20T12:00:00Z");
    }
}
