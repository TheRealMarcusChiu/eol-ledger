package dev.eolledger;

import org.springframework.beans.factory.annotation.Autowired;

/** Seeds a small catalogue and three branches across two repositories through the real sync/import paths. */
abstract class WebTestBase extends IntegrationTest {

    @Autowired CatalogService catalog;
    @Autowired ImportService imports;

    static final String CATALOGUE = """
            {"result":[
             {"name":"react","label":"React","identifiers":[{"type":"purl","id":"pkg:npm/react"}],"links":{"html":"https://endoflife.date/react"},
              "releases":[{"name":"19","releaseDate":"2024-12-05","isEol":false,"eolFrom":null,"latest":{"name":"19.2.0","date":"2025-10-01"}},
                          {"name":"18","releaseDate":"2022-03-29","isEol":true,"eolFrom":"2025-01-01","latest":{"name":"18.3.1","date":"2024-04-26"}}]},
             {"name":"python","label":"Python","identifiers":[{"type":"purl","id":"pkg:generic/python"}],
              "releases":[{"name":"3.10","releaseDate":"2021-10-04","isEol":false,"eolFrom":"2026-11-20"},
                          {"name":"3.9","releaseDate":"2020-10-05","isEol":true,"eolFrom":"2025-10-31"},
                          {"name":"3.1","releaseDate":"2009-06-27","isEol":true,"eolFrom":"2012-04-09"},
                          {"name":"2.7","releaseDate":"2010-07-03","isEol":true,"eolFrom":null}]},
             {"name":"django","label":"Django","identifiers":[{"type":"purl","id":"pkg:pypi/django"}],
              "releases":[{"name":"4.2","releaseDate":"2023-04-03","isEol":false,"eolFrom":"2027-04-30","latest":{"name":"4.2.25","date":"2026-10-01"}}]}
            ]}""";

    /** One dependency-inventory JSON entry whose packages are the given purls (name = last path segment, version = after '@'). */
    static String jsonEntry(String name, String url, String provider, String branch, String... purls) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(name).append("\",\"url\":\"").append(url)
                .append("\",\"provider\":\"").append(provider).append("\",\"branch\":\"").append(branch).append("\",\"dependencies\":[");
        for (int i = 0; i < purls.length; i++) {
            String p = purls[i];
            String pkg = p.substring(p.lastIndexOf('/') + 1, p.indexOf('@'));
            String version = p.substring(p.indexOf('@') + 1);
            sb.append(i == 0 ? "" : ",").append("{\"name\":\"").append(pkg).append("\",\"version\":\"").append(version)
                    .append("\",\"purl\":\"").append(p).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /** A dependency-inventory JSON root array of entries. */
    static String jsonInventory(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    void seed() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(CATALOGUE));
        catalog.sync();
        imports.importJson(jsonInventory(
                jsonEntry("Alpha", "https://gitlab.com/acme/alpha", "gitlab", "main",
                        "pkg:npm/react@18.2.0", "pkg:generic/python@3.10.5", "pkg:pypi/django@4.2.1", "pkg:maven/com.example/core@1.0.0"),
                jsonEntry("Alpha", "https://gitlab.com/acme/alpha", "gitlab", "release/1.x", "pkg:npm/react@18.3.0", "pkg:generic/python@2.7.18"),
                jsonEntry("Beta", "https://dev.azure.com/acme/P/_git/beta", "azure-devops", "main", "pkg:pypi/django@4.2.3", "pkg:generic/python@3.1.4")),
                "test");
    }

    long branchId(String repoUrl, String branch) {
        return db.sql("SELECT id FROM repo_branch WHERE repo_url = ? AND branch = ?").params(repoUrl, branch).query(Long.class).single();
    }
}
