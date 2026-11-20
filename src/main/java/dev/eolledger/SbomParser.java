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

/** Reads CycloneDX JSON (nested components) and SPDX 2.x JSON into a sorted, de-duplicated package list. */
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
            cyclone(root.path("components"), deps);
        } else if (root.path("spdxVersion").asText().startsWith("SPDX-2.")) {
            spdx(root, deps);
        } else {
            throw new IllegalArgumentException("SBOM is neither CycloneDX JSON (bomFormat) nor SPDX 2.x JSON (spdxVersion)");
        }
        return new ArrayList<>(deps);
    }

    private static void cyclone(JsonNode components, Set<Dep> out) {
        for (JsonNode c : components) {
            String name = text(c, "name");
            if (name != null) {
                String group = text(c, "group");
                out.add(new Dep(group == null ? name : group + ":" + name, text(c, "version"), text(c, "purl")));
            }
            cyclone(c.path("components"), out);
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
        for (JsonNode p : root.path("packages")) {
            String name = text(p, "name");
            if (name == null || described.contains(p.path("SPDXID").asText())) continue;
            String purl = null;
            for (JsonNode ref : p.path("externalRefs")) {
                if ("purl".equals(ref.path("referenceType").asText())) purl = text(ref, "referenceLocator");
            }
            out.add(new Dep(name, text(p, "versionInfo"), purl));
        }
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank() || v.equals("NOASSERTION") || v.equals("NONE")) return null;
        return v.trim();
    }
}
