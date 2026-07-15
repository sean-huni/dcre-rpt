package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptSecurityIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest.CRDB::getJdbcUrl);
    }

    static Connection forRole(final String role) throws SQLException {
        // insecure dev container: passwordless role login
        Connection c = RoleConnections.forRole(RptJobTest.CRDB, RptJobTest.CRDB.getDatabaseName(), role);
        try (Statement s = c.createStatement()) {
            var rs = s.executeQuery("SHOW default_transaction_use_follower_reads");
            assertTrue(rs.next());
            assertEquals("on", rs.getString(1), "follower-read role default applied by changeset");
            // Fresh test container: the follower-read timestamp (~4.8s ago) predates the
            // container's own bootstrap and DDL, so historical reads see neither the database
            // nor the tables. Pin this session to present time for the grants-wall assertions.
            s.execute("SET default_transaction_use_follower_reads = off");
        }
        return c;
    }

    @Test
    void clientRoleCannotReadOltpTables() throws Exception {
        try (Connection root = DriverManager.getConnection(
                RptJobTest.CRDB.getJdbcUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS public.tx_header (id UUID PRIMARY KEY DEFAULT gen_random_uuid())");
        }
        try (Connection c = forRole("fnbcc01"); Statement s = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.executeQuery("SELECT count(*) FROM public.tx_header"));
            assertEquals("42501", ex.getSQLState(), "grants wall: no SELECT on public schema");
        }
    }

    @Test
    void clientRoleCanUseRptSchema() throws Exception {
        try (Connection c = forRole("fnbcc01"); Statement s = c.createStatement()) {
            // schema exists and is usable; views arrive in Task 4
            s.executeQuery("SELECT 1").next();
            var rs = s.executeQuery("SELECT count(*) FROM [SHOW SCHEMAS] WHERE schema_name = 'rpt'");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "rpt schema visible to client role");
        }
    }
}
