package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptIsolationIT extends OltpPreseededTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyRptViewIsClientScopedForEveryClientRole() throws Exception {
        FixtureSeeder.seed(jdbc);
        List<String> views = jdbc.queryForList(
                "SELECT table_name FROM information_schema.views WHERE table_schema = 'rpt'", String.class);
        assertFalse(views.isEmpty());
        for (String role : List.of("fnbcc01", "fnbcc02", "fnbrf01")) {
            String own = role.toUpperCase();
            try (Connection c = RptSecurityIT.forRole(role); Statement s = c.createStatement()) {
                for (String v : views) {
                    // Client-facing views carry a client column and scope to the caller's own client;
                    // the SCRUM-58 internal-only trace views (e.g. v_flow_trace, arrival/job-keyed with
                    // no client column) return zero rows to any client role via the current_user
                    // predicate. Both satisfy the no-foreign-client-leakage invariant.
                    String sql = hasClientColumn(v)
                            ? "SELECT count(*) FROM rpt." + v + " WHERE client IS DISTINCT FROM '" + own + "'"
                            : "SELECT count(*) FROM rpt." + v;
                    ResultSet rs = s.executeQuery(sql);
                    rs.next();
                    assertEquals(0, rs.getInt(1),
                            "view rpt." + v + " leaked foreign-client rows to " + role);
                }
            }
        }
    }

    private boolean hasClientColumn(final String view) {
        return !jdbc.queryForList(
                "SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = 'rpt' AND table_name = ? AND column_name = 'client'",
                Integer.class, view).isEmpty();
    }

    @Test
    void internalRoleSeesAllThreeClients() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("rpt_internal"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT count(DISTINCT client) FROM rpt.v_tx");
            rs.next();
            assertEquals(3, rs.getInt(1));
        }
    }
}
