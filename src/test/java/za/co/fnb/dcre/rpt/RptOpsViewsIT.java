package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agt_ops ops views (Task 6): stage health + SLA, internal-only. The agt_ops database and its
 * operational tables are bootstrapped in {@link RptJobTest}'s static block (referenced from the
 * property source below, so class initialization ordering guarantees they pre-exist) because
 * EVERY context runs the secondary opsLiquibase whose CREATE VIEW validates them.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptOpsViewsIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    /** 1 arrival FNBCC01, 2 intents (CRR, CTV), 3 outcomes (2 BUSINESS_ACCEPTED, 1 TECH_FAILED). */
    @BeforeEach
    void seedOpsFixture() throws SQLException {
        try (Connection root = DriverManager.getConnection(
                RptJobTest.opsDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            // duplicate_delivery FKs file_arrival(id): delete child-first (SCRUM-58 file trace).
            s.execute("DELETE FROM public.duplicate_delivery; DELETE FROM public.stage_outcome;"
                    + "DELETE FROM public.launch_intent; DELETE FROM public.file_arrival");
            s.execute("""
                INSERT INTO public.file_arrival (id, client_token, arrived_at) VALUES
                ('00000000-0000-0000-0000-0000000000a1','FNBCC01','2026-07-01T08:00:00Z')""");
            s.execute("""
                INSERT INTO public.launch_intent (id, arrival_id, stage) VALUES
                ('00000000-0000-0000-0000-0000000000c1','00000000-0000-0000-0000-0000000000a1','CRR'),
                ('00000000-0000-0000-0000-0000000000c2','00000000-0000-0000-0000-0000000000a1','CTV')""");
            s.execute("""
                INSERT INTO public.stage_outcome (intent_id, outcome, observed_at) VALUES
                ('00000000-0000-0000-0000-0000000000c1','BUSINESS_ACCEPTED','2026-07-01T08:05:00Z'),
                ('00000000-0000-0000-0000-0000000000c2','BUSINESS_ACCEPTED','2026-07-01T08:06:00Z'),
                ('00000000-0000-0000-0000-0000000000c2','TECH_FAILED','2026-07-01T08:07:00Z')""");
        }
    }

    @Test
    void stageHealthGoldenForInternalRole() throws Exception {
        try (Connection c = RptSecurityIT.forRole("agt_ops", "rpt_internal");
             Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT stage, process_date, business_count, tech_failed_count, total
                FROM rpt.v_ops_stage_health ORDER BY stage""");
            assertTrue(rs.next());
            assertEquals("CRR", rs.getString("stage"));
            assertEquals("2026-07-01", rs.getDate("process_date").toString());
            assertEquals(1, rs.getInt("business_count"));
            assertEquals(0, rs.getInt("tech_failed_count"));
            assertEquals(1, rs.getInt("total"));
            assertTrue(rs.next());
            assertEquals("CTV", rs.getString("stage"));
            assertEquals("2026-07-01", rs.getDate("process_date").toString());
            assertEquals(1, rs.getInt("business_count"));
            assertEquals(1, rs.getInt("tech_failed_count"));
            assertEquals(2, rs.getInt("total"));
            assertFalse(rs.next(), "exactly one health row per stage");
            ResultSet totals = s.executeQuery("""
                SELECT sum(business_count), sum(tech_failed_count), sum(total)
                FROM rpt.v_ops_stage_health""");
            assertTrue(totals.next());
            assertEquals(2, totals.getInt(1), "business total");
            assertEquals(1, totals.getInt(2), "tech-failed total");
            assertEquals(3, totals.getInt(3), "grand total");
        }
    }

    @Test
    void slaGoldenForInternalRole() throws Exception {
        try (Connection c = RptSecurityIT.forRole("agt_ops", "rpt_internal");
             Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT count(*) AS n, count(DISTINCT client) AS clients,
                       min(EXTRACT(EPOCH FROM arrival_to_outcome))::INT AS min_secs,
                       max(EXTRACT(EPOCH FROM arrival_to_outcome))::INT AS max_secs
                FROM rpt.v_ops_sla WHERE client = 'FNBCC01'""");
            assertTrue(rs.next());
            assertEquals(3, rs.getInt("n"), "one SLA row per outcome");
            assertEquals(1, rs.getInt("clients"));
            assertEquals(300, rs.getInt("min_secs"), "08:00Z arrival to 08:05Z first outcome");
            assertEquals(420, rs.getInt("max_secs"), "08:00Z arrival to 08:07Z last outcome");
        }
    }

    /** Secondary-history invariant, mirroring the primary 14-count in {@link RptJobTest}. */
    @Test
    void opsLiquibaseHistoryHoldsExactlyFourChangesets() throws Exception {
        try (Connection root = DriverManager.getConnection(
                RptJobTest.opsDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT count(*) FROM rpt_databasechangelog");
            assertTrue(rs.next());
            assertEquals(8, rs.getInt(1),
                    "rpt schema + two ops views + grants + file-trace (duplicate_delivery pre-create "
                            + "+ two ops file views + grant) applied via rpt-prefixed history in agt_ops");
        }
    }

    @Test
    void clientRoleDeniedOnOpsViews() throws Exception {
        // Ops views have NO client-role grants (wall 1); the current_user predicate is
        // defense-in-depth only. fnbcc01 must be denied outright on the agt_ops connection.
        try (Connection c = RptSecurityIT.forRole("agt_ops", "fnbcc01");
             Statement s = c.createStatement()) {
            for (String view : List.of("v_ops_stage_health", "v_ops_sla")) {
                SQLException ex = assertThrows(SQLException.class,
                        () -> s.executeQuery("SELECT count(*) FROM rpt." + view));
                assertEquals("42501", ex.getSQLState(),
                        "no client grant on agt_ops view rpt." + view);
            }
        }
    }
}
