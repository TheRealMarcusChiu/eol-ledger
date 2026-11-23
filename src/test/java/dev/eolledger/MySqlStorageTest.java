package dev.eolledger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Storage contract on a REAL MySQL 8 server. Opt-in only: runs when EOL_TEST_MYSQL_URL is set to a dedicated test
 * server (no database in the path), with EOL_TEST_MYSQL_USER / EOL_TEST_MYSQL_PASSWORD allowed to CREATE and DROP
 * databases named eol_test_*. Each run creates one fresh eol_test_&lt;random&gt; database, uses only that, and drops only it.
 */
@EnabledIfEnvironmentVariable(named = "EOL_TEST_MYSQL_URL", matches = ".+")
@ActiveProfiles(value = "mysql", inheritProfiles = false)
@DirtiesContext
class MySqlStorageTest extends StorageContractTest {

    static final String SCHEMA = "eol_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    static boolean created;

    static String url(String schema) {
        String[] parts = System.getenv("EOL_TEST_MYSQL_URL").split("\\?", 2);
        return parts[0].replaceAll("/+$", "") + "/" + schema + (parts.length > 1 ? "?" + parts[1] : "");
    }

    static void admin(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(""), System.getenv("EOL_TEST_MYSQL_USER"), System.getenv("EOL_TEST_MYSQL_PASSWORD"));
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry r) throws SQLException {
        admin("CREATE DATABASE `" + SCHEMA + "`"); // no IF NOT EXISTS: an existing schema is never adopted
        created = true;
        TestSupport.ownedMySqlSchema = SCHEMA;
        r.add("spring.datasource.url", () -> url(SCHEMA));
        r.add("spring.datasource.username", () -> System.getenv("EOL_TEST_MYSQL_USER"));
        r.add("spring.datasource.password", () -> System.getenv("EOL_TEST_MYSQL_PASSWORD"));
    }

    @AfterAll
    static void dropOwnSchema() throws SQLException {
        TestSupport.ownedMySqlSchema = null;
        if (created) admin("DROP DATABASE `" + SCHEMA + "`");
    }

    @Test
    void runsWithTheMysqlProfileAndItsOwnDatasource() throws SQLException {
        assertThat(env.getActiveProfiles()).containsExactly("mysql");
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(c.getCatalog()).isEqualTo(SCHEMA);
        }
    }

    @Autowired org.springframework.core.env.Environment env;

    @Autowired DataSource dataSource;

    @Test
    void reallyRunsOnMySql8WithOwnSchemaInnoDbBinaryCollationAndForeignKeys() {
        assertThat(db.sql("SELECT version()").query(String.class).single()).startsWith("8.");
        assertThat(db.sql("SELECT database()").query(String.class).single()).isEqualTo(SCHEMA);
        assertThat(db.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND (engine <> 'InnoDB' OR table_collation <> 'utf8mb4_0900_bin')")
                .param(SCHEMA).query(Integer.class).single()).isZero();
        assertThat(db.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = ?").param(SCHEMA).query(Integer.class).single()).isEqualTo(10); // 9 data tables + the dash_row read model
        assertThat(db.sql("SELECT count(*) FROM information_schema.referential_constraints WHERE constraint_schema = ?").param(SCHEMA)
                .query(Integer.class).single()).isEqualTo(3);
        assertThatThrownBy(() -> db.sql("INSERT INTO dependency (snapshot_id, name) VALUES (987654321, 'orphan')").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaInitIsSafeToRepeatAndKeepsData() throws SQLException {
        seed();
        int deps = count("dependency");
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("schema-mysql.sql"));
        }
        assertThat(count("dependency")).isEqualTo(deps).isPositive();
    }
}
