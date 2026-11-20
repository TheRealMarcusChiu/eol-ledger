package dev.eolledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSyncTest extends IntegrationTest {

    @Autowired CatalogService catalog;

    static final String PYTHON = """
            {"name":"python","label":"Python","identifiers":[{"type":"purl","id":"pkg:generic/python"},{"type":"cpe","id":"cpe:/a:python"}],
             "links":{"html":"https://endoflife.date/python"},
             "releases":[
               {"name":"3.15","releaseDate":"2026-10-09","isEol":false,"eolFrom":"2031-10-31","latest":{"name":"3.15.2","date":"2026-10-25"}},
               {"name":"2.7","releaseDate":"2010-07-03","isEol":true,"eolFrom":null,"latest":null},
               {"name":"3.16","releaseDate":null,"isEol":false,"eolFrom":null}
             ]}""";
    static final String REACT = """
            {"name":"react","label":"React","identifiers":[{"type":"purl","id":"pkg:npm/react"}],
             "releases":[{"name":"18","releaseDate":"2022-03-29","isEol":false,"eolFrom":null}]}""";

    static String full(String... products) {
        return "{\"schema_version\":\"1.2.1\",\"total\":" + products.length + ",\"result\":[" + String.join(",", products) + "]}";
    }

    @Test
    void fullCatalogueSyncStoresProductsCyclesAndIdentifiersPreservingUnknownEol() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(full(PYTHON, REACT)));

        CatalogService.SyncResult r = catalog.sync();

        assertThat(r.ok()).isTrue();
        assertThat(r.productsSynced()).isEqualTo(2);
        Map<String, Object> py315 = db.sql("SELECT * FROM release_cycle WHERE product='python' AND name='3.15'").query().singleRow();
        assertThat(py315.get("EOL_FROM").toString()).isEqualTo("2031-10-31");
        assertThat(py315.get("LATEST")).isEqualTo("3.15.2");
        Map<String, Object> py27 = db.sql("SELECT * FROM release_cycle WHERE product='python' AND name='2.7'").query().singleRow();
        assertThat(py27.get("IS_EOL")).isEqualTo(true);
        assertThat(py27.get("EOL_FROM")).isNull();
        assertThat(db.sql("SELECT purl FROM product_identifier WHERE product='python'").query(String.class).list()).containsExactly("pkg:generic/python");
        assertThat(catalog.status().ok()).isTrue();
    }

    @Test
    void failedFetchKeepsPreviousDataAndRecordsRedactedFailure() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(full(PYTHON)));
        catalog.sync();
        TestSupport.REPLIES.put("/api/v1/products/full", new TestSupport.Reply(500, "boom", Map.of()));

        CatalogService.SyncResult r = catalog.sync();

        assertThat(r.ok()).isFalse();
        assertThat(db.sql("SELECT count(*) FROM release_cycle WHERE product='python'").query(Integer.class).single()).isEqualTo(3);
        CatalogService.Status s = catalog.status();
        assertThat(s.ok()).isFalse();
        assertThat(s.message()).contains("HTTP 500");
        assertThat(s.succeededAt()).isNotNull();
    }

    @Test
    void malformedProductIsSkippedWhileOthersAreReplacedAtomically() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(full(PYTHON, REACT)));
        catalog.sync();
        String brokenPython = "{\"name\":\"python\",\"label\":\"Python\",\"releases\":[{\"name\":\"3.15\",\"eolFrom\":\"not-a-date\"}]}";
        String react19 = REACT.replace("\"name\":\"18\"", "\"name\":\"19\"");
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(full(brokenPython, react19)));

        CatalogService.SyncResult r = catalog.sync();

        assertThat(r.ok()).isFalse();
        assertThat(r.productsSynced()).isEqualTo(1);
        assertThat(r.message()).contains("python");
        assertThat(db.sql("SELECT count(*) FROM release_cycle WHERE product='python'").query(Integer.class).single()).isEqualTo(3);
        assertThat(db.sql("SELECT name FROM release_cycle WHERE product='react'").query(String.class).list()).containsExactly("19");
    }

    @Test
    void configuredProductListFetchesEachProduct() {
        TestSupport.REPLIES.put("/api/v1/products/python", TestSupport.Reply.ok("{\"result\":" + PYTHON + "}"));

        CatalogService.SyncResult r = catalog.sync(List.of("python", "missing"));

        assertThat(r.productsSynced()).isEqualTo(1);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("missing");
        assertThat(db.sql("SELECT synced_at FROM product WHERE name='python'").query().singleValue()).isNotNull();
    }

    @Test
    void redirectsAreNotFollowed() {
        TestSupport.REPLIES.put("/api/v1/products/full", new TestSupport.Reply(302, "", Map.of("Location", "http://169.254.169.254/")));
        assertThat(catalog.sync().message()).contains("redirect");
    }

    @Test
    void releaseDateParsingHandlesNulls() {
        TestSupport.REPLIES.put("/api/v1/products/full", TestSupport.Reply.ok(full(PYTHON)));
        catalog.sync();
        assertThat(db.sql("SELECT count(*) FROM release_cycle WHERE name='3.16' AND release_date IS NULL").query(Integer.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT release_date FROM release_cycle WHERE name='3.15'").query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026, 10, 9));
    }
}
