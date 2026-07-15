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
class RptCoreViewsIT extends OltpPreseededTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest.CRDB::getJdbcUrl);
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
}
