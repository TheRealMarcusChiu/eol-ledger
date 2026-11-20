package dev.eolledger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The default suite must stay on in-memory H2 even when the shell running Maven carries the README's MySQL settings,
 * and the per-test reset must refuse to delete from anything it does not own. Prove the first with, for example:
 * mvn test -Dspring.profiles.active=mysql -DMYSQL_URL=jdbc:mysql://127.0.0.1:1/x -Dspring.datasource.url=jdbc:mysql://127.0.0.1:1/x
 */
class TestIsolationTest extends IntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired Environment env;

    final String previouslyOwned = TestSupport.ownedMySqlSchema;

    @AfterEach
    void restoreOwnedSchema() {
        TestSupport.ownedMySqlSchema = previouslyOwned;
    }

    @Test
    void ordinaryTestsUseInMemoryH2WithoutTheMysqlProfile() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            assertThat(c.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        assertThat(env.getActiveProfiles()).doesNotContain("mysql");
    }

    @Test
    void resetGuardAllowsOnlyLocalInMemoryH2() {
        assertThatCode(() -> TestSupport.requireDisposable("H2", "jdbc:h2:mem:eol", "EOL")).doesNotThrowAnyException();
        assertThatThrownBy(() -> TestSupport.requireDisposable("H2", "jdbc:h2:file:./data/eol-ledger", "EOL-LEDGER")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSupport.requireDisposable("H2", "jdbc:h2:tcp://db.example.com/mem:eol", "EOL")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSupport.requireDisposable("PostgreSQL", "jdbc:postgresql://db/eol", "eol")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSupport.requireDisposable("PostgreSQL", "jdbc:h2:mem:eol", "eol")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resetGuardAllowsOnlyTheMysqlSchemaThisRunCreated() {
        assertThatThrownBy(() -> TestSupport.requireDisposable("MySQL", "jdbc:mysql://db/eol_ledger", "eol_ledger")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSupport.requireDisposable("MySQL", "jdbc:mysql://db/eol_test_existing", "eol_test_existing")).isInstanceOf(IllegalStateException.class);
        TestSupport.ownedMySqlSchema = "eol_test_0123456789abcdef";
        assertThatCode(() -> TestSupport.requireDisposable("MySQL", "jdbc:mysql://db/eol_test_0123456789abcdef", "eol_test_0123456789abcdef"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TestSupport.requireDisposable("MySQL", "jdbc:mysql://db/eol_test_existing", "eol_test_existing")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSupport.requireDisposable("MySQL", "jdbc:mysql://db/eol_test_0123456789abcdef", null)).isInstanceOf(IllegalStateException.class);
    }
}
