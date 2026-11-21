package dev.eolledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Imports dependency-inventory JSON uploads (see {@link InventoryJson}). Every entry is validated before anything is
 * written; then one transaction upserts the listed branches (branches not in the file are untouched) and appends a
 * snapshot only when a branch's inventory differs from that branch's latest snapshot. Nothing is ever fetched.
 */
@Service
public class ImportService {

    public record Result(int rows, int newSnapshots, String message) {
    }

    public static class ImportFailedException extends RuntimeException {
        private final List<String> errors;

        ImportFailedException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = errors;
        }

        public List<String> errors() {
            return errors;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DbTime time;
    private final CatalogService catalog;

    public ImportService(JdbcClient db, TransactionTemplate tx, Clock clock, DbTime time, CatalogService catalog) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.time = time;
        this.catalog = catalog;
    }

    /** Children before parents, so the foreign keys hold at every step. */
    private static final List<String> DATA_TABLES = List.of("dependency", "snapshot_inventory", "snapshot", "repo_branch", "import_run",
            "product_identifier", "release_cycle", "product", "sync_status");

    /**
     * Deletes every imported row and the synced catalogue in one transaction; schema and configuration stay.
     * Holds this service's monitor (imports) and then the catalogue's (sync), so neither can interleave with the clear.
     */
    public synchronized void clearAll() {
        synchronized (catalog) {
            try {
                tx.executeWithoutResult(s -> DATA_TABLES.forEach(t -> db.sql("DELETE FROM " + t).update()));
            } catch (DataAccessException e) {
                log.warn("Clear all data rolled back", e);
                throw new ImportFailedException(List.of("Database write failed, so nothing was cleared; see the server log"));
            }
        }
    }

    /**
     * SBOM location stored for branches whose inventory came from a JSON upload: not a URL, never fetched.
     * Rows written by earlier versions (which fetched SBOMs from a registry CSV) keep their stored URL and are shown as-is.
     */
    public static final String INLINE = "inline:json-upload";

    /** Imports a dependency-inventory JSON root array; nothing is fetched. */
    public synchronized Result importJson(String json, String source) {
        List<InventoryJson.Entry> entries;
        try {
            entries = InventoryJson.parse(json);
        } catch (ImportFailedException e) {
            recordRun(source, false, 0, 0, String.join("; ", e.errors()));
            throw e;
        }
        return commit(entries, source);
    }

    private Result commit(List<InventoryJson.Entry> entries, String source) {
        int rows = entries.size();
        Instant now = clock.instant();
        int created;
        try {
            created = tx.execute(s -> {
                int n = 0;
                for (InventoryJson.Entry e : entries) n += store(e, now);
                return n;
            });
        } catch (DataAccessException e) {
            // details stay in the server log; the UI and import history get a message without SQL or connection details
            log.warn("Import from {} rolled back", source, e);
            String msg = "Database write failed, so nothing was changed (for example a value longer than the database allows); see the server log";
            recordRun(source, false, rows, 0, msg);
            throw new ImportFailedException(List.of(msg));
        }
        String msg = rows + " row(s) imported, " + created + " new snapshot(s), " + (rows - created) + " unchanged";
        recordRun(source, true, rows, created, msg);
        return new Result(rows, created, msg);
    }

    private int store(InventoryJson.Entry e, Instant now) {
        InventoryJson.Row r = e.row();
        // shortcut: repo_url/branch lookup has no usable index on MySQL (too long); fine for thousands of branches, add a digest column lookup beyond that.
        Long existing = db.sql("SELECT id FROM repo_branch WHERE repo_url = ? AND branch = ?").params(r.repoUrl(), r.branch()).query(Long.class).optional().orElse(null);
        long branchId;
        if (existing != null) {
            branchId = existing;
            db.sql("UPDATE repo_branch SET repo_name = ?, provider = ?, sbom_url = ? WHERE id = ?").params(r.repoName(), r.provider(), r.sbomUrl(), branchId).update();
        } else {
            var branchKey = new GeneratedKeyHolder();
            db.sql("INSERT INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) VALUES (?, ?, ?, ?, ?)")
                    .params(r.repoUrl(), r.branch(), r.repoName(), r.provider(), r.sbomUrl()).update(branchKey, "id");
            branchId = branchKey.getKey().longValue();
        }
        String latest = db.sql("SELECT fingerprint FROM snapshot WHERE branch_id = ? ORDER BY observed_at DESC, id DESC LIMIT 1")
                .param(branchId).query(String.class).optional().orElse(null);
        if (e.fingerprint().equals(latest)) return 0;
        var key = new GeneratedKeyHolder();
        db.sql("INSERT INTO snapshot (branch_id, observed_at, sbom_url, fingerprint) VALUES (?, ?, ?, ?)")
                .params(branchId, time.param(now), r.sbomUrl(), e.fingerprint()).update(key, "id");
        long snapshotId = key.getKey().longValue();
        for (InventoryJson.Dep d : e.deps()) {
            db.sql("INSERT INTO dependency (snapshot_id, name, version, purl) VALUES (?, ?, ?, ?)")
                    .params(snapshotId, d.name(), d.version(), d.purl()).update();
        }
        db.sql("INSERT INTO snapshot_inventory (snapshot_id, payload) VALUES (?, ?)").params(snapshotId, e.payload()).update();
        return 1;
    }

    private void recordRun(String source, boolean ok, int rows, int created, String message) {
        db.sql("INSERT INTO import_run (ran_at, source, ok, rows_total, new_snapshots, message) VALUES (?, ?, ?, ?, ?, ?)")
                .params(time.param(clock.instant()), source, ok, rows, created, message.length() > 3900 ? message.substring(0, 3900) + "…" : message)
                .update();
    }

    /** The one downloadable bundled sample: the resolved demo dependency-inventory JSON. */
    static final String SAMPLE = "demo-dependency-inventory.json";

    /** Bundled sample text (only the fixed name under classpath:samples/). */
    public static String sample(String name) {
        if (!SAMPLE.equals(name)) throw new IllegalArgumentException("unknown sample");
        try (InputStream in = new ClassPathResource("samples/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
