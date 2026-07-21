package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.BeforeEach;
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

/** Guards duplicate-e2e correlation across arrivals, clients and legacy response identities. */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptBatchCorrelationIT extends OltpPreseededTestBase {

    private static final String A1 = "10000000-0000-0000-0000-0000000000a1";
    private static final String A2 = "10000000-0000-0000-0000-0000000000a2";
    private static final String A3 = "10000000-0000-0000-0000-0000000000a3";
    private static final String A4 = "10000000-0000-0000-0000-0000000000a4";
    private static final String A5 = "10000000-0000-0000-0000-0000000000a5";
    private static final String E1 = "10000000-0000-0000-0000-0000000000e1";
    private static final String E2 = "10000000-0000-0000-0000-0000000000e2";
    private static final String E3 = "10000000-0000-0000-0000-0000000000e3";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM public.pbsr_resp; DELETE FROM public.sbsr_resp; DELETE FROM public.isr_resp;"
                + "DELETE FROM public.crw_emission_member; DELETE FROM public.crw_emission;"
                + "DELETE FROM public.crw_emission_group; DELETE FROM public.validation_log;"
                + "DELETE FROM public.tx_entry; DELETE FROM public.tx_header");
        jdbc.execute("""
            INSERT INTO public.tx_header
              (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at) VALUES
              ('10000000-0000-0000-0000-0000000000b1','%s','MSG-A','FNBCC01','20260701',1,'2026-07-01T08:00:00Z'),
              ('10000000-0000-0000-0000-0000000000b2','%s','MSG-B','FNBCC01','20260701',1,'2026-07-01T08:01:00Z'),
              ('10000000-0000-0000-0000-0000000000b3','%s','MSG-C','FNBCC02','20260701',1,'2026-07-01T08:02:00Z'),
              ('10000000-0000-0000-0000-0000000000b4','%s','MSG-D','FNBCC01','20260701',1,'2026-07-01T08:03:00Z')
            """.formatted(A1, A2, A3, A4));
        jdbc.execute("""
            INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount, debtor_account) VALUES
              ('%s',1,'E2E-DUP',100.00,'DEBTOR-A'),
              ('%s',1,'E2E-DUP',200.00,'DEBTOR-B'),
              ('%s',1,'E2E-DUP',300.00,'DEBTOR-C'),
              ('%s',1,'E2E-DUP',400.00,'DEBTOR-D')
            """.formatted(A1, A2, A3, A4));
        jdbc.execute("""
            INSERT INTO public.validation_log (arrival_id, sequence, outcome) VALUES
              ('%s',1,'PASS'),('%s',1,'PASS'),('%s',1,'PASS'),('%s',1,'PASS')
            """.formatted(A1, A2, A3, A4));
        jdbc.execute("""
            INSERT INTO public.crw_emission
              (id, arrival_id, run_date, file_name, outbound_msg_id) VALUES
              ('%s','%s','2026-07-01','FILE-A','MSG-A_1'),
              ('%s','%s','2026-07-01','FILE-B','MSG-B_1'),
              ('%s','%s','2026-07-01','FILE-C','MSG-C_1')
            """.formatted(E1, A1, E2, A2, E3, A3));
        jdbc.execute("""
            INSERT INTO public.crw_emission_member (emission_id, sequence, e2e, amount) VALUES
              ('%s',1,'E2E-DUP',100.00),('%s',1,'E2E-DUP',200.00),('%s',1,'E2E-DUP',300.00)
            """.formatted(E1, E2, E3));
        jdbc.execute("""
            INSERT INTO public.pbsr_resp
              (response_file, orgnl_msg_id, e2e, status, reason, emission_id, created_at) VALUES
              ('PBSR-A','MSG-A_1','E2E-DUP','ACCC',NULL,NULL,'2026-07-01T10:00:00Z'),
              ('PBSR-B','MSG-B_1','E2E-DUP','CANC','B001','%s','2026-07-01T10:01:00Z'),
              ('PBSR-C','MSG-C_1','E2E-DUP','ACSP',NULL,'%s','2026-07-01T10:02:00Z')
            """.formatted(E2, E3));
        jdbc.execute("""
            INSERT INTO public.isr_resp
              (response_file, orgnl_msg_id, e2e, status, reason, emission_id, created_at)
            VALUES ('ISR-D','MSG-D_1','E2E-DUP','RJCT','D001',NULL,'2026-07-01T10:03:00Z')
            """);
    }

    @Test
    void duplicateE2eStatusesStayInsideTheirArrivalAndClientIdentity() throws Exception {
        try (Connection connection = RptSecurityIT.forRole("fnbcc01");
             Statement statement = connection.createStatement()) {
            ResultSet tx = statement.executeQuery("""
                SELECT arrival_id, status, emitted FROM rpt.v_tx ORDER BY arrival_id""");
            assertTx(tx, A1, "ACCC", true);
            assertTx(tx, A2, "CANC", true);
            // A4 was never emitted and its ISR row carries neither identity: fail-closed, the
            // response binds nowhere and A4 stays at its CTV evidence.
            assertTx(tx, A4, "CTV_PASS", false);
            assertFalse(tx.next());

            ResultSet fails = statement.executeQuery("""
                SELECT debtor_account, stage, reason FROM rpt.v_fails ORDER BY debtor_account""");
            assertFailure(fails, "DEBTOR-B", "PBSR", "B001");
            assertFalse(fails.next(), "the unattributable ISR RJCT must not surface as a failure");

            ResultSet recon = statement.executeQuery("""
                SELECT file_name, settled_sum, variance FROM rpt.v_recon_daily ORDER BY file_name""");
            assertRecon(recon, "FILE-A", "100.00", "0.00");
            assertRecon(recon, "FILE-B", "0.00", "200.00");
            assertFalse(recon.next());
        }

        try (Connection connection = RptSecurityIT.forRole("fnbcc02");
             Statement statement = connection.createStatement();
             ResultSet tx = statement.executeQuery("SELECT arrival_id, status FROM rpt.v_tx")) {
            assertTrue(tx.next());
            assertEquals(A3, tx.getString("arrival_id"));
            assertEquals("ACSP", tx.getString("status"));
            assertFalse(tx.next());
        }
    }

    @Test
    void responseWithNeitherIdentityBindsNowhere() throws Exception {
        // ISR-D: emission_id NULL and orgnl_msg_id 'MSG-D_1' matches no crw_emission.outbound_msg_id.
        try (Connection connection = RptSecurityIT.forRole("rpt_internal");
             Statement statement = connection.createStatement()) {
            ResultSet tx = statement.executeQuery(
                    "SELECT count(*) FROM rpt.v_tx WHERE status = 'RJCT'");
            assertTrue(tx.next());
            assertEquals(0, tx.getInt(1), "the unattributable RJCT binds to no transaction row");

            ResultSet fails = statement.executeQuery(
                    "SELECT count(*) FROM rpt.v_fails WHERE reason = 'D001'");
            assertTrue(fails.next());
            assertEquals(0, fails.getInt(1), "the unattributable RJCT surfaces in no failure row");
        }
    }

    @Test
    void sharedMsgIdAndReusedE2eAcrossClientsDoNotCrossBind() throws Exception {
        // A5 (FNBCC02) reuses A1's tx_header.msg_id AND the duplicate e2e, and has no emissions:
        // the retired MsgId-family fallback would have borrowed A1's 'MSG-A_1' responses for it.
        jdbc.execute("""
            INSERT INTO public.tx_header
              (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
            VALUES ('10000000-0000-0000-0000-0000000000b5','%s','MSG-A','FNBCC02','20260701',1,
                    '2026-07-01T08:04:00Z')""".formatted(A5));
        jdbc.execute("INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount, debtor_account)"
                + " VALUES ('%s',1,'E2E-DUP',500.00,'DEBTOR-E')".formatted(A5));
        jdbc.execute("INSERT INTO public.validation_log (arrival_id, sequence, outcome)"
                + " VALUES ('%s',1,'PASS')".formatted(A5));

        try (Connection connection = RptSecurityIT.forRole("fnbcc02");
             Statement statement = connection.createStatement()) {
            ResultSet tx = statement.executeQuery(
                    "SELECT arrival_id, status, emitted FROM rpt.v_tx ORDER BY arrival_id");
            assertTx(tx, A3, "ACSP", true);
            assertTx(tx, A5, "CTV_PASS", false);
            assertFalse(tx.next());

            ResultSet fails = statement.executeQuery("SELECT count(*) FROM rpt.v_fails");
            assertTrue(fails.next());
            assertEquals(0, fails.getInt(1), "no borrowed failure crosses the tenant boundary");
        }
    }

    private void assertTx(final ResultSet result, final String arrival, final String status,
                          final boolean emitted) throws Exception {
        assertTrue(result.next());
        assertEquals(arrival, result.getString("arrival_id"));
        assertEquals(status, result.getString("status"));
        assertEquals(emitted, result.getBoolean("emitted"));
    }

    private void assertFailure(final ResultSet result, final String debtorAccount, final String stage,
                               final String reason) throws Exception {
        assertTrue(result.next());
        assertEquals(debtorAccount, result.getString("debtor_account"));
        assertEquals(stage, result.getString("stage"));
        assertEquals(reason, result.getString("reason"));
    }

    private void assertRecon(final ResultSet result, final String file, final String settled,
                             final String variance) throws Exception {
        assertTrue(result.next());
        assertEquals(file, result.getString("file_name"));
        assertEquals(new BigDecimal(settled), result.getBigDecimal("settled_sum").setScale(2));
        assertEquals(new BigDecimal(variance), result.getBigDecimal("variance").setScale(2));
    }
}
