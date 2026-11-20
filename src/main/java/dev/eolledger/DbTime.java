package dev.eolledger;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Instants in and out of the database without relying on server, session or JVM time zones:
 * H2 columns are TIMESTAMP WITH TIME ZONE (bound/read as OffsetDateTime, so rows written by earlier builds keep
 * their instant); MySQL columns are DATETIME(6) holding UTC (bound/read as zone-less LocalDateTime).
 * Truncated to microseconds, the precision of both columns, so a database never rounds a value into the next day.
 */
@Component
public class DbTime {

    private final boolean utcLocal;

    public DbTime(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            utcLocal = "MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName());
        }
    }

    public Object param(Instant i) {
        Instant t = i.truncatedTo(ChronoUnit.MICROS);
        return utcLocal ? LocalDateTime.ofInstant(t, ZoneOffset.UTC) : t.atOffset(ZoneOffset.UTC);
    }

    public Instant read(ResultSet rs, String column) throws SQLException {
        if (utcLocal) {
            LocalDateTime t = rs.getObject(column, LocalDateTime.class);
            return t == null ? null : t.toInstant(ZoneOffset.UTC);
        }
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
