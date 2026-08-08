package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the CURRENT-emission semantics of the v_tx spine: batch-scoped response binding for a
 * split arrival, the single-row pick across cross-run_date re-emissions, and the resubmission
 * window ordering (client, e2e ordered by created_at, arrival_id, sequence).
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptEmissionCurrencyIT extends OltpPreseededTestBase {

    private static final String AB = "20000000-0000-0000-0000-0000000000a1";
    private static final String AR = "20000000-0000-0000-0000-0000000000a2";
    private static final String AS1 = "20000000-0000-0000-0000-0000000000a3";
    private static final String AS2 = "20000000-0000-0000-0000-0000000000a4";
    private static final String AT = "20000000-0000-0000-0000-0000000000a5";
    private static final String EB1 = "20000000-0000-0000-0000-0000000000e1";
    private static final String EB2 = "20000000-0000-0000-0000-0000000000e2";
    private static final String ER1 = "20000000-0000-0000-0000-0000000000e3";
    private static final String ER2 = "20000000-0000-0000-0000-0000000000e4";
    private static final String ET1 = "20000000-0000-0000-0000-0000000000e5";
    private static final String ET2 = "20000000-0000-0000-0000-0000000000e6";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest::businessDbUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM public.pbsr_resp; DELETE FROM public.sbsr_resp; DELETE FROM public.isr_resp;"
                + "DELETE FROM public.crw_emission_member; DELETE FROM public.crw_emission;"
                + "DELETE FROM public.crw_emission_group; DELETE FROM public.validation_log;"
                + "DELETE FROM public.tx_entry; DELETE FROM public.tx_header");
    }

    @Test
    void responsesLandOnTheirOwnBatchOfASplitArrival() throws Exception {
        seedArrival(AB, "20000000-0000-0000-0000-0000000000b1", "MSG-MB", "2026-07-01T08:00:00Z");
        jdbc.execute("""
            INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount, debtor_account) VALUES
              ('%s',1,'E2E-SHARED',100.00,'DEBTOR-M1'),('%s',2,'E2E-SHARED',200.00,'DEBTOR-M2')
            """.formatted(AB, AB));
        jdbc.execute("INSERT INTO public.validation_log (arrival_id, sequence, outcome) VALUES"
                + " ('%s',1,'PASS'),('%s',2,'PASS')".formatted(AB, AB));
        jdbc.execute("""
            INSERT INTO public.crw_emission
              (id, arrival_id, run_date, batch_ordinal, file_name, outbound_msg_id) VALUES
              ('%s','%s','2026-07-01',1,'FILE-MB_1','MSG-MB_1'),
              ('%s','%s','2026-07-01',2,'FILE-MB_2','MSG-MB_2')
            """.formatted(EB1, AB, EB2, AB));
        jdbc.execute("""
            INSERT INTO public.crw_emission_member (emission_id, sequence, e2e, amount) VALUES
              ('%s',1,'E2E-SHARED',100.00),('%s',2,'E2E-SHARED',200.00)
            """.formatted(EB1, EB2));
        // Both batches reuse the SAME e2e: only the (emission_id, e2e) identity separates them.
        jdbc.execute("""
            INSERT INTO public.pbsr_resp
              (response_file, orgnl_msg_id, e2e, status, reason, emission_id, created_at) VALUES
              ('PBSR-MB1','MSG-MB_1','E2E-SHARED','ACSC',NULL,'%s','2026-07-01T15:00:00Z'),
              ('PBSR-MB2','MSG-MB_2','E2E-SHARED','RJCT','MB01','%s','2026-07-01T15:01:00Z')
            """.formatted(EB1, EB2));

        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet tx = s.executeQuery(
                    "SELECT sequence, status FROM rpt.v_tx ORDER BY sequence");
            assertTrue(tx.next());
            assertEquals(1, tx.getInt("sequence"));
            assertEquals("ACSC", tx.getString("status"), "batch _1 keeps its own ACSC");
            assertTrue(tx.next());
            assertEquals(2, tx.getInt("sequence"));
            assertEquals("RJCT", tx.getString("status"), "batch _2 keeps its own RJCT, no borrow");
            assertFalse(tx.next());

            ResultSet fails = s.executeQuery("SELECT debtor_account, stage, reason FROM rpt.v_fails");
            assertTrue(fails.next());
            assertEquals("DEBTOR-M2", fails.getString("debtor_account"));
            assertEquals("PBSR", fails.getString("stage"));
            assertEquals("MB01", fails.getString("reason"));
            assertFalse(fails.next());
        }
    }

    @Test
    void crossRunDateReEmissionCountsOnceAndFollowsTheCurrentEmission() throws Exception {
        seedArrival(AR, "20000000-0000-0000-0000-0000000000b2", "MSG-RR", "2026-07-01T08:00:00Z");
        jdbc.execute("INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount, debtor_account)"
                + " VALUES ('%s',1,'E2E-RERUN',250.00,'DEBTOR-R')".formatted(AR));
        jdbc.execute("INSERT INTO public.validation_log (arrival_id, sequence, outcome)"
                + " VALUES ('%s',1,'PASS')".formatted(AR));
        jdbc.execute("""
            INSERT INTO public.crw_emission
              (id, arrival_id, run_date, batch_ordinal, file_name, outbound_msg_id) VALUES
              ('%s','%s','2026-07-01',1,'FILE-RR_D1','MSG-RR_1'),
              ('%s','%s','2026-07-02',1,'FILE-RR_D2','MSG-RR_2')
            """.formatted(ER1, AR, ER2, AR));
        jdbc.execute("""
            INSERT INTO public.crw_emission_member (emission_id, sequence, e2e, amount) VALUES
              ('%s',1,'E2E-RERUN',250.00),('%s',1,'E2E-RERUN',250.00)
            """.formatted(ER1, ER2));
        jdbc.execute("""
            INSERT INTO public.pbsr_resp
              (response_file, orgnl_msg_id, e2e, status, reason, emission_id, created_at) VALUES
              ('PBSR-RR1','MSG-RR_1','E2E-RERUN','ACSC',NULL,'%s','2026-07-01T15:00:00Z'),
              ('PBSR-RR2','MSG-RR_2','E2E-RERUN','RJCT','RR01','%s','2026-07-02T15:00:00Z')
            """.formatted(ER1, ER2));

        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet tx = s.executeQuery("SELECT status FROM rpt.v_tx WHERE e2e = 'E2E-RERUN'");
            assertTrue(tx.next());
            assertEquals("RJCT", tx.getString("status"),
                    "the CURRENT (latest run_date) emission's response wins");
            assertFalse(tx.next(), "cross-run_date re-emission must not fan the entry out");

            ResultSet daily = s.executeQuery(
                    "SELECT tx_count FROM rpt.v_tx_daily WHERE process_date = '2026-07-01'");
            assertTrue(daily.next());
            assertEquals(1, daily.getInt("tx_count"), "no double count in the daily rollup");
            assertFalse(daily.next());
        }
    }

    @Test
    void sameRunDateOrdinalReEmissionFollowsTheHighestOrdinal() throws Exception {
        seedArrival(AT, "20000000-0000-0000-0000-0000000000b5", "MSG-TB", "2026-07-01T08:00:00Z");
        jdbc.execute("INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount, debtor_account)"
                + " VALUES ('%s',1,'E2E-TIE',75.00,'DEBTOR-T')".formatted(AT));
        jdbc.execute("INSERT INTO public.validation_log (arrival_id, sequence, outcome)"
                + " VALUES ('%s',1,'PASS')".formatted(AT));
        jdbc.execute("""
            INSERT INTO public.crw_emission
              (id, arrival_id, run_date, batch_ordinal, file_name, outbound_msg_id) VALUES
              ('%s','%s','2026-07-01',1,'FILE-TB_1','MSG-TB_1'),
              ('%s','%s','2026-07-01',2,'FILE-TB_2','MSG-TB_2')
            """.formatted(ET1, AT, ET2, AT));
        jdbc.execute("""
            INSERT INTO public.crw_emission_member (emission_id, sequence, e2e, amount) VALUES
              ('%s',1,'E2E-TIE',75.00),('%s',1,'E2E-TIE',75.00)
            """.formatted(ET1, ET2));
        // Ordinal 2's response is deliberately OLDER than ordinal 1's: only the
        // batch_ordinal DESC tie-break, never recency, may pick the winner.
        jdbc.execute("""
            INSERT INTO public.pbsr_resp
              (response_file, orgnl_msg_id, e2e, status, reason, emission_id, created_at) VALUES
              ('PBSR-TB1','MSG-TB_1','E2E-TIE','ACSC',NULL,'%s','2026-07-01T15:00:00Z'),
              ('PBSR-TB2','MSG-TB_2','E2E-TIE','RJCT','TB01','%s','2026-07-01T14:00:00Z')
            """.formatted(ET1, ET2));

        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet tx = s.executeQuery("SELECT status FROM rpt.v_tx WHERE e2e = 'E2E-TIE'");
            assertTrue(tx.next());
            assertEquals("RJCT", tx.getString("status"),
                    "the highest batch_ordinal of the run_date wins the tie-break");
            assertFalse(tx.next(), "same-run_date ordinal re-emission must not fan the entry out");

            ResultSet daily = s.executeQuery(
                    "SELECT tx_count FROM rpt.v_tx_daily WHERE process_date = '2026-07-01'");
            assertTrue(daily.next());
            assertEquals(1, daily.getInt("tx_count"), "no double count in the daily rollup");
            assertFalse(daily.next());
        }
    }

    @Test
    void resubmissionWindowOrdersByCreatedAtArrivalThenSequence() throws Exception {
        seedArrival(AS1, "20000000-0000-0000-0000-0000000000b3", "MSG-RS1", "2026-07-01T08:00:00Z");
        seedArrival(AS2, "20000000-0000-0000-0000-0000000000b4", "MSG-RS2", "2026-07-01T08:05:00Z");
        jdbc.execute("""
            INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount, debtor_account) VALUES
              ('%s',1,'E2E-RESUB',10.00,'DEBTOR-S'),
              ('%s',2,'E2E-RESUB',10.00,'DEBTOR-S'),
              ('%s',1,'E2E-RESUB',10.00,'DEBTOR-S')
            """.formatted(AS1, AS1, AS2));
        jdbc.execute("INSERT INTO public.validation_log (arrival_id, sequence, outcome) VALUES"
                + " ('%s',1,'PASS'),('%s',2,'PASS'),('%s',1,'PASS')".formatted(AS1, AS1, AS2));

        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet tx = s.executeQuery(
                    "SELECT arrival_id, sequence, is_resubmission FROM rpt.v_tx ORDER BY arrival_id, sequence");
            assertResubmission(tx, AS1, 1, false);
            assertResubmission(tx, AS1, 2, true);
            assertResubmission(tx, AS2, 1, true);
            assertFalse(tx.next());
        }
    }

    private void assertResubmission(final ResultSet result, final String arrival, final int sequence,
                                    final boolean resubmission) throws Exception {
        assertTrue(result.next());
        assertEquals(arrival, result.getString("arrival_id"));
        assertEquals(sequence, result.getInt("sequence"));
        assertEquals(resubmission, result.getBoolean("is_resubmission"),
                "is_resubmission for sequence " + sequence + " of " + arrival);
    }

    private void seedArrival(final String arrivalId, final String headerId, final String msgId,
                             final String createdAt) {
        jdbc.execute("""
            INSERT INTO public.tx_header
              (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
            VALUES ('%s','%s','%s','FNBCC01','20260701',1,'%s')
            """.formatted(headerId, arrivalId, msgId, createdAt));
    }
}
