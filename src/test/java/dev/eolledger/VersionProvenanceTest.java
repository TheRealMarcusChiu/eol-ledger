package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Quality I1 / M1 / M2: version provenance at read time, and deterministic history diffs and counts. */
class VersionProvenanceTest extends WebTestBase {

    @Autowired Ledger ledger;

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** Rows written straight to the DB, as data stored by earlier builds would be. */
    void storeLegacy(long snapshotId, String name, String version, String purl) {
        db.sql("INSERT INTO dependency (snapshot_id, name, version, purl) VALUES (?, ?, ?, ?)").params(snapshotId, name, version, purl).update();
    }

    @Test
    void storedConflictingAndMissingVersionsRenderConservatively() throws Exception {
        seed();
        long branch = branchId("https://dev.azure.com/acme/P/_git/beta", "main");
        long snap = db.sql("SELECT id FROM snapshot WHERE branch_id = ?").param(branch).query(Long.class).single();
        storeLegacy(snap, "react", "18.2.0", "pkg:npm/react@19.0.0");   // CycloneDX-style conflict
        storeLegacy(snap, "react-dom", null, "pkg:npm/react@18.3.1");   // SPDX package without versionInfo

        String html = page("/repos/" + branch);

        assertThat(html).contains("18.2.0 (purl: 19.0.0)").contains("conflicts");
        assertThat(html).contains("18.3.1 (from purl)").doesNotContain(">not stated<");
        long missing = db.sql("SELECT id FROM dependency WHERE name = 'react-dom'").query(Long.class).single();
        long conflict = db.sql("SELECT id FROM dependency WHERE snapshot_id = ? AND name = 'react'").param(snap).query(Long.class).single();
        // missing version resolved from the purl: row goes to the cycle; conflict: row goes to the unknown-cycle detail
        assertThat(html).contains("<a href=\"/products/react?cycle=18\">react-dom</a>");
        assertThat(html).contains("<a href=\"/repos/" + branch + "/deps/" + conflict + "\">react</a>");
        assertThat(page("/repos/" + branch + "/deps/" + conflict)).contains("conflicts").contains("18.2.0 (purl: 19.0.0)");
        assertThat(page("/products/react?cycle=18")).contains("18.3.1");
        assertThat(missing).isPositive();
    }

    @Test
    void historyCountsRowsAndOrdersVersionsDeterministically() {
        TestSupport.REPLIES.put("/v.json", TestSupport.Reply.ok("{\"bomFormat\":\"CycloneDX\",\"components\":["
                + "{\"name\":\"c\",\"version\":\"2.0\",\"purl\":\"pkg:npm/c@2.0\"},"
                + "{\"name\":\"c\",\"version\":\"1.0\",\"purl\":\"pkg:npm/c@1.0\"},"
                + "{\"name\":\"odd\",\"version\":\"1, 5\",\"purl\":\"pkg:npm/odd@1, 5\"},"
                + "{\"name\":\"bare\"}]}"));
        String csv = "repo_name,repo_url,provider,branch,sbom_url\nV,https://github.com/acme/v,github,main," + TestSupport.base() + "/v.json\n";
        imports.importCsv(csv, "test");
        long branch = branchId("https://github.com/acme/v", "main");
        long first = db.sql("SELECT id FROM snapshot WHERE branch_id = ?").param(branch).query(Long.class).single();
        // a later snapshot with the same packages plus one more, stored with rows in the opposite order
        CLOCK.now = CLOCK.now.plusSeconds(60);
        db.sql("INSERT INTO snapshot (branch_id, observed_at, sbom_url, fingerprint) VALUES (?, ?, 'x', 'manual')")
                .params(branch, java.sql.Timestamp.from(CLOCK.now)).update();
        long second = db.sql("SELECT max(id) FROM snapshot").query(Long.class).single();
        storeLegacy(second, "new", "1", "pkg:npm/new@1");
        storeLegacy(second, "bare", null, null);
        storeLegacy(second, "odd", "1, 5", "pkg:npm/odd@1, 5");
        storeLegacy(second, "c", "1.0", "pkg:npm/c@1.0");
        storeLegacy(second, "c", "2.0", "pkg:npm/c@2.0");

        var timeline = ledger.timeline(branch);

        assertThat(timeline.get(0).id()).isEqualTo(first);
        assertThat(timeline.get(0).depCount()).isEqualTo(4);
        assertThat(timeline.get(0).diff().added()).contains("pkg:npm/c 1.0, 2.0", "bare (no version)").noneMatch(s -> s.contains("null"));
        assertThat(timeline.get(1).depCount()).isEqualTo(5);
        assertThat(timeline.get(1).diff().changed()).isEmpty();
        assertThat(timeline.get(1).diff().added()).containsExactly("pkg:npm/new 1");
    }
}
