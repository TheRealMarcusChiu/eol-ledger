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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Base for Spring integration tests: one shared context, loopback catalogue, settable clock, clean DB.
 * The profile and in-memory H2 datasource are pinned above environment variables and -D system properties, so an
 * operator shell set up for MySQL (SPRING_PROFILES_ACTIVE=mysql, MYSQL_URL, SPRING_DATASOURCE_URL) cannot redirect
 * the suite to a real database. Only @DynamicPropertySource (MySqlStorageTest) ranks higher.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:eol;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password="})
@ActiveProfiles("test")
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
    @Autowired DataSource testDataSource;

    @BeforeEach
    void resetState() throws java.sql.SQLException {
        TestSupport.reset(db, testDataSource);
        CLOCK.now = java.time.Instant.parse("2026-11-20T12:00:00Z");
    }
}
