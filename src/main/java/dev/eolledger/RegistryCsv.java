package dev.eolledger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The repository registry CSV: exact header {@value #HEADER}. Every row is validated and the whole
 * file is rejected with all row errors if any row is invalid. Error messages never echo URL values.
 */
public final class RegistryCsv {

    public static final String HEADER = "repo_name,repo_url,provider,branch,sbom_url";
    public static final List<String> PROVIDERS = List.of("github", "gitlab", "azure-devops", "tfs", "bitbucket", "other");
    static final int MAX_ROWS = 5000;
    private static final Pattern SAMPLE = Pattern.compile("classpath:samples/[A-Za-z0-9][A-Za-z0-9._-]*\\.json");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    public record Row(String repoName, String repoUrl, String provider, String branch, String sbomUrl) {
    }

    public static class InvalidCsvException extends RuntimeException {
        private final List<String> errors;

        public InvalidCsvException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = errors;
        }

        public List<String> errors() {
            return errors;
        }
    }

    private RegistryCsv() {
    }

    public static List<Row> parse(String text, List<EolProperties.Host> hosts) {
        List<List<String>> records = records(text.startsWith("﻿") ? text.substring(1) : text);
        if (records.isEmpty() || !String.join(",", records.get(0)).trim().equalsIgnoreCase(HEADER)) {
            throw new InvalidCsvException(List.of("First line must be exactly the header: " + HEADER));
        }
        List<String> errors = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> f = records.get(i);
            int line = i + 1;
            if (f.size() == 1 && f.get(0).isBlank()) continue;
            if (f.size() != 5) {
                errors.add("line " + line + ": expected 5 columns, found " + f.size());
                continue;
            }
            int before = errors.size();
            String name = f.get(0).trim();
            String repoUrl = normalizeRepoUrl(f.get(1).trim());
            String provider = f.get(2).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
            String branch = f.get(3).trim();
            String sbom = f.get(4).trim();
            if (name.isEmpty() || name.length() > 300) errors.add("line " + line + ": repo_name is required (max 300 characters)");
            if (repoUrl == null) errors.add("line " + line + ": repo_url must be an http(s) URL with a host and no credentials, query or fragment");
            if (!PROVIDERS.contains(provider)) errors.add("line " + line + ": provider must be one of " + String.join(", ", PROVIDERS));
            if (branch.isEmpty() || branch.length() > 500 || !branch.chars().allMatch(c -> c > 32 && c != 127)) {
                errors.add("line " + line + ": branch is required and must not contain spaces or control characters");
            }
            String sbomError = checkSbomUrl(sbom, hosts);
            if (sbomError != null) errors.add("line " + line + ": " + sbomError);
            if (errors.size() == before) {
                if (!seen.add(repoUrl + "\n" + branch)) {
                    errors.add("line " + line + ": duplicate repo_url + branch in this file");
                } else {
                    rows.add(new Row(name, repoUrl, provider, branch, sbom));
                }
            }
        }
        if (errors.isEmpty() && rows.isEmpty()) errors.add("CSV has no data rows");
        if (rows.size() > MAX_ROWS) errors.add("CSV has more than " + MAX_ROWS + " rows");
        if (!errors.isEmpty()) throw new InvalidCsvException(errors);
        return rows;
    }

    /** Lower-cases scheme/host and drops a trailing slash or .git so the same repo is one identity. */
    static String normalizeRepoUrl(String raw) {
        URI u = uri(raw);
        if (u == null || u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return null;
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        path = path.replaceAll("/+$", "").replaceAll("\\.git$", "").replaceAll("/+$", "");
        String port = u.getPort() < 0 ? "" : ":" + u.getPort();
        return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + port + path;
    }

    /** Returns null when the SBOM location is allowed, otherwise a message that does not echo the value. */
    public static String checkSbomUrl(String raw, List<EolProperties.Host> hosts) {
        if (raw.startsWith("classpath:")) {
            return SAMPLE.matcher(raw).matches() && !raw.contains("..") ? null
                    : "sbom_url classpath locations are limited to bundled classpath:samples/<file>.json";
        }
        URI u = uri(raw);
        if (u == null || u.getScheme() == null || u.getHost() == null) return "sbom_url must be an absolute HTTPS URL";
        if (u.getRawUserInfo() != null) return "sbom_url must not embed credentials; configure a per-host token instead";
        if (u.getRawQuery() != null || u.getRawFragment() != null) return "sbom_url must not have a query string or fragment";
        if (u.getRawPath() != null && u.getRawPath().contains("..")) return "sbom_url must not contain path traversal";
        String host = u.getHost().toLowerCase(Locale.ROOT);
        EolProperties.Host allowed = hosts.stream().filter(h -> h.host() != null && h.host().equalsIgnoreCase(host)).findFirst().orElse(null);
        if (allowed == null) return "sbom_url host " + host + " is not in the SBOM host allowlist (eol.sbom.hosts)";
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return null;
        if (scheme.equals("http") && allowed.allowHttp() && LOOPBACK.contains(host)) return null;
        return "sbom_url must use HTTPS (plain HTTP is only allowed for loopback hosts configured with allow-http)";
    }

    private static URI uri(String raw) {
        try {
            return new URI(raw);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Minimal RFC 4180 reader: quoted fields, doubled quotes, CRLF or LF line ends. */
    static List<List<String>> records(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> rec = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                rec.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                rec.add(field.toString());
                field.setLength(0);
                out.add(rec);
                rec = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !rec.isEmpty()) {
            rec.add(field.toString());
            out.add(rec);
        }
        return out;
    }
}
