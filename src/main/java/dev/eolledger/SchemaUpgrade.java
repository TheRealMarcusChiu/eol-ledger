package dev.eolledger;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brings a repo_branch table created by an earlier version up to the current schema (schema.sql only creates missing
 * tables): adds the nullable unique import_key and drops NOT NULL from repo_url, branch, repo_name and provider (and, on
 * MySQL, from the generated identity_key, so partial identities store NULL there while complete tuples stay unique).
 * Every step is guarded by DatabaseMetaData, so running it again (every start) changes nothing. Rows and IDs are kept.
 */
@Component
@DependsOnDatabaseInitialization
public class SchemaUpgrade {

    private static final List<String> NULLABLE = List.of("repo_url", "branch", "repo_name", "provider");

    public SchemaUpgrade(DataSource dataSource) throws SQLException {
        upgrade(dataSource);
    }

    static void upgrade(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            DatabaseMetaData md = c.getMetaData();
            boolean mysql = md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
            Map<String, Boolean> nullable = columns(c, md);
            if (nullable.isEmpty()) return; // no table: nothing to upgrade
            // separate guards, so a start interrupted between the two steps is completed on the next one
            if (!nullable.containsKey("import_key")) st.execute("ALTER TABLE repo_branch ADD COLUMN import_key VARCHAR(64)");
            if (!uniqueImportKey(c, md)) {
                st.execute(mysql ? "ALTER TABLE repo_branch ADD UNIQUE KEY uq_repo_branch_import_key (import_key)"
                        : "ALTER TABLE repo_branch ADD CONSTRAINT uq_repo_branch_import_key UNIQUE (import_key)");
            }
            Map<String, String> types = Map.of("repo_url", "VARCHAR(1000)", "branch", "VARCHAR(500)", "repo_name", "VARCHAR(300)", "provider", "VARCHAR(50)");
            for (String col : NULLABLE) {
                if (Boolean.TRUE.equals(nullable.get(col))) continue;
                st.execute(mysql ? "ALTER TABLE repo_branch MODIFY " + col + " " + types.get(col) + " NULL"
                        : "ALTER TABLE repo_branch ALTER COLUMN " + col + " SET NULL");
            }
            if (mysql && Boolean.FALSE.equals(nullable.get("identity_key"))) {
                st.execute("ALTER TABLE repo_branch MODIFY identity_key BINARY(64) AS (CONCAT(UNHEX(SHA2(repo_url, 256)), UNHEX(SHA2(branch, 256)))) STORED NULL");
            }
        }
    }

    /** True when some unique index (any name) covers exactly the single column import_key; non-unique or composite ones do not count. */
    private static boolean uniqueImportKey(Connection c, DatabaseMetaData md) throws SQLException {
        Map<String, List<String>> unique = new HashMap<>();
        try (ResultSet rs = md.getIndexInfo(c.getCatalog(), c.getSchema(), table(md), true, false)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME"), column = rs.getString("COLUMN_NAME");
                if (index == null || rs.getBoolean("NON_UNIQUE")) continue;
                unique.computeIfAbsent(index, k -> new java.util.ArrayList<>()).add(column == null ? "" : column.toLowerCase(Locale.ROOT));
            }
        }
        return unique.values().stream().anyMatch(cols -> cols.equals(List.of("import_key")));
    }

    private static String table(DatabaseMetaData md) throws SQLException {
        return md.storesUpperCaseIdentifiers() ? "REPO_BRANCH" : "repo_branch";
    }

    /** repo_branch column name (lower case) -> nullable, in the connection's own catalog/schema. */
    private static Map<String, Boolean> columns(Connection c, DatabaseMetaData md) throws SQLException {
        String table = table(md);
        Map<String, Boolean> out = new HashMap<>();
        try (ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), table, null)) {
            while (rs.next()) out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
        }
        return out;
    }
}
