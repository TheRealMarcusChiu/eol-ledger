package dev.eolledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An H2 database created by the previous version (NOT NULL repo columns, no import_key) upgraded in place, twice. */
class SchemaUpgradeTest {

    @Test
    void oldH2SchemaIsUpgradedInPlaceKeepingRowsIdsAndHistoryAndASecondRunChangesNothing() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:upgrade-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcClient db = JdbcClient.create(ds);
        db.sql("""
                CREATE TABLE repo_branch (id BIGINT AUTO_INCREMENT PRIMARY KEY, repo_url VARCHAR(1000) NOT NULL, branch VARCHAR(500) NOT NULL,
                    repo_name VARCHAR(300) NOT NULL, provider VARCHAR(50) NOT NULL, sbom_url VARCHAR(2000) NOT NULL, UNIQUE (repo_url, branch))""").update();
        db.sql("CREATE TABLE snapshot (id BIGINT AUTO_INCREMENT PRIMARY KEY, branch_id BIGINT NOT NULL REFERENCES repo_branch(id), fingerprint VARCHAR(64) NOT NULL)").update();
        db.sql("INSERT INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) VALUES ('https://x.test/a', 'main', 'A', 'other', 'inline:json-upload')").update();
        db.sql("INSERT INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) VALUES ('https://x.test/a', 'dev', 'A', 'github', 'inline:json-upload')").update();
        db.sql("INSERT INTO snapshot (branch_id, fingerprint) SELECT id, 'f' || id FROM repo_branch ORDER BY id").update();
        var branchesBefore = db.sql("SELECT id || ' ' || repo_url || ' ' || branch || ' ' || repo_name || ' ' || provider FROM repo_branch ORDER BY id").query(String.class).list();
        var snapshotsBefore = db.sql("SELECT id || ':' || branch_id || ':' || fingerprint FROM snapshot ORDER BY id").query(String.class).list();
        long maxBefore = db.sql("SELECT max(id) FROM repo_branch").query(Long.class).single();

        SchemaUpgrade.upgrade(ds);
        SchemaUpgrade.upgrade(ds); // every start runs it again

        assertThat(db.sql("SELECT id || ' ' || repo_url || ' ' || branch || ' ' || repo_name || ' ' || provider FROM repo_branch ORDER BY id")
                .query(String.class).list()).hasSize(2).isEqualTo(branchesBefore);
        assertThat(db.sql("SELECT count(*) FROM repo_branch WHERE import_key IS NULL").query(Integer.class).single()).isEqualTo(2);
        assertThat(db.sql("SELECT id || ':' || branch_id || ':' || fingerprint FROM snapshot ORDER BY id").query(String.class).list()).isEqualTo(snapshotsBefore);
        assertThat(db.sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'REPO_BRANCH' AND is_nullable = 'YES' ORDER BY column_name")
                .query(String.class).list()).containsExactly("BRANCH", "IMPORT_KEY", "PROVIDER", "REPO_NAME", "REPO_URL");
        // partial identities now fit, several NULL tuples never collide, and both uniqueness rules still hold
        db.sql("INSERT INTO repo_branch (sbom_url, import_key) VALUES ('inline:json-upload', 'k1')").update();
        db.sql("INSERT INTO repo_branch (sbom_url, import_key, repo_url) VALUES ('inline:json-upload', 'k2', 'https://x.test/a')").update();
        assertThatThrownBy(() -> db.sql("INSERT INTO repo_branch (sbom_url, import_key) VALUES ('inline:json-upload', 'k1')").update())
                .hasMessageContaining("Unique");
        assertThatThrownBy(() -> db.sql("INSERT INTO repo_branch (repo_url, branch, sbom_url) VALUES ('https://x.test/a', 'main', 'inline:json-upload')").update())
                .hasMessageContaining("Unique");
        assertThat(db.sql("SELECT min(id) FROM repo_branch WHERE import_key IS NOT NULL").query(Long.class).single()).isGreaterThan(maxBefore);
    }

    @Test
    void interruptedUpgradeAddsMissingImportKeyUniqueness() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:interrupted-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcClient db = JdbcClient.create(ds);
        // a start that stopped after ADD COLUMN import_key: the column exists, its unique constraint does not;
        // a non-unique and a composite unique index on import_key must not count as that constraint
        db.sql("""
                CREATE TABLE repo_branch (id BIGINT AUTO_INCREMENT PRIMARY KEY, repo_url VARCHAR(1000) NOT NULL, branch VARCHAR(500) NOT NULL,
                    repo_name VARCHAR(300) NOT NULL, provider VARCHAR(50) NOT NULL, sbom_url VARCHAR(2000) NOT NULL, import_key VARCHAR(64),
                    UNIQUE (repo_url, branch))""").update();
        db.sql("CREATE INDEX ix_import_key_plain ON repo_branch (import_key)").update();
        db.sql("CREATE UNIQUE INDEX ix_import_key_composite ON repo_branch (import_key, sbom_url)").update();
        db.sql("INSERT INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) VALUES ('https://x.test/a', 'main', 'A', 'other', 'inline:json-upload')").update();
        db.sql("INSERT INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) VALUES ('https://x.test/a', 'dev', 'A', 'github', 'inline:json-upload')").update();
        var before = db.sql("SELECT id || ' ' || repo_url || ' ' || branch || ' ' || repo_name || ' ' || provider || ' ' || COALESCE(import_key, '-') FROM repo_branch ORDER BY id")
                .query(String.class).list();

        SchemaUpgrade.upgrade(ds);
        SchemaUpgrade.upgrade(ds); // repeat start: nothing more to add

        assertThat(db.sql("SELECT id || ' ' || repo_url || ' ' || branch || ' ' || repo_name || ' ' || provider || ' ' || COALESCE(import_key, '-') FROM repo_branch ORDER BY id")
                .query(String.class).list()).hasSize(2).isEqualTo(before);
        db.sql("INSERT INTO repo_branch (sbom_url, import_key) VALUES ('inline:json-upload', 'k1')").update();
        assertThatThrownBy(() -> db.sql("INSERT INTO repo_branch (sbom_url, import_key) VALUES ('other-sbom', 'k1')").update())
                .hasMessageContaining("Unique");
        assertThatThrownBy(() -> db.sql("INSERT INTO repo_branch (repo_url, branch, sbom_url) VALUES ('https://x.test/a', 'main', 'inline:json-upload')").update())
                .hasMessageContaining("Unique");
    }
}
