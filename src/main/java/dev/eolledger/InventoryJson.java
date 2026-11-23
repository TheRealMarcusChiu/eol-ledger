package dev.eolledger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The dependency-inventory JSON upload: a root array of repository-branch entries, each carrying its own resolved
 * dependency rows (see README "Dependency inventory JSON"). Every entry and nested row is validated and the whole
 * file is rejected with all entry errors if any is invalid. Nothing referenced inside (SBOM URL, evidence paths,
 * local repository) is ever fetched. Unknown fields are kept in the stored payload.
 */
public final class InventoryJson {

    /** A code repo branch as stored in repo_branch; sbomUrl is always {@link ImportService#INLINE} for JSON uploads. */
    public record Row(String repoName, String repoUrl, String provider, String branch, String sbomUrl) {
    }

    /** One package identity + version in a snapshot's inventory (the unit the lifecycle views match). */
    public record Dep(String name, String version, String purl) {
    }

    static final Comparator<Dep> ORDER = Comparator.comparing(Dep::name)
            .thenComparing(Dep::version, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Dep::purl, Comparator.nullsFirst(Comparator.naturalOrder()));

    public static final List<String> PROVIDERS = List.of("github", "gitlab", "azure-devops", "tfs", "bitbucket", "other");
    static final int MAX_ENTRIES = 5000;

    /**
     * One validated entry: its branch row (SBOM location = {@link ImportService#INLINE}), the unique package
     * identities for the lifecycle views, the canonical payload kept per snapshot and the change fingerprint.
     */
    public record Entry(Row row, List<Dep> deps, String payload, String fingerprint) {
    }

    // duplicate keys would make the stored payload ambiguous; trailing data after the root would be silently ignored
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String, String> PURL_TYPE = Map.of("maven", "maven", "python", "pypi", "npm", "npm", "nuget", "nuget");
    private static final List<String> DEP_STRINGS = List.of("groupId", "artifactId", "type", "scope", "parentCoordinate", "projectCoordinate",
            "ecosystem", "packageManager", "projectPath", "recordSource");

    private InventoryJson() {
    }

