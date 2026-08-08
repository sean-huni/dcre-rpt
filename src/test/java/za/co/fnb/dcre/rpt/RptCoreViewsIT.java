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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptCoreViewsIT extends OltpPreseededTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest::businessDbUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void vTxDailyGoldenNumbersForClientRole() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT tx_count, total_amount, settled_count, settled_amount,
                       rejected_late_count, rejected_late_amount,
                       rejected_early_count, rejected_early_amount, p50_amount, p95_amount
                FROM rpt.v_tx_daily WHERE process_date = '2026-07-01'""");
            assertTrue(rs.next());
            assertEquals(4, rs.getInt("tx_count"));
            assertEquals(new BigDecimal("650.00"), rs.getBigDecimal("total_amount").setScale(2));
            assertEquals(2, rs.getInt("settled_count"));
            assertEquals(new BigDecimal("300.00"), rs.getBigDecimal("settled_amount").setScale(2));
            assertEquals(1, rs.getInt("rejected_late_count"));
            assertEquals(0, new BigDecimal("300.00").compareTo(rs.getBigDecimal("rejected_late_amount")));
            assertEquals(1, rs.getInt("rejected_early_count"));
            assertEquals(0, new BigDecimal("50.00").compareTo(rs.getBigDecimal("rejected_early_amount")));
            assertEquals(0, new BigDecimal("150.00").compareTo(rs.getBigDecimal("p50_amount")));
            assertEquals(0, new BigDecimal("285.00").compareTo(rs.getBigDecimal("p95_amount")));
            assertFalse(rs.next(), "exactly one row for own client + date");
        }
    }

    @Test
    void vFailsSplitsEarlyCtvFromLatePbsr() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                "SELECT stage, reason, count(*) c FROM rpt.v_fails GROUP BY 1,2 ORDER BY 1,2");
            assertTrue(rs.next());
            assertEquals("CTV", rs.getString("stage"));
            assertEquals("FAIL_ACCOUNT_NOT_FOUND", rs.getString("reason"));
            assertEquals(1, rs.getInt("c"));
            assertTrue(rs.next());
            assertEquals("PBSR", rs.getString("stage"));
            assertEquals("AC04", rs.getString("reason"));
            assertTrue(rs.next()); // AM04
            assertEquals("AM04", rs.getString("reason"));
            assertFalse(rs.next());
        }
    }

    @Test
    void vReasonDailyClassifiesCancellationAsTerminalFailure() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT fail_count, fail_amount FROM rpt.v_reason_daily
                WHERE process_date = '2026-07-01' AND stage = 'PBSR' AND reason = 'AC04'""");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("fail_count"));
            assertEquals(0, new BigDecimal("300.00").compareTo(rs.getBigDecimal("fail_amount")));
            assertFalse(rs.next());
        }
    }

    @Test
    void externalFailPrefixDoesNotMasqueradeAsEarlyValidationFailure() throws Exception {
        FixtureSeeder.seed(jdbc);
        jdbc.update("UPDATE public.pbsr_resp SET status = 'FAILX' WHERE e2e = 'E2E-RF1-001'");

        try (Connection c = RptSecurityIT.forRole("fnbrf01"); Statement s = c.createStatement()) {
            ResultSet daily = s.executeQuery("""
                SELECT settled_count, rejected_late_count, rejected_early_count
                FROM rpt.v_tx_daily WHERE process_date = '2026-07-01'""");
            assertTrue(daily.next());
            assertEquals(0, daily.getInt("settled_count"));
            assertEquals(0, daily.getInt("rejected_late_count"));
            assertEquals(0, daily.getInt("rejected_early_count"));

            ResultSet debtor = s.executeQuery("SELECT failed_count FROM rpt.v_debtor_daily");
            assertTrue(debtor.next());
            assertEquals(0, debtor.getInt("failed_count"));

            ResultSet bucket = s.executeQuery("SELECT failed_count FROM rpt.v_amount_buckets");
            assertTrue(bucket.next());
            assertEquals(0, bucket.getInt("failed_count"));

            ResultSet failures = s.executeQuery(
                    "SELECT count(*) FROM rpt.v_fails WHERE e2e = 'E2E-RF1-001'");
            assertTrue(failures.next());
            assertEquals(0, failures.getInt(1));
        }
    }

    @Test
    void vFailsUsesTheDeepestAvailableTerminalResponseLeg() throws Exception {
        FixtureSeeder.seed(jdbc);
        jdbc.execute("""
            DELETE FROM public.pbsr_resp WHERE e2e IN ('E2E-CC1-002', 'E2E-CC1-003');
            INSERT INTO public.isr_resp (response_file, e2e, status, reason, emission_id, created_at) VALUES
              ('ISR_D1_2','E2E-CC1-002','RJCT','IS01','00000000-0000-0000-0000-0000000000e1','2026-07-01T13:00:00Z'),
              ('ISR_D1_3','E2E-CC1-003','RJCT','IS02','00000000-0000-0000-0000-0000000000e1','2026-07-01T13:00:00Z'),
              ('ISR_D1_4','E2E-CC1-004','RJCT','IS03','00000000-0000-0000-0000-0000000000e1','2026-07-01T13:00:00Z');
            INSERT INTO public.sbsr_resp (response_file, e2e, status, reason, emission_id, created_at) VALUES
              ('SBSR_D1_3','E2E-CC1-003','CANC','SB01','00000000-0000-0000-0000-0000000000e1','2026-07-01T14:00:00Z'),
              ('SBSR_D1_4','E2E-CC1-004','CANC','SB02','00000000-0000-0000-0000-0000000000e1','2026-07-01T14:00:00Z')""");

        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT e2e, stage, reason, detected_at FROM rpt.v_fails
                WHERE e2e IN ('E2E-CC1-002', 'E2E-CC1-003', 'E2E-CC1-004')
                ORDER BY e2e""");
            assertFailure(rs, "E2E-CC1-002", "ISR", "IS01", "2026-07-01T13:00:00Z");
            assertFailure(rs, "E2E-CC1-003", "SBSR", "SB01", "2026-07-01T14:00:00Z");
            assertFailure(rs, "E2E-CC1-004", "PBSR", "AC04", "2026-07-01T15:00:00Z");
            assertFalse(rs.next());

            ResultSet reasons = s.executeQuery("""
                SELECT stage, reason FROM rpt.v_reason_daily
                WHERE reason IN ('IS01', 'SB01', 'AC04') ORDER BY stage""");
            assertTrue(reasons.next()); assertEquals("ISR", reasons.getString("stage"));
            assertTrue(reasons.next()); assertEquals("PBSR", reasons.getString("stage"));
            assertTrue(reasons.next()); assertEquals("SBSR", reasons.getString("stage"));
            assertFalse(reasons.next());
        }
    }

    @Test
    void vDebtorDailyTopFailedDebtor() throws Exception {
        FixtureSeeder.seed(jdbc);
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                SELECT debtor_account, sum(failed_amount) fa FROM rpt.v_debtor_daily
                GROUP BY 1 ORDER BY fa DESC NULLS LAST LIMIT 1""");
            assertTrue(rs.next());
            assertEquals("D-CC1-D", rs.getString(1));
            assertEquals(0, new BigDecimal("300.00").compareTo(rs.getBigDecimal(2)));
        }
    }

    private void assertFailure(final ResultSet rs, final String e2e, final String stage,
                               final String reason, final String detectedAt) throws Exception {
        assertTrue(rs.next());
        assertEquals(e2e, rs.getString("e2e"));
        assertEquals(stage, rs.getString("stage"));
        assertEquals(reason, rs.getString("reason"));
        assertEquals(Instant.parse(detectedAt), rs.getTimestamp("detected_at").toInstant());
    }
}
