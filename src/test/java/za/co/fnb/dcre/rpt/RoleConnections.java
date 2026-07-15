package za.co.fnb.dcre.rpt;

import org.testcontainers.containers.CockroachContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens a JDBC connection as a named database role against the insecure dev/test
 * CockroachDB container (passwordless role login).
 *
 * <p>The Testcontainers CockroachDB JDBC URL carries the database as the path segment and,
 * by default, no query parameters at all (verified against testcontainers-cockroachdb 1.21.4:
 * {@code jdbc:postgresql://host:port/postgres}). Without an explicit {@code user=} parameter
 * the pgjdbc driver falls back to the OS username, so the role is forced onto the URL here:
 * an existing {@code user=} parameter is rewritten, otherwise one is appended.
 */
final class RoleConnections {

    private RoleConnections() {
    }

    static Connection forRole(final CockroachContainer crdb, final String dbName, final String role)
            throws SQLException {
        final String jdbcUrl = crdb.getJdbcUrl();
        final int queryStart = jdbcUrl.indexOf('?');
        final String base = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
        final String params = queryStart < 0 ? "" : jdbcUrl.substring(queryStart + 1);
        final String hostPort = base.substring(0, base.lastIndexOf('/'));
        final String withRole = params.contains("user=")
                ? params.replaceAll("user=[^&]+", "user=" + role)
                : params.isEmpty() ? "user=" + role : "%s&user=%s".formatted(params, role);
        return DriverManager.getConnection("%s/%s?%s".formatted(hostPort, dbName, withRole));
    }
}