    public static List<Entry> parse(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (IOException e) {
            throw new ImportService.ImportFailedException(List.of("File is not valid JSON (or repeats a key in one object)"));
        }
        if (root == null || !root.isArray()) throw new ImportService.ImportFailedException(List.of("JSON root must be a JSON array of repository branch entries"));
        if (root.isEmpty()) throw new ImportService.ImportFailedException(List.of("JSON array has no entries"));
        if (root.size() > MAX_ENTRIES) throw new ImportService.ImportFailedException(List.of("JSON has more than " + MAX_ENTRIES + " entries"));
        List<String> errors = new ArrayList<>();
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < root.size(); i++) {
            try {
                Entry e = entry(root.get(i));
                if (!seen.add(e.row().repoUrl() + "\n" + e.row().branch())) throw new IllegalArgumentException("duplicate url + branch in this file");
                out.add(e);
            } catch (IllegalArgumentException e) {
                errors.add("entry " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) throw new ImportService.ImportFailedException(errors);
        return out;
    }

    private static Entry entry(JsonNode n) {
        if (!n.isObject()) throw new IllegalArgumentException("must be an object");
        ObjectNode e = (ObjectNode) canonical(n);
        sortRows(e, "projects");
        sortRows(e, "dependencies");
        String name = text(e, "name", "", 300, true);
        String url = normalizeRepoUrl(text(e, "url", "", 1000, true));
        if (url == null) throw new IllegalArgumentException("url must be an http(s) URL with a host and no credentials, query or fragment");
        String branch = text(e, "branch", "", 500, false);
        if (branch == null || branch.isEmpty() || !branch.chars().allMatch(c -> c > 32 && c != 127)) {
            throw new IllegalArgumentException("branch is required and must not contain spaces or control characters (no default branch is assumed)");
        }
        String provider = text(e, "provider", "", 50, false);
        provider = provider == null ? "other" : provider.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("provider must be one of " + String.join(", ", PROVIDERS));
        e.put("url", url);
        e.put("provider", provider);
        text(e, "appId", "", 200, false);
        // the application/repository version (never an SBOM schema or dependency version); optional, trimmed
        String version = text(e, "version", "", 200, false);
        if (version != null && version.isBlank()) e.remove("version");
        else if (version != null) e.put("version", version.trim());
        text(e, "localRepository", "", 1000, false);
        if (e.has("schemaVersion") && !(e.get("schemaVersion").isInt() && e.get("schemaVersion").asInt() == 1)) throw new IllegalArgumentException("schemaVersion must be 1");
        if (e.has("demo") && !e.get("demo").isBoolean()) throw new IllegalArgumentException("\"demo\" must be true or false");

        if (e.has("sbom") && !e.get("sbom").isNull()) {
            JsonNode s = object(e.get("sbom"), "sbom");
            String sbomUrl = text(s, "url", "sbom.", 2000, false);
            if (sbomUrl != null && (normalizeRepoUrl(sbomUrl) == null || Ledger.safeLink(sbomUrl) == null)) {
                throw new IllegalArgumentException("sbom.url must be null or an http(s) URL without credentials, query or fragment (it is provenance only and never fetched)");
            }
            for (String f : List.of("format", "evidence")) text(s, f, "sbom.", 2000, false);
            String generated = text(s, "generatedAt", "sbom.", 100, false);
            try {
                if (generated != null) Instant.parse(generated);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("sbom.generatedAt must be an ISO-8601 UTC instant such as 2026-11-20T10:00:00Z");
            }
        }

        Set<String> paths = new HashSet<>();
        JsonNode projects = e.get("projects");
        if (projects != null && !projects.isNull()) {
            if (!projects.isArray()) throw new IllegalArgumentException("\"projects\" must be an array");
            for (int i = 0; i < projects.size(); i++) {
                String at = "projects[" + i + "].";
                JsonNode p = object(projects.get(i), "projects[" + i + "]");
                String path = text(p, "path", at, 500, true);
                if (!paths.add(path)) throw new IllegalArgumentException("duplicate project path " + path);
                for (String f : List.of("ecosystem", "packageManager", "coordinate")) text(p, f, at, 500, false);
                if (p.has("resolution") && !p.get("resolution").isNull()) {
                    JsonNode r = object(p.get("resolution"), at + "resolution");
                    for (String f : List.of("status", "command", "toolVersion", "tree")) text(r, f, at + "resolution.", 4000, false);
                }
            }
        }

        JsonNode deps = e.get("dependencies");
        if (deps == null || !deps.isArray()) throw new IllegalArgumentException("must have a \"dependencies\" array (use [] for none)");
        // one inventory row per package identity + version; every graph-context row stays in the payload
        TreeMap<String, Dep> unique = new TreeMap<>();
        List<Dep> all = new ArrayList<>();
        Map<String, String> purlFreeOrigin = new HashMap<>();
        for (int i = 0; i < deps.size(); i++) {
            String at = "dependencies[" + i + "]";
            JsonNode d = object(deps.get(i), at);
            Dep dep = dep(d, at, paths);
            all.add(dep);
            if (dep.purl() != null) continue;
            // without a purl only the name identifies the package: it must not merge packages from different ecosystems/coordinates
            String origin = d.path("ecosystem").asText("") + "\n" + d.path("groupId").asText("") + "\n" + d.path("artifactId").asText("");
            String seen = purlFreeOrigin.putIfAbsent(dep.name() + "\n" + dep.version(), origin);
            if (seen != null && !seen.equals(origin)) {
                throw new IllegalArgumentException(at + " " + dep.name() + " " + dep.version()
                        + " has no purl and another row with that name and version has a different ecosystem, groupId or artifactId; add a purl to both");
            }
        }
        all.sort(ORDER);
        for (Dep d : all) {
            String id = d.purl() != null ? Matcher.normalizePurl(d.purl()) : d.name();
            unique.putIfAbsent(id + "\n" + d.version(), d);
        }
        List<Dep> flat = new ArrayList<>(unique.values());
        flat.sort(ORDER);

        ObjectNode identity = e.deepCopy();
        if (identity.get("sbom") instanceof ObjectNode s) s.remove("generatedAt"); // regeneration time alone is not an inventory change
        return new Entry(new Row(name.trim(), url, provider, branch, ImportService.INLINE), flat, write(e), sha256("inline-json\n" + write(identity)));
    }

    private static Dep dep(JsonNode d, String at, Set<String> paths) {
        String name = text(d, "name", at + " ", 500, true).trim();
        String version = text(d, "version", at + " ", 200, true).trim();
        String purl = text(d, "purl", at + " ", 1000, false);
        for (String f : DEP_STRINGS) text(d, f, at + " ", 1000, false);
        if (purl != null) {
            String norm = Matcher.normalizePurl(purl);
            if (norm == null) throw new IllegalArgumentException(at + " has a malformed purl");
            String fromPurl = Matcher.purlVersion(purl);
            if (fromPurl == null || !fromPurl.equals(version)) {
                throw new IllegalArgumentException(at + " version " + version + " conflicts with purl version " + fromPurl + " (they must be identical)");
            }
            String eco = d.path("ecosystem").asText("").toLowerCase(Locale.ROOT);
            String type = norm.substring(4, norm.indexOf('/'));
            if (PURL_TYPE.containsKey(eco) && !PURL_TYPE.get(eco).equals(type)) {
                throw new IllegalArgumentException(at + " purl type " + type + " does not match ecosystem " + d.get("ecosystem").asText());
            }
        }
        String kind = text(d, "dependencyType", at + " ", 20, false);
        if (kind != null && !kind.equals("direct") && !kind.equals("transitive")) throw new IllegalArgumentException(at + " dependencyType must be direct or transitive");
        if (d.has("optional") && !d.get("optional").isBoolean() && !d.get("optional").isNull()) throw new IllegalArgumentException(at + " \"optional\" must be true or false");
        JsonNode depth = d.get("depth");
        if (depth != null && !depth.isNull()) {
            if (!depth.isInt() || depth.asInt() < 1) throw new IllegalArgumentException(at + " depth must be a whole number of at least 1");
            if ("direct".equals(kind) && depth.asInt() != 1) throw new IllegalArgumentException(at + " direct dependencies have depth 1");
            if ("transitive".equals(kind) && depth.asInt() < 2) throw new IllegalArgumentException(at + " transitive dependencies have depth 2 or more");
        }
        String path = text(d, "projectPath", at + " ", 500, false);
        if (path != null && !paths.isEmpty() && !paths.contains(path)) throw new IllegalArgumentException(at + " projectPath " + path + " is not a declared project path");
        if (d.has("verification") && !d.get("verification").isNull()) {
            JsonNode v = object(d.get("verification"), at + ".verification");
            for (String f : List.of("status", "source", "context")) text(v, f, at + ".verification.", 4000, false);
        }
        return new Dep(name, version, purl);
    }

    /** Lower-cases scheme/host and drops a trailing slash or .git so the same repo is one identity; null when not a plain http(s) URL. */
    static String normalizeRepoUrl(String raw) {
        URI u;
        try {
            u = new URI(raw);
        } catch (URISyntaxException e) {
            return null;
        }
        if (u.getScheme() == null || u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return null;
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        path = path.replaceAll("/+$", "").replaceAll("\\.git$", "").replaceAll("/+$", "");
        String port = u.getPort() < 0 ? "" : ":" + u.getPort();
        return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + port + path;
    }

    private static JsonNode object(JsonNode n, String where) {
        if (!n.isObject()) throw new IllegalArgumentException(where + " must be an object");
        return n;
    }

    /** A string field (null when absent or JSON null); any other type, or blank/too long when required, is rejected. */
    private static String text(JsonNode n, String field, String where, int max, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) throw new IllegalArgumentException(where.isEmpty() ? field + " is required" : where.trim() + " has no \"" + field + "\"");
            return null;
        }
        if (!v.isTextual()) throw new IllegalArgumentException(where + "\"" + field + "\" must be a string");
        if (required && v.asText().isBlank()) throw new IllegalArgumentException(where.isEmpty() ? field + " is required" : where.trim() + " has no \"" + field + "\"");
        if (v.asText().length() > max) throw new IllegalArgumentException(where + "\"" + field + "\" is longer than " + max + " characters");
        return v.asText();
    }

    /** Object keys sorted at every level; array order kept. */
    private static JsonNode canonical(JsonNode n) {
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            n.fields().forEachRemaining(f -> sorted.put(f.getKey(), canonical(f.getValue())));
            ObjectNode o = JSON.createObjectNode();
            sorted.forEach(o::set);
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            n.forEach(x -> a.add(canonical(x)));
            return a;
        }
        return n;
    }

    /** The entry's projects and dependencies are sets of rows: stored in a stable order so reordering is no change. */
    private static void sortRows(ObjectNode e, String field) {
        if (!(e.get(field) instanceof ArrayNode a)) return;
        List<JsonNode> rows = new ArrayList<>();
        a.forEach(rows::add);
        rows.sort(Comparator.comparing(InventoryJson::write));
        e.set(field, JSON.createArrayNode().addAll(rows));
    }

    static String write(JsonNode n) {
        try {
            return JSON.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
