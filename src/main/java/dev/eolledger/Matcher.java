package dev.eolledger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps an SBOM package to an endoflife.date product and release cycle.
 * Precedence: exact normalized purl listed by exactly one product, then explicit mapping
 * (normalized purl or exact package name), then — only for packages without a purl — a package
 * name equal to a product name. Anything else stays unmatched.
 */
public final class Matcher {

    public record Match(String product, String cycle, String evidence) {
    }

    private static final Pattern NUMERIC = Pattern.compile("\\d+(\\.\\d+)*");
    // Pre-releases (-rc1, -SNAPSHOT, .M1), build metadata and other suffixes are deliberately not matched.
    private static final Pattern PLAIN_RELEASE = Pattern.compile("[vV]?(\\d+(?:\\.\\d+)*)(?:\\.(?i:Final|RELEASE|GA))?");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9.+-]*");
    /** purl types whose namespace and name are case-insensitive per the purl specification. */
    static final Set<String> CASE_INSENSITIVE = Set.of("npm", "pypi", "github", "bitbucket", "deb", "nuget", "composer", "hex", "apk");

    private final Map<String, Set<String>> productsByPurl;
    private final Map<String, List<String>> cyclesByProduct;
    private final Map<String, String> mappings;

    public Matcher(Map<String, Set<String>> productsByPurl, Map<String, List<String>> cyclesByProduct, Map<String, String> mappings) {
        this.productsByPurl = productsByPurl;
        this.cyclesByProduct = cyclesByProduct;
        this.mappings = mappings;
    }

    public Match match(String name, String version, String purl) {
        String norm = normalizePurl(purl);
        if (purl != null && norm == null) {
            return new Match(null, null, "malformed purl " + purl + "; not matched by package name either");
        }
        String product = null;
        String evidence;
        Set<String> listed = norm == null ? Set.of() : productsByPurl.getOrDefault(norm, Set.of());
        if (listed.size() == 1) {
            product = listed.iterator().next();
            evidence = "purl " + norm + " is an endoflife.date identifier of " + product;
        } else if (norm != null && mappings.containsKey(norm)) {
            product = mappings.get(norm);
            evidence = "explicit mapping " + norm + " = " + product;
        } else if (name != null && mappings.containsKey(name.toLowerCase(Locale.ROOT))) {
            product = mappings.get(name.toLowerCase(Locale.ROOT));
            evidence = "explicit mapping by package name " + name + " = " + product;
        } else if (listed.size() > 1) {
            return new Match(null, null, "purl " + norm + " is listed by several products " + String.join(", ", listed.stream().sorted().toList())
                    + "; add an explicit mapping to choose one");
        } else if (norm == null && name != null && cyclesByProduct.containsKey(name.toLowerCase(Locale.ROOT))) {
            product = name.toLowerCase(Locale.ROOT);
            evidence = "package has no purl; its name equals product " + product;
        } else {
            return new Match(null, null, norm == null
                    ? "No catalogue identifier or mapping for package name " + name + " (no purl)"
                    : "No catalogue identifier or mapping for " + norm);
        }
        String cycle = matchCycle(version, cyclesByProduct.getOrDefault(product, List.of()));
        if (cycle == null && !isPlainRelease(version)) {
            evidence += "; version " + version + " is not a plain release version (digits and dots, optional leading v or .Final/.RELEASE/.GA), so no cycle is guessed";
        } else if (cycle == null) {
            evidence += "; no release cycle of " + product + " matches version " + version;
        } else {
            evidence += "; version " + version + " falls in cycle " + cycle;
        }
        return new Match(product, cycle, evidence);
    }

    /**
     * Package identity {@code pkg:type/namespace/name}: version, qualifiers and subpath removed, each
     * segment percent-decoded (a decoded '/' or '%' is re-encoded so segments cannot collide).
     * Scheme and type are lower-cased; namespace/name only for types the purl spec defines as
     * case-insensitive (see {@link #CASE_INSENSITIVE}); pypi also maps '_' to '-'. Maven and all
     * other types keep their case. Returns null for anything that is not a well-formed purl.
     */
    public static String normalizePurl(String purl) {
        if (purl == null) return null;
        String p = purl.trim();
        if (!p.regionMatches(true, 0, "pkg:", 0, 4)) return null;
        p = p.substring(4).replaceFirst("^/+", "").replaceAll("[?#].*$", "");
        int slash = p.indexOf('/');
        if (slash <= 0) return null;
        String type = p.substring(0, slash).toLowerCase(Locale.ROOT);
        if (!TYPE.matcher(type).matches()) return null;
        String rest = p.substring(slash + 1).replaceAll("/+$", "");
        int at = rest.indexOf('@', rest.lastIndexOf('/') + 1);
        if (at >= 0) rest = rest.substring(0, at);
        if (rest.isEmpty()) return null;
        List<String> segments = new ArrayList<>();
        for (String raw : rest.split("/", -1)) {
            if (raw.isEmpty()) return null;
            String s;
            try {
                s = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            s = s.replace("%", "%25").replace("/", "%2F");
            if (CASE_INSENSITIVE.contains(type)) s = s.toLowerCase(Locale.ROOT);
            if (type.equals("pypi")) s = s.replace('_', '-');
            segments.add(s);
        }
        return "pkg:" + type + "/" + String.join("/", segments);
    }

    /** True for digits-and-dots versions with an optional leading v and optional .Final/.RELEASE/.GA suffix. */
    static boolean isPlainRelease(String version) {
        return version != null && PLAIN_RELEASE.matcher(version.trim()).matches();
    }

    /** Longest numeric cycle whose dot components are a prefix of a plain release version's components. */
    public static String matchCycle(String version, Collection<String> cycles) {
        if (version == null) return null;
        var m = PLAIN_RELEASE.matcher(version.trim());
        if (!m.matches()) return null;
        List<String> v = Arrays.asList(m.group(1).split("\\."));
        String best = null;
        int bestLen = 0;
        for (String c : cycles) {
            if (!NUMERIC.matcher(c).matches()) continue;
            List<String> parts = Arrays.asList(c.split("\\."));
            if (parts.size() > v.size() || parts.size() <= bestLen) continue;
            if (sameNumbers(parts, v.subList(0, parts.size()))) {
                best = c;
                bestLen = parts.size();
            }
        }
        return best;
    }

    private static boolean sameNumbers(List<String> a, List<String> b) {
        List<String> x = new ArrayList<>();
        List<String> y = new ArrayList<>();
        a.forEach(s -> x.add(s.replaceFirst("^0+(?=\\d)", "")));
        b.forEach(s -> y.add(s.replaceFirst("^0+(?=\\d)", "")));
        return x.equals(y);
    }
}
