package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptExtendedViewsIT extends OltpPreseededTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest::businessDbUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void funnelGolden() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT stage, tx_count FROM rpt.v_funnel_daily
                WHERE process_date = '2026-07-01' ORDER BY stage_order""");
            assertTrue(rs.next()); assertEquals("SUBMITTED", rs.getString(1)); assertEquals(4, rs.getInt(2));
            assertTrue(rs.next()); assertEquals("CTV_PASS", rs.getString(1)); assertEquals(3, rs.getInt(2));
            assertTrue(rs.next()); assertEquals("EMITTED", rs.getString(1)); assertEquals(3, rs.getInt(2));
            assertTrue(rs.next()); assertEquals("SETTLED", rs.getString(1)); assertEquals(2, rs.getInt(2));
            assertFalse(rs.next());
        }
    }

    @Test
    void latencyGolden() throws Exception {
        FixtureSeeder.seed(jdbc);
        // Only the deterministic columns are asserted: emitted_at (crw_emission.created_at)
        // defaults to now() in the fixture, so ingest_to_emit / emit_to_settle vary per run.
        // end_to_end comes from fixture timestamps: ingested 08:00Z -> settled 15:00Z = 7h.
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT settlement_day_offset, EXTRACT(EPOCH FROM end_to_end)::INT AS e2e_seconds
                FROM rpt.v_latency WHERE e2e = 'E2E-CC1-002'""");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("settlement_day_offset"), "settled on the business date itself");
            assertEquals(7 * 3600, rs.getInt("e2e_seconds"), "08:00Z ingest -> 15:00Z settle = 7h end_to_end");
            assertFalse(rs.next());
        }
    }

    @Test
    void reconGolden() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT control_sum, settled_sum, variance FROM rpt.v_recon_daily
                WHERE process_date = '2026-07-01' AND file_name = 'CC01_D1_PAIN008'""");
            assertTrue(rs.next());
            assertEquals(0, new BigDecimal("600.00").compareTo(rs.getBigDecimal(1)));
            assertEquals(0, new BigDecimal("300.00").compareTo(rs.getBigDecimal(2)));
            assertEquals(0, new BigDecimal("300.00").compareTo(rs.getBigDecimal(3)));
        }
    }

    @Test
    void cureGolden() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT cured_date, days_to_cure FROM rpt.v_cure
                WHERE debtor_account = 'D-CC1-D' AND fail_date = '2026-07-01'""");
            assertTrue(rs.next());
            assertEquals(java.sql.Date.valueOf("2026-07-02"), rs.getDate(1));
            assertEquals(1, rs.getInt(2));
        }
    }

    @Test
    void bucketsGolden() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc02"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT bucket, tx_count, failed_count FROM rpt.v_amount_buckets
                WHERE process_date = '2026-07-01' ORDER BY bucket""");
            assertTrue(rs.next()); assertEquals("D_1K_5K", rs.getString(1)); assertEquals(2, rs.getInt(2));
            assertEquals(1, rs.getInt("failed_count"), "CANC is a terminal non-success");
            assertFalse(rs.next(), "1000 and 2000 both land in D_1K_5K (bucket edges: >=1000 <5000)");
        }
    }
}
