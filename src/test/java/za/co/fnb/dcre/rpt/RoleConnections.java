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
 *
 * <p><b>Follower-read caveat:</b> the client-facing roles carry
 * {@code default_transaction_use_follower_reads = 'on'} as a ROLE DEFAULT (changeset 001), so a
 * connection returned here serves reads ~4.8s STALE by design. Tests that seed fixtures and read
 * them back immediately MUST pin the session to present time first
 * ({@code SET default_transaction_use_follower_reads = off}), as {@code RptSecurityIT.forRole}
 * does after asserting the role default is {@code 'on'}. Skipping the pin makes fresh-fixture
 * reads flake; on a fresh container the historical timestamp can even predate the container's
 * own bootstrap DDL.
 */
final class RoleConnections {

    private RoleConnections() {
    }

    /**
     * Rewrites the container JDBC URL to target another database in the same cluster,
     * preserving any query parameters (single source of the URL-rewrite logic; also used
     * for the {@code dcre.rpt.ops-db-url} property pointing at {@code agt_ops}).
     */
    static String forDatabase(final CockroachContainer crdb, final String dbName) {
        final String jdbcUrl = crdb.getJdbcUrl();
        final int queryStart = jdbcUrl.indexOf('?');
        final String base = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
        final String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return "%s/%s%s".formatted(base.substring(0, base.lastIndexOf('/')), dbName, query);
    }

    static Connection forRole(final CockroachContainer crdb, final String dbName, final String role)
            throws SQLException {
        final String dbUrl = forDatabase(crdb, dbName);
        final int queryStart = dbUrl.indexOf('?');
        final String base = queryStart < 0 ? dbUrl : dbUrl.substring(0, queryStart);
        final String params = queryStart < 0 ? "" : dbUrl.substring(queryStart + 1);
        final String withRole = params.contains("user=")
                ? params.replaceAll("user=[^&]+", "user=" + role)
                : params.isEmpty() ? "user=" + role : "%s&user=%s".formatted(params, role);
        return DriverManager.getConnection("%s?%s".formatted(base, withRole));
    }
}
