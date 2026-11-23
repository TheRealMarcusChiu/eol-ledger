package dev.eolledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one uploaded SBOM file plus the operator's explicit repository metadata into a dependency-inventory JSON root
 * array with one entry, which then goes through {@link InventoryJson} validation and the normal import transaction.
 * The format is detected from the content, never the file name. Nothing referenced inside the file is ever fetched.
 */
final class SbomInventory {

    /** The repository branch the SBOM describes, as typed by the operator (never guessed from the document). */
    record Meta(String name, String url, String branch, String provider, String appId) {
    }

    static final int MAX_PACKAGES = 50_000;
    static final int MAX_DEPTH = 32;
    // only purl types the lifecycle views already know get an ecosystem; anything else keeps its purl and no ecosystem
    private static final Map<String, String> ECOSYSTEM = Map.of("pypi", "python", "maven", "maven", "npm", "npm", "nuget", "nuget");
    private static final Set<String> CYCLONEDX = Set.of("1.4", "1.5", "1.6");
    // component types that are not software packages: never a dependency row, version or not
    private static final Set<String> NOT_PACKAGES = Set.of("file", "data", "service", "license");
    /** The multipart upload limit (spring.servlet.multipart.max-file-size=10MB), checked again here at the adapter boundary. */
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final String XINCLUDE_NS = "http://www.w3.org/2001/XInclude";

    private final List<String> errors = new ArrayList<>();
    private final ArrayNode deps = InventoryJson.JSON.createArrayNode();
    private String format;
    /** The described application's own version, only when the document states it unambiguously; else null. */
    private String appVersion;
    /** Package version by SPDXID, to resolve the one described package. */
    private final Map<String, String> versionById = new java.util.HashMap<>();

    private SbomInventory() {
    }

    /** The one described package's version, or null when none or several are described. */
    private void described(Set<String> ids) {
        if (ids.size() == 1) appVersion = versionById.get(ids.iterator().next());
    }

    static String toInventoryJson(String text, String fileName, Meta meta) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw fail("The SBOM file is larger than 10 MB");
        SbomInventory s = new SbomInventory();
        String t =text.startsWith("﻿") ? text.substring(1) : text;
        String head = t.stripLeading();
        if (head.startsWith("{")) s.json(head);
        else if (head.startsWith("<")) s.xml(head);
        else if (head.lines().anyMatch(l -> l.strip().startsWith("SPDXVersion:"))) s.tagValue(head);
        else throw fail("The file is not a supported SBOM: expected CycloneDX 1.4-1.6 JSON or XML, SPDX 2.2/2.3 JSON or tag-value, or one ISO/IEC 19770-2:2015 SWID tag");
        if (!s.errors.isEmpty()) throw new ImportService.ImportFailedException(s.errors);

