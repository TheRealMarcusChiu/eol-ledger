package dev.eolledger;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Storage contract on the default embedded H2 database. */
class H2StorageTest extends StorageContractTest {

    @Test
    void timestampsWrittenByEarlierBuildsReadBackAsTheSameInstant() {
        // earlier builds bound java.sql.Timestamp (JVM-zone based); those rows must not be reinterpreted
        Instant t = Instant.parse("2026-11-21T22:59:59.999999Z");
        db.sql("INSERT INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) VALUES ('https://github.com/acme/old', 'main', 'Old', 'github', 'x')").update();
        long id = branchId("https://github.com/acme/old", "main");
        db.sql("INSERT INTO snapshot (branch_id, observed_at, sbom_url, fingerprint) VALUES (?, ?, 'x', 'f')").params(id, Timestamp.from(t)).update();

        assertThat(ledger.timeline(id).get(0).observedAt()).isEqualTo(t);
    }
}
