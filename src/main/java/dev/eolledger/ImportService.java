package dev.eolledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Imports the repository registry CSV. All rows are validated and every SBOM is fetched and parsed
 * before anything is written; then one transaction upserts listed rows (omitted rows are untouched)
 * and appends a snapshot only when a branch's normalized inventory (SBOM URL + sorted packages)
 * differs from that branch's latest snapshot.
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

    private record Fetched(RegistryCsv.Row row, List<SbomParser.Dep> deps, String fingerprint) {
    }

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final EolProperties props;
    private final Clock clock;

    public ImportService(JdbcClient db, TransactionTemplate tx, EolProperties props, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public Result importCsv(String csv, String source) {
        List<RegistryCsv.Row> rows;
        try {
            rows = RegistryCsv.parse(csv, props.sbom().hosts());
        } catch (RegistryCsv.InvalidCsvException e) {
            recordRun(source, false, 0, 0, String.join("; ", e.errors()));
            throw e;
        }
        return run(rows, source, 2);
    }

    /** Re-fetches every registered branch from the stored registry. */
    public Result refreshRegistry(String source) {
        List<RegistryCsv.Row> rows = db.sql("SELECT repo_name, repo_url, provider, branch, sbom_url FROM repo_branch ORDER BY id")
                .query((rs, i) -> new RegistryCsv.Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
        if (rows.isEmpty()) return new Result(0, 0, "No registered branches to refresh");
        return run(rows, source, 1);
    }

    /** Scheduled refresh: the configured CSV path when set, otherwise the stored registry. */
    public Result scheduledRefresh() {
        String path = props.refreshCsvPath();
        if (path == null || path.isBlank()) return refreshRegistry("scheduled registry refresh");
        try {
            return importCsv(Files.readString(Path.of(path), StandardCharsets.UTF_8), "scheduled CSV");
        } catch (IOException e) {
            String msg = "Could not read eol.refresh-csv-path (" + e.getClass().getSimpleName() + ")";
            recordRun("scheduled CSV", false, 0, 0, msg);
            throw new ImportFailedException(List.of(msg));
        }
    }

    private synchronized Result run(List<RegistryCsv.Row> rows, String source, int firstLine) {
        List<Fetched> fetched = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            RegistryCsv.Row row = rows.get(i);
            try {
                List<SbomParser.Dep> deps = SbomParser.parse(fetch(row.sbomUrl()));
                fetched.add(new Fetched(row, deps, fingerprint(row.sbomUrl(), deps)));
            } catch (IOException | IllegalArgumentException e) {
                String msg = e.getMessage().startsWith("SBOM") ? e.getMessage() : "SBOM " + e.getMessage();
                errors.add((firstLine == 2 ? "line " + (i + firstLine) : row.repoUrl() + " @ " + row.branch()) + ": " + msg);
            }
        }
        if (!errors.isEmpty()) {
            String msg = String.join("; ", errors);
            recordRun(source, false, rows.size(), 0, msg);
            log.warn("Import from {} rejected: {}", source, msg);
            throw new ImportFailedException(errors);
        }
        Instant now = clock.instant();
        int created = tx.execute(s -> {
            int n = 0;
            for (Fetched f : fetched) n += store(f, now);
            return n;
        });
        String msg = rows.size() + " row(s) imported, " + created + " new snapshot(s), " + (rows.size() - created) + " unchanged";
        recordRun(source, true, rows.size(), created, msg);
        return new Result(rows.size(), created, msg);
    }

    private int store(Fetched f, Instant now) {
        RegistryCsv.Row r = f.row();
        db.sql("MERGE INTO repo_branch (repo_url, branch, repo_name, provider, sbom_url) KEY (repo_url, branch) VALUES (?, ?, ?, ?, ?)")
                .params(r.repoUrl(), r.branch(), r.repoName(), r.provider(), r.sbomUrl()).update();
        long branchId = db.sql("SELECT id FROM repo_branch WHERE repo_url = ? AND branch = ?").params(r.repoUrl(), r.branch()).query(Long.class).single();
        String latest = db.sql("SELECT fingerprint FROM snapshot WHERE branch_id = ? ORDER BY observed_at DESC, id DESC LIMIT 1")
                .param(branchId).query(String.class).optional().orElse(null);
        if (f.fingerprint().equals(latest)) return 0;
        var key = new GeneratedKeyHolder();
        db.sql("INSERT INTO snapshot (branch_id, observed_at, sbom_url, fingerprint) VALUES (?, ?, ?, ?)")
                .params(branchId, Timestamp.from(now), r.sbomUrl(), f.fingerprint()).update(key, "id");
        long snapshotId = key.getKey().longValue();
        for (SbomParser.Dep d : f.deps()) {
            db.sql("INSERT INTO dependency (snapshot_id, name, version, purl) VALUES (?, ?, ?, ?)")
                    .params(snapshotId, d.name(), d.version(), d.purl()).update();
        }
        return 1;
    }

    private byte[] fetch(String url) throws IOException {
        String problem = RegistryCsv.checkSbomUrl(url, props.sbom().hosts());
        if (problem != null) throw new Http.FetchException(problem);
        if (url.startsWith("classpath:")) {
            ClassPathResource res = new ClassPathResource(url.substring("classpath:".length()));
            if (!res.exists()) throw new Http.FetchException("bundled sample not found");
            try (InputStream in = res.getInputStream()) {
                return in.readNBytes((int) props.sbom().maxBytes());
            }
        }
        URI uri = URI.create(url);
        String token = props.sbom().hosts().stream()
                .filter(h -> h.host() != null && h.host().equalsIgnoreCase(uri.getHost()))
                .findFirst().map(EolProperties.Host::token).orElse(null); // a host without a token is fetched unauthenticated
        return Http.get(uri, token, props.sbom().timeout(), props.sbom().maxBytes());
    }

    static String fingerprint(String sbomUrl, List<SbomParser.Dep> deps) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(sbomUrl.getBytes(StandardCharsets.UTF_8));
            for (SbomParser.Dep d : deps) {
                md.update(("\n" + d.name() + "\t" + d.version() + "\t" + d.purl()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void recordRun(String source, boolean ok, int rows, int created, String message) {
        db.sql("INSERT INTO import_run (ran_at, source, ok, rows_total, new_snapshots, message) VALUES (?, ?, ?, ?, ?, ?)")
                .params(Timestamp.from(clock.instant()), source, ok, rows, created, message.length() > 3900 ? message.substring(0, 3900) + "…" : message)
                .update();
    }

    /** Bundled demo CSV text (only fixed names under classpath:samples/). */
    public static String sampleCsv(String name) {
        if (!name.matches("demo-registry(-update)?\\.csv")) throw new IllegalArgumentException("unknown sample");
        try (InputStream in = new ClassPathResource("samples/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
