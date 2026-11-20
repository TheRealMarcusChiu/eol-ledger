package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Hermetic checks of the mysql profile wiring and DDL; real-server behaviour is in {@link MySqlStorageTest}. */
class MySqlProfileTest {

    static String text(String resource) throws Exception {
        return new String(new ClassPathResource(resource).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void mysqlProfileWithoutUrlFailsInsteadOfFallingBackToH2() {
        assumeTrue(System.getenv("MYSQL_URL") == null, "MYSQL_URL is set in this environment");
        assertThatThrownBy(() -> new SpringApplicationBuilder(EolLedgerApplication.class).profiles("mysql")
                .properties("server.port=0", "spring.main.banner-mode=off").run().close())
                .hasStackTraceContaining("MYSQL_URL");
    }

    @Test
    void mysqlDriverIsOnTheRuntimeClasspath() throws Exception {
        assertThat(Class.forName("com.mysql.cj.jdbc.Driver")).isNotNull();
        assertThat(text("application-mysql.properties")).contains("${MYSQL_URL}", "${MYSQL_USER}", "${MYSQL_PASSWORD}", "schema-mysql.sql");
    }

    @Test
    void mysqlSchemaOnlyCreatesMissingTablesWithRealForeignKeysAndExactBinaryText() throws Exception {
        String ddl = text("schema-mysql.sql");
        String upper = ddl.toUpperCase();
        assertThat(upper).doesNotContain("DROP ", "TRUNCATE", "WITH TIME ZONE", "MERGE");
        assertThat(upper.split("CREATE TABLE IF NOT EXISTS", -1)).hasSize(9);
        assertThat(upper.split("CREATE TABLE", -1)).hasSize(9);
        assertThat(upper.split("ENGINE=INNODB", -1)).hasSize(9);
        assertThat(ddl.split("COLLATE=utf8mb4_0900_bin", -1)).hasSize(9);
        // table-level constraints: MySQL silently ignores inline column REFERENCES
        assertThat(upper.split("FOREIGN KEY", -1)).hasSize(3);
        assertThat(upper.split("REFERENCES", -1)).hasSize(3);
    }

    @Test
    void defaultH2SchemaIsUnchanged() throws Exception {
        assertThat(text("schema.sql")).contains("synced_at TIMESTAMP WITH TIME ZONE NOT NULL", "UNIQUE (repo_url, branch)", "PRIMARY KEY (product, purl)");
    }
}
