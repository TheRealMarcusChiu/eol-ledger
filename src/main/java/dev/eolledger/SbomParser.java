package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads CycloneDX JSON and SPDX 2.x JSON into a sorted, de-duplicated package list.
 * Accepted profile (anything else rejects the whole SBOM, so a malformed file can never
 * replace a good inventory): CycloneDX needs a top-level {@code components} array (empty = no
 * packages), every component an object with a string {@code name}, optional string
 * {@code group}/{@code version}/{@code purl} and an optional nested {@code components} array.
 * SPDX needs a {@code packages} array of objects with a string {@code name}, optional string
 * {@code versionInfo} and an optional {@code externalRefs} array whose purl locators are strings.
 * A purl, when present, must be a well-formed {@code pkg:type/name}.
 */
public final class SbomParser {

    public record Dep(String name, String version, String purl) {
    }

    static final Comparator<Dep> ORDER = Comparator.comparing(Dep::name)
            .thenComparing(Dep::version, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Dep::purl, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final ObjectMapper JSON = new ObjectMapper();

    private SbomParser() {
    }

    public static List<Dep> parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("SBOM is not valid JSON (only CycloneDX JSON and SPDX 2.x JSON are supported)");
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("SBOM is not a JSON object");
        TreeSet<Dep> deps = new TreeSet<>(ORDER);
        if ("CycloneDX".equals(root.path("bomFormat").asText())) {
            cyclone(array(root, "components", "CycloneDX"), "components", deps);
        } else if (root.path("spdxVersion").asText().startsWith("SPDX-2.")) {
            spdx(root, deps);
        } else {
            throw new IllegalArgumentException("SBOM is neither CycloneDX JSON (bomFormat) nor SPDX 2.x JSON (spdxVersion)");
        }
        return new ArrayList<>(deps);
    }

    private static void cyclone(JsonNode components, String where, Set<Dep> out) {
        for (int i = 0; i < components.size(); i++) {
            String at = where + "[" + i + "]";
            JsonNode c = record(components.get(i), at);
            String name = required(c, "name", at);
            String group = optional(c, "group", at);
            out.add(new Dep(group == null ? name : group + ":" + name, optional(c, "version", at), purl(optional(c, "purl", at), at)));
            if (c.has("components")) cyclone(array(c, "components", at), at + ".components", out);
        }
    }

    private static void spdx(JsonNode root, Set<Dep> out) {
        Set<String> described = new HashSet<>();
        root.path("documentDescribes").forEach(n -> described.add(n.asText()));
        for (JsonNode r : root.path("relationships")) {
            if ("DESCRIBES".equals(r.path("relationshipType").asText()) && "SPDXRef-DOCUMENT".equals(r.path("spdxElementId").asText())) {
                described.add(r.path("relatedSpdxElement").asText());
            }
        }
        JsonNode packages = array(root, "packages", "SPDX");
        for (int i = 0; i < packages.size(); i++) {
            String at = "packages[" + i + "]";
            JsonNode p = record(packages.get(i), at);
            String name = required(p, "name", at);
            if (described.contains(p.path("SPDXID").asText())) continue;
            String purl = null;
            if (p.has("externalRefs")) {
                JsonNode refs = array(p, "externalRefs", at);
                for (int j = 0; j < refs.size(); j++) {
                    String refAt = at + ".externalRefs[" + j + "]";
                    JsonNode ref = record(refs.get(j), refAt);
                    if ("purl".equals(ref.path("referenceType").asText())) purl = purl(required(ref, "referenceLocator", refAt), refAt);
                }
            }
            out.add(new Dep(name, optional(p, "versionInfo", at), purl));
        }
    }

    private static JsonNode array(JsonNode parent, String field, String where) {
        JsonNode a = parent.get(field);
        if (a == null || !a.isArray()) throw new IllegalArgumentException("SBOM " + where + " must have a \"" + field + "\" array (use [] for none)");
        return a;
    }

    private static JsonNode record(JsonNode n, String where) {
        if (!n.isObject()) throw new IllegalArgumentException("SBOM " + where + " must be an object");
        return n;
    }

    private static String required(JsonNode n, String field, String where) {
        String v = optional(n, field, where);
        if (v == null) throw new IllegalArgumentException("SBOM " + where + " has no \"" + field + "\"");
        return v;
    }

    /** A string field, or null when absent, JSON null, blank, NOASSERTION or NONE. Any other type is rejected. */
    private static String optional(JsonNode n, String field, String where) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw new IllegalArgumentException("SBOM " + where + " \"" + field + "\" must be a string");
        String s = v.asText().trim();
        return s.isEmpty() || s.equals("NOASSERTION") || s.equals("NONE") ? null : s;
    }

    private static String purl(String purl, String where) {
        if (purl != null && Matcher.normalizePurl(purl) == null) throw new IllegalArgumentException("SBOM " + where + " has a malformed purl");
        return purl;
    }
}