        ObjectNode e = InventoryJson.JSON.createObjectNode();
        e.put("name", meta.name());
        e.put("url", meta.url());
        e.put("branch", meta.branch());
        if (meta.provider() != null && !meta.provider().isBlank()) e.put("provider", meta.provider());
        if (meta.appId() != null && !meta.appId().isBlank()) e.put("appId", meta.appId().trim());
        if (s.appVersion != null) e.put("version", s.appVersion);
        ObjectNode sbom = e.putObject("sbom");
        sbom.put("format", s.format);
        sbom.put("evidence", "uploaded SBOM file " + safeName(fileName));
        e.set("dependencies", s.deps);
        return InventoryJson.write(InventoryJson.JSON.createArrayNode().add(e));
    }

    private static ImportService.ImportFailedException fail(String message) {
        return new ImportService.ImportFailedException(List.of(message));
    }

    /** A file name safe to show and store: letters, digits, dot, dash and underscore only. */
    static String safeName(String name) {
        if (name == null || name.isBlank()) return "(unnamed)";
        String n = name.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._-]", "_");
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    // ---- JSON serializations ----------------------------------------------------------------

    private void json(String text) {
        JsonNode root;
        try {
            root = InventoryJson.JSON.readTree(text);
        } catch (IOException e) {
            throw fail("The SBOM is not valid JSON (or repeats a key in one object, or has data after the document)");
        }
        if ("CycloneDX".equals(root.path("bomFormat").asText(null))) {
            String v = root.path("specVersion").asText("");
            if (!CYCLONEDX.contains(v)) throw fail("CycloneDX specVersion " + v + " is not supported; use 1.4, 1.5 or 1.6");
            format = "CycloneDX " + v + " JSON";
            JsonNode components = root.get("components");
            if (components == null || !components.isArray()) {
                throw fail("The CycloneDX BOM has no \"components\" array, so it cannot say which packages the branch has; use \"components\": [] for a deliberately empty SBOM");
            }
            cycloneDxJson(components, "components", 1);
            JsonNode app = root.path("metadata").path("component");
            if (app.isObject()) appVersion = str(app, "version");
            return;
        }
        if (root.has("spdxVersion")) {
            String v = spdxVersion(root.path("spdxVersion").asText(""));
            format = v + " JSON";
            JsonNode packages = root.get("packages");
            if (packages == null || !packages.isArray()) {
                throw fail("The SPDX document has no \"packages\" array, so it cannot say which packages the branch has; use \"packages\": [] for a deliberately empty SBOM");
            }
            if (packages.size() > MAX_PACKAGES) throw fail("The SBOM lists more than " + MAX_PACKAGES + " packages");
            // every package counts, including the described root; files, snippets and relationships are never packages
            for (int i = 0; i < packages.size(); i++) {
                JsonNode p = packages.get(i);
                if (!p.isObject()) throw fail("packages[" + i + "] must be an object");
                String where = "packages[" + i + "]", purl = null;
                JsonNode refs = p.get("externalRefs");
                if (refs != null && !refs.isArray()) throw fail(where + ".externalRefs must be an array");
                for (int j = 0; refs != null && j < refs.size(); j++) {
                    JsonNode r = refs.get(j);
                    if (!r.isObject()) throw fail(where + ".externalRefs[" + j + "] must be an object");
                    if (!"purl".equals(r.path("referenceType").asText())) continue;
                    String locator = id(r, "referenceLocator", where);
                    if (purl != null && locator != null && !purl.equals(locator)) throw fail(where + " has more than one purl (" + purl + ", " + locator + ")");
                    if (locator != null) purl = locator;
                }
                add(where, str(p, "name"), str(p, "versionInfo"), purl, null, id(p, "SPDXID", where));
            }
            Set<String> ids = new java.util.HashSet<>();
            for (JsonNode d : root.path("documentDescribes")) if (d.isTextual()) ids.add(d.asText());
            String doc = root.path("SPDXID").asText("SPDXRef-DOCUMENT");
            for (JsonNode r : root.path("relationships")) {
                if (doc.equals(r.path("spdxElementId").asText()) && "DESCRIBES".equals(r.path("relationshipType").asText())) ids.add(r.path("relatedSpdxElement").asText());
            }
            described(ids);
            return;
        }
        throw fail("The JSON file is not a supported SBOM: expected CycloneDX (bomFormat) 1.4-1.6 or SPDX (spdxVersion) 2.2/2.3");
    }

    private void cycloneDxJson(JsonNode components, String at, int depth) {
        if (depth > MAX_DEPTH) throw fail("Components are nested more than " + MAX_DEPTH + " levels deep");
        for (int i = 0; i < components.size(); i++) {
            JsonNode c = components.get(i);
            String where = at + "[" + i + "]";
            if (!c.isObject()) throw fail(where + " must be an object");
            String purl = id(c, "purl", where), group = id(c, "group", where), ref = id(c, "bom-ref", where);
            if (!NOT_PACKAGES.contains(c.path("type").asText(""))) add(where, str(c, "name"), str(c, "version"), purl, group, ref);
            JsonNode nested = c.get("components");
            // present means a list: null, text or an object would otherwise silently drop the packages under it
            if (nested != null && !nested.isArray()) throw fail(where + ".components must be an array");
            if (nested != null && nested.isArray()) cycloneDxJson(nested, where + ".components", depth + 1);
        }
    }

    // ---- XML serializations (CycloneDX XML, SWID) ---------------------------------------------

    private static final String SWID_NS = "http://standards.iso.org/iso/19770/-2/2015/schema.xsd";
    private static final String CYCLONEDX_NS = "http://cyclonedx.org/schema/bom/";

    /** Parses without DTDs, entities, XInclude or any external access: nothing in the file is ever resolved or fetched. */
    private static Document parseXml(String text) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new DefaultHandler()); // fatal errors throw; nothing is printed
            return b.parse(new InputSource(new StringReader(text)));
        } catch (SAXException e) {
            throw fail(String.valueOf(e.getMessage()).contains("DOCTYPE")
                    ? "XML with a DOCTYPE (DTD or entity declarations) is refused for safety; export the SBOM without one"
                    : "The SBOM is not well-formed XML");
        } catch (ParserConfigurationException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One bounded walk over the whole document before anything is read: element depth and XInclude elements. */
    private static void checkTree(Element e, int depth) {
        if (depth > MAX_DEPTH) throw fail("XML elements are nested more than " + MAX_DEPTH + " levels deep");
        if (XINCLUDE_NS.equals(e.getNamespaceURI())) throw fail("XInclude elements are refused; nothing referenced in an SBOM is ever loaded");
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) checkTree(c, depth + 1);
        }
    }

    private void xml(String text) {
        Element root = parseXml(text).getDocumentElement();
        checkTree(root, 1);
        String ns = String.valueOf(root.getNamespaceURI());
        if ("bom".equals(root.getLocalName()) && ns.startsWith(CYCLONEDX_NS) && CYCLONEDX.contains(ns.substring(CYCLONEDX_NS.length()))) {
            format = "CycloneDX " + ns.substring(CYCLONEDX_NS.length()) + " XML";
            Element components = child(root, "components");
            if (components == null) {
                throw fail("The CycloneDX BOM has no <components> element, so it cannot say which packages the branch has; use an empty <components/> for a deliberately empty SBOM");
            }
            cycloneDxXml(components, "components", 1);
            Element metadata = child(root, "metadata"), app = metadata == null ? null : child(metadata, "component");
            if (app != null) appVersion = text(child(app, "version"));
            return;
        }
        if ("SoftwareIdentity".equals(root.getLocalName()) && SWID_NS.equals(ns)) {
            format = "ISO/IEC 19770-2:2015 SWID";
            if (root.getElementsByTagNameNS(SWID_NS, "SoftwareIdentity").getLength() > 0) {
                throw fail("A SWID upload must hold exactly one SoftwareIdentity (the software it describes); embedded or multiple SoftwareIdentity elements are not supported");
            }
            // only the described software is a package: Payload/Evidence files are not, and Link hrefs are never followed
            add("SoftwareIdentity", attr(root, "name"), attr(root, "version"), null, null, attr(root, "tagId"));
            appVersion = attr(root, "version");
            return;
        }
        throw fail("The XML file is not a supported SBOM: expected a CycloneDX 1.4-1.6 <bom> or one ISO/IEC 19770-2:2015 SWID <SoftwareIdentity>");
    }

    private void cycloneDxXml(Element components, String at, int depth) {
        if (depth > MAX_DEPTH) throw fail("Components are nested more than " + MAX_DEPTH + " levels deep");
        // only CycloneDX <component> elements may sit in <components>: anything else is not skipped silently (no full schema validation)
        for (Node n = components.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element x && !("component".equals(x.getLocalName()) && components.getNamespaceURI().equals(x.getNamespaceURI()))) {
                throw fail(at + " has an unexpected element <" + x.getNodeName() + ">; only CycloneDX <component> elements are allowed there");
            }
        }
        int i = 0;
        for (Element c : children(components, "component")) {
            String where = at + "[" + i++ + "]";
            if (!NOT_PACKAGES.contains(c.getAttribute("type"))) {
                add(where, text(child(c, "name")), text(child(c, "version")), text(child(c, "purl")), text(child(c, "group")), attr(c, "bom-ref"));
            }
            Element nested = child(c, "components");
            if (nested != null) cycloneDxXml(nested, where + ".components", depth + 1);
        }
    }

    private static List<Element> children(Element e, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            // same namespace as the parent only: foreign-namespace look-alikes are never read as CycloneDX fields
            if (n instanceof Element c && localName.equals(c.getLocalName()) && java.util.Objects.equals(e.getNamespaceURI(), c.getNamespaceURI())) out.add(c);
        }
        return out;
    }

    private static Element child(Element e, String localName) {
        List<Element> c = children(e, localName);
        return c.isEmpty() ? null : c.get(0);
    }

    private static String text(Element e) {
        return e == null || e.getTextContent().isBlank() ? null : e.getTextContent().trim();
    }

    private static String attr(Element e, String name) {
        return e.getAttribute(name).isBlank() ? null : e.getAttribute(name).trim();
    }

    // ---- SPDX tag-value ----------------------------------------------------------------------

    /**
     * SPDX 2.2/2.3 tag-value: a package section starts at PackageName and ends at the next PackageName, FileName,
     * SnippetSPDXID or LicenseID; multi-line &lt;text&gt; values and # comments never start or fill a package.
     */
    private void tagValue(String text) {
        String version = null, doc = null;
        Set<String> describedIds = new java.util.HashSet<>();
        String[] lines = text.split("\r?\n", -1);
        String[] pkg = null; // name, version, purl, SPDXID
        int packages = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            int colon = t.indexOf(':');
            if (t.isEmpty() || t.startsWith("#") || colon < 0) continue;
            String tag = t.substring(0, colon).trim(), value = t.substring(colon + 1).trim();
            if (value.startsWith("<text>")) {
                StringBuilder sb = new StringBuilder(value.substring(6));
                while (sb.indexOf("</text>") < 0) {
                    if (++i >= lines.length) throw fail("The SPDX tag-value document has a <text> value that is never closed with </text>");
                    sb.append('\n').append(lines[i]);
                }
                value = sb.substring(0, sb.indexOf("</text>")).trim();
            }
            switch (tag) {
                case "SPDXVersion" -> version = value;
                case "PackageName" -> {
                    if (pkg != null) add("package " + packages, pkg[0], pkg[1], pkg[2], null, pkg[3]);
                    pkg = new String[]{value.isEmpty() ? null : value, null, null, null};
                    packages++;
                }
                case "FileName", "SnippetSPDXID", "LicenseID" -> {
                    if (pkg != null) add("package " + packages, pkg[0], pkg[1], pkg[2], null, pkg[3]);
                    pkg = null;
                }
                case "PackageVersion" -> { if (pkg != null && !value.isEmpty()) pkg[1] = value; }
                case "SPDXID" -> {
                    if (pkg != null && pkg[3] == null) pkg[3] = value;
                    else if (pkg == null && doc == null && packages == 0) doc = value;
                }
                case "Relationship" -> {
                    String[] rel = value.split("\\s+");
                    if (rel.length == 3 && rel[1].equals("DESCRIBES") && rel[0].equals(doc == null ? "SPDXRef-DOCUMENT" : doc)) describedIds.add(rel[2]);
                }
                case "ExternalRef" -> {
                    String[] ref = value.split("\\s+", 3);
                    if (pkg == null || ref.length != 3 || !ref[1].equals("purl")) break;
                    if (pkg[2] != null && !pkg[2].equals(ref[2])) throw fail("package " + packages + " " + pkg[0] + " has more than one purl (" + pkg[2] + ", " + ref[2] + ")");
                    pkg[2] = ref[2];
                }
                default -> { }
            }
        }
        if (pkg != null) add("package " + packages, pkg[0], pkg[1], pkg[2], null, pkg[3]);
        described(describedIds);
        if (version == null) throw fail("The SPDX tag-value document has no SPDXVersion");
        format = spdxVersion(version) + " tag-value";
        if (packages == 0) {
            throw fail("The SPDX tag-value document has no PackageName entries, so it cannot say which packages the branch has; for a deliberately empty SBOM use SPDX JSON with \"packages\": []");
        }
    }

    private static String spdxVersion(String v) {
        if (!v.equals("SPDX-2.2") && !v.equals("SPDX-2.3")) {
            throw fail("SPDX version " + v + " is not supported; use SPDX-2.2 or SPDX-2.3 JSON or tag-value (SPDX 3 JSON-LD, RDF, XML and YAML are not supported)");
        }
        return v;
    }

    /** An identity field: absent or null is optional, but present non-text is rejected rather than ignored. */
    private static String id(JsonNode n, String field, String where) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw fail(where + " \"" + field + "\" must be a string");
        return v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || !v.isTextual() || v.asText().isBlank() ? null : v.asText().trim();
    }

    // ---- shared normalization ---------------------------------------------------------------

    /** One package row; a missing name or version is collected as an error so the whole upload is rejected before any write. */
    private void add(String where, String name, String version, String purl, String group, String ref) {
        String label = where + (ref != null ? " (" + ref + ")" : "") + (name != null ? " " + name : "");
        if (name == null) {
            errors.add(label + " has no package name");
            return;
        }
        if (version == null) {
            errors.add(label + " has no version; every package needs a version so its release cycle can be found");
            return;
        }
        if (deps.size() >= MAX_PACKAGES) throw fail("The SBOM lists more than " + MAX_PACKAGES + " packages");
        // an id seen twice is ambiguous: kept as null so it can never pick an arbitrary (last) version
        if (ref != null) versionById.put(ref, versionById.containsKey(ref) ? null : version);
        ObjectNode d = deps.addObject();
        d.put("name", name);
        d.put("version", version);
        if (purl != null) {
            String norm = Matcher.normalizePurl(purl);
            if (norm == null) {
                errors.add(label + " has a malformed purl " + purl);
                return;
            }
            String fromPurl = Matcher.purlVersion(purl);
            if (fromPurl == null) purl = withVersion(purl, version);
            else if (!fromPurl.equals(version)) {
                errors.add(label + " version " + version + " conflicts with its purl version " + fromPurl);
                return;
            }
            d.put("purl", purl);
            String eco = ECOSYSTEM.get(norm.substring(4, norm.indexOf('/')));
            if (eco != null) d.put("ecosystem", eco);
        }
        if (group != null) d.put("groupId", group);
        if (ref != null) d.put("bomRef", ref);
    }

    /** The purl with "@version" (percent-encoded) inserted before any ?qualifiers or #subpath. */
    static String withVersion(String purl, String version) {
        String p = purl.trim();
        int cut = p.length();
        for (char c : new char[]{'?', '#'}) {
            int i = p.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        return p.substring(0, cut) + "@" + URLEncoder.encode(version, StandardCharsets.UTF_8).replace("+", "%20") + p.substring(cut);
    }
}
