package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-58 Task 17 prod-support views (spec 11.3 Tier-2), extending the Task-12 rpt view stack.
 * Proves, per plan Task 17:
 * (a) {@code v_ops_attempts} distinguishes CONSISTENT vs CONFLICTING vs STALE_ATTEMPT vs UNOBSERVED
 *     from {@code stage_outcome} fixtures (evidence-only, no execution-duration claim);
 * (b) each {@code v_ops_stuck}/{@code v_stuck} wedge class surfaces from a wedged fixture while a
 *     healthy arrival does NOT;
 * (c) {@code v_correlation_index} resolves a MsgId AND an end-to-end id to the right arrival;
 * (d) {@code v_arrival_status}/{@code v_client_day} return only the session-identity client's rows
 *     AND expose zero filenames / job names;
 * (e) internal views deny (ops: 42501) / zero-row (business: current_user predicate) a client role.
 *
 * <p>Owner tables are pre-created by {@link RptJobTest}'s static block; this IT seeds owner ROWS as
 * root and reads the views back pinned to present-time via {@link RptSecurityIT#forRole}.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class SupportViewIT {

    // business arrivals
    private static final String A1 = "00000000-0000-0000-0000-0000000000a1"; // FNBCC01 healthy, fully replied
    private static final String A2 = "00000000-0000-0000-0000-0000000000a2"; // FNBCC02 (cross-client scoping)
    private static final String ASW = "00000000-0000-0000-0000-0000000000a3"; // STAGED_NOT_WRITTEN
    private static final String AEV = "00000000-0000-0000-0000-0000000000a4"; // EMISSION_VISIBLE_NO_REPLY
    private static final String AX = "00000000-0000-0000-0000-0000000000a5"; // external unknown FAILX

    // ops intents
    private static final String IC = "00000000-0000-0000-0000-0000000000c1"; // CONSISTENT
    private static final String IX = "00000000-0000-0000-0000-0000000000c2"; // CONFLICTING
    private static final String IS = "00000000-0000-0000-0000-0000000000c3"; // STALE_ATTEMPT + current CONSISTENT
    private static final String IU = "00000000-0000-0000-0000-0000000000c4"; // UNOBSERVED / LAUNCHED_NO_OUTCOME
    private static final String INE = "00000000-0000-0000-0000-0000000000c5"; // ATTEMPT_NEAR_EXHAUSTION
    private static final String AU = "00000000-0000-0000-0000-0000000000c9"; // ARRIVAL_UNCLAIMED file_arrival

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest.CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @BeforeEach
    void seed() throws SQLException {
        seedBusiness();
        seedOps();
    }

    private void seedBusiness() throws SQLException {
        try (Connection root = DriverManager.getConnection(
                RptJobTest.CRDB.getJdbcUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DELETE FROM public.prg_watermark; DELETE FROM public.rpt_run;"
                    + "DELETE FROM public.prg_report; DELETE FROM public.cir_response;"
                    + "DELETE FROM public.pbsr_resp; DELETE FROM public.sbsr_resp; DELETE FROM public.isr_resp;"
                    + "DELETE FROM public.crw_emission_member; DELETE FROM public.crw_emission;"
                    + "DELETE FROM public.crw_emission_group; DELETE FROM public.validation_log;"
                    + "DELETE FROM public.tx_entry; DELETE FROM public.tx_header");

            // ---- A1: FNBCC01 healthy, fully replied (must NOT be stuck) ----
            s.execute("""
                INSERT INTO public.tx_header (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000b1','%s','MSG1','FNBCC01','20260701',2,'2026-07-01T08:00:00Z')"""
                    .formatted(A1));
            s.execute("""
                INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount) VALUES
                ('%s',1,'E2E1',100.00),('%s',2,'E2E2',200.00)""".formatted(A1, A1));
            s.execute("""
                INSERT INTO public.validation_log (arrival_id, sequence, outcome, created_at) VALUES
                ('%s',1,'PASS','2026-07-01T08:01:00Z'),('%s',2,'PASS','2026-07-01T08:01:00Z')"""
                    .formatted(A1, A1));
            s.execute("""
                INSERT INTO public.crw_emission_group (id, arrival_id, client, source_msg_id, run_date)
                VALUES ('00000000-0000-0000-0000-0000000000f1','%s','FNBCC01','MSGP1','2026-07-01')""".formatted(A1));
            s.execute("""
                INSERT INTO public.crw_emission (id, arrival_id, run_date, file_name, state, group_id, visible_at, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000e1','%s','2026-07-01','FNBCC01_MSGP1_PAIN008.txt',
                        'MATERIALIZED','00000000-0000-0000-0000-0000000000f1','2026-07-01T09:00:00Z','2026-07-01T08:30:00Z')"""
                    .formatted(A1));
            s.execute("""
                INSERT INTO public.crw_emission_member (emission_id, sequence, e2e, amount) VALUES
                ('00000000-0000-0000-0000-0000000000e1',1,'E2E1',100.00),
                ('00000000-0000-0000-0000-0000000000e1',2,'E2E2',200.00)""");
            s.execute("""
                INSERT INTO public.isr_resp (response_file, e2e, status, emission_id, created_at) VALUES
                ('ISR_a1_1.txt','E2E1','ACSC','00000000-0000-0000-0000-0000000000e1','2026-07-01T10:00:00Z'),
                ('ISR_a1_2.txt','E2E2','ACSC','00000000-0000-0000-0000-0000000000e1','2026-07-01T10:00:00Z')""");
            s.execute("""
                INSERT INTO public.pbsr_resp (response_file, e2e, status, emission_id, created_at) VALUES
                ('PBSR_a1_1.txt','E2E1','ACSC','00000000-0000-0000-0000-0000000000e1','2026-07-01T10:10:00Z'),
                ('PBSR_a1_2.txt','E2E2','ACSC','00000000-0000-0000-0000-0000000000e1','2026-07-01T10:10:00Z')""");
            s.execute("""
                INSERT INTO public.cir_response (arrival_id, client, msg_id, route_id, outcome, file_name,
                                                 accepted_count, total_count, written_at, created_at)
                VALUES ('%s','FNBCC01','MSG1','onhost-req','ACK','FNBCC01_MSG1_RESP.txt',2,2,
                        '2026-07-01T08:20:00Z','2026-07-01T08:15:00Z')""".formatted(A1));
            s.execute("""
                INSERT INTO public.prg_watermark (client, e2e, last_status, updated_at) VALUES
                ('FNBCC01','E2E1','ACSC','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E2','ACCC','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E3','RJCT','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E4','CANC','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E5','ACCP','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E6','ACSP','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E7','ACTC','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E8','ACFC','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E9','RCVD','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E10','PDNG','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E11','PATC','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E12','PART','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E13','ACWC','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E14','ACWP','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E-CTV-FAIL','FAIL_ACCOUNT_NOT_FOUND','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E16','CTV_PASS','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E17','ZZZZ','2026-07-01T10:20:00Z'),
                ('FNBCC01','E2E-EXTERNAL-FAIL','FAILX','2026-07-01T10:20:00Z')""");

            // ---- A2: FNBCC02 arrival (cross-client scoping for v_arrival_status/v_client_day) ----
            s.execute("""
                INSERT INTO public.tx_header (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000b2','%s','MSG2','FNBCC02','20260701',1,'2026-07-01T08:05:00Z')"""
                    .formatted(A2));
            s.execute("""
                INSERT INTO public.validation_log (arrival_id, sequence, outcome, created_at)
                VALUES ('%s',1,'FAIL_ACCOUNT_NOT_FOUND','2026-07-01T08:06:00Z')""".formatted(A2));

            // ---- AX: external FAILX must remain non-terminal (not a CTV FAIL_* verdict) ----
            s.execute("""
                INSERT INTO public.tx_header (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000b5','%s','MSGX','FNBCC01','20260701',1,'2026-07-01T08:07:00Z')"""
                    .formatted(AX));
            s.execute("""
                INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount) VALUES
                ('%s',1,'E2E-EXTERNAL-FAIL',300.00)""".formatted(AX));
            s.execute("""
                INSERT INTO public.validation_log (arrival_id, sequence, outcome, created_at)
                VALUES ('%s',1,'PASS','2026-07-01T08:08:00Z')""".formatted(AX));
            s.execute("""
                INSERT INTO public.pbsr_resp (response_file, e2e, status, created_at)
                VALUES ('FNBCC01_PBSR_FAILX.txt','E2E-EXTERNAL-FAIL','FAILX','2026-07-01T10:10:00Z')""");

            // ---- ASW: STAGED_NOT_WRITTEN (cir_response written_at NULL, aged) ----
            s.execute("""
                INSERT INTO public.tx_header (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000b3','%s','MSGSW','FNBCC01','20260701',1,'2026-07-01T08:00:00Z')"""
                    .formatted(ASW));
            s.execute("""
                INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount) VALUES
                ('%s',1,'E2E-CTV-FAIL',100.00)""".formatted(ASW));
            s.execute("""
                INSERT INTO public.validation_log (arrival_id, sequence, outcome, created_at)
                VALUES ('%s',1,'FAIL_ACCOUNT_NOT_FOUND','2026-07-01T08:01:00Z')""".formatted(ASW));
            s.execute("""
                INSERT INTO public.cir_response (arrival_id, client, msg_id, route_id, outcome, file_name,
                                                 accepted_count, total_count, written_at, created_at)
                VALUES ('%s','FNBCC01','MSGSW','onhost-req','ACK','FNBCC01_MSGSW_RESP.txt',1,1,
                        NULL,'2026-07-01T08:15:00Z')""".formatted(ASW));

            // ---- AEV: EMISSION_VISIBLE_NO_REPLY (visible aged, no ISR/SBSR/PBSR) ----
            s.execute("""
                INSERT INTO public.crw_emission_group (id, arrival_id, client, source_msg_id, run_date)
                VALUES ('00000000-0000-0000-0000-0000000000f4','%s','FNBCC01','MSGEV','2026-07-01')""".formatted(AEV));
            s.execute("""
                INSERT INTO public.crw_emission (id, arrival_id, run_date, file_name, state, group_id, visible_at, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000e4','%s','2026-07-01','FNBCC01_MSGEV_PAIN008.txt',
                        'MATERIALIZED','00000000-0000-0000-0000-0000000000f4','2026-07-01T09:00:00Z','2026-07-01T08:30:00Z')"""
                    .formatted(AEV));
        }
    }

    private void seedOps() throws SQLException {
        try (Connection root = DriverManager.getConnection(
                RptJobTest.opsDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DELETE FROM public.duplicate_delivery; DELETE FROM public.stage_outcome;"
                    + "DELETE FROM public.launch_intent; DELETE FROM public.file_arrival");
            // A1 = healthy claimed arrival (intent + outcome); AU = unclaimed (no intent)
            s.execute("""
                INSERT INTO public.file_arrival (id, route_id, client_token, status, physical_filename, arrived_at) VALUES
                ('%s','onhost-req','FNBCC01','ROUTED','FNBCC01_INBOUND_a1.txt','2026-07-01T08:00:00Z'),
                ('%s','onhost-req','FNBCC01','ROUTED','FNBCC01_UNCLAIMED.txt','2026-07-01T08:00:00Z')"""
                    .formatted(A1, AU));
            s.execute("""
                INSERT INTO public.launch_intent (id, arrival_id, stage, job_name, status, attempt, created_at) VALUES
                ('%s','%s','CRR','dcre-crr-ic','SUCCEEDED',0,'2026-07-01T08:03:00Z'),
                ('%s',NULL,'CTV','dcre-ctv-ix','SUCCEEDED',0,'2026-07-01T08:03:00Z'),
                ('%s',NULL,'CRW','dcre-crw-is','SUCCEEDED',1,'2026-07-01T08:03:00Z'),
                ('%s',NULL,'PRG','dcre-prg-iu','LAUNCHED',0,'2026-07-01T08:03:00Z'),
                ('%s',NULL,'HCS','dcre-hcs-ine','LAUNCHED',3,'2026-07-01T08:03:00Z')"""
                    .formatted(IC, A1, IX, IS, IU, INE));
            s.execute("""
                INSERT INTO public.stage_outcome (intent_id, outcome, exit_code, attempt, observed_at) VALUES
                ('%s','BUSINESS_ACCEPTED',0,0,'2026-07-01T08:05:00Z'),
                ('%s','BUSINESS_ACCEPTED',0,0,'2026-07-01T08:05:00Z'),
                ('%s','TECH_FAILED',1,0,'2026-07-01T08:06:00Z'),
                ('%s','TECH_FAILED',1,0,'2026-07-01T08:05:00Z'),
                ('%s','BUSINESS_ACCEPTED',0,1,'2026-07-01T08:10:00Z'),
                ('%s','TECH_FAILED',1,3,'2026-07-01T08:20:00Z')"""
                    .formatted(IC, IX, IX, IS, IS, INE));
        }
    }

    // ----- (a) v_ops_attempts: attempt-evidence classification -------------------------------

    @Test
    void opsAttemptsClassifiesEvidenceStateWithoutDurationClaim() throws Exception {
        try (Connection c = RptSecurityIT.forRole("agt_ops", "rpt_internal")) {
            Map<String, String> state = new HashMap<>();
            Map<String, Integer> obs = new HashMap<>();
            Map<String, Integer> distinct = new HashMap<>();
            Map<String, Boolean> current = new HashMap<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("""
                     SELECT intent_id, attempt, attempt_evidence_state, is_current_attempt,
                            observation_count, distinct_outcome_count
                     FROM rpt.v_ops_attempts""")) {
                while (rs.next()) {
                    String key = rs.getString("intent_id") + "#" + rs.getInt("attempt");
                    state.put(key, rs.getString("attempt_evidence_state"));
                    obs.put(key, rs.getInt("observation_count"));
                    distinct.put(key, rs.getInt("distinct_outcome_count"));
                    current.put(key, rs.getBoolean("is_current_attempt"));
                }
            }
            assertEquals("CONSISTENT", state.get(IC + "#0"), "one attempt, one outcome");
            assertEquals(1, obs.get(IC + "#0"));
            assertTrue(current.get(IC + "#0"));

            assertEquals("CONFLICTING", state.get(IX + "#0"), "observer recorded contradictory outcomes");
            assertEquals(2, obs.get(IX + "#0"));
            assertEquals(2, distinct.get(IX + "#0"));

            assertEquals("STALE_ATTEMPT", state.get(IS + "#0"), "attempt 0 superseded by current attempt 1");
            assertFalse(current.get(IS + "#0"));
            assertEquals("CONSISTENT", state.get(IS + "#1"), "current attempt 1 has a single outcome");
            assertTrue(current.get(IS + "#1"));

            assertEquals("UNOBSERVED", state.get(IU + "#0"), "launched, no outcome recorded");
            assertEquals(0, obs.get(IU + "#0"));

            // NO execution-duration column may be exposed (ledger records observation time only).
            assertFalse(columnNames(c, "rpt.v_ops_attempts").stream()
                            .anyMatch(n -> n.contains("duration") || n.contains("elapsed") || n.contains("runtime")),
                    "v_ops_attempts must not claim execution duration");
        }
    }

    // ----- (b) stuck-scan: each wedge class surfaces; a healthy arrival does not --------------

    @Test
    void opsStuckSurfacesEachWedgeClassButNotHealthy() throws Exception {
        try (Connection c = RptSecurityIT.forRole("agt_ops", "rpt_internal")) {
            Map<String, String> bySubject = new HashMap<>();
            Set<String> classes = new HashSet<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT wedge_class, subject FROM rpt.v_ops_stuck")) {
                while (rs.next()) {
                    classes.add(rs.getString("wedge_class"));
                    bySubject.put(rs.getString("subject"), rs.getString("wedge_class"));
                }
            }
            assertEquals("LAUNCHED_NO_OUTCOME", bySubject.get("dcre-prg-iu"));
            assertEquals("ATTEMPT_NEAR_EXHAUSTION", bySubject.get("dcre-hcs-ine"));
            assertEquals("ARRIVAL_UNCLAIMED", bySubject.get("FNBCC01_UNCLAIMED.txt"));
            assertTrue(classes.containsAll(List.of(
                    "LAUNCHED_NO_OUTCOME", "ATTEMPT_NEAR_EXHAUSTION", "ARRIVAL_UNCLAIMED")));
            assertFalse(bySubject.containsKey("dcre-crr-ic"),
                    "healthy claimed+observed arrival is not wedged");
            assertFalse(bySubject.containsKey("FNBCC01_INBOUND_a1.txt"),
                    "healthy claimed arrival is not unclaimed");
        }
    }

    @Test
    void businessStuckSurfacesEachWedgeClassButNotHealthy() throws Exception {
        try (Connection c = RptSecurityIT.forRole("rpt_internal")) {
            Map<String, String> bySubject = new HashMap<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT wedge_class, subject FROM rpt.v_stuck")) {
                while (rs.next()) {
                    bySubject.put(rs.getString("subject"), rs.getString("wedge_class"));
                }
            }
            assertEquals("STAGED_NOT_WRITTEN", bySubject.get("FNBCC01_MSGSW_RESP.txt"));
            assertEquals("EMISSION_VISIBLE_NO_REPLY", bySubject.get("FNBCC01_MSGEV_PAIN008.txt"));
            assertFalse(bySubject.containsKey("FNBCC01_MSG1_RESP.txt"),
                    "written cir_response is not staged-not-written");
            assertFalse(bySubject.containsKey("FNBCC01_MSGP1_PAIN008.txt"),
                    "replied emission is not visible-no-reply");
        }
    }

    // ----- (c) v_correlation_index: MsgId + e2e resolve to the arrival ------------------------

    @Test
    void correlationIndexResolvesMsgIdAndEndToEnd() throws Exception {
        try (Connection c = RptSecurityIT.forRole("rpt_internal"); Statement s = c.createStatement()) {
            ResultSet msg = s.executeQuery(
                    "SELECT arrival_id, client, source_table FROM rpt.v_correlation_index "
                            + "WHERE key_type = 'MSG_ID' AND key_value = 'MSG1'");
            assertTrue(msg.next(), "MsgId MSG1 resolves");
            assertEquals(A1, msg.getString("arrival_id"));
            assertEquals("FNBCC01", msg.getString("client"));

            ResultSet e2e = s.executeQuery(
                    "SELECT arrival_id FROM rpt.v_correlation_index "
                            + "WHERE key_type = 'END_TO_END_ID' AND key_value = 'E2E1'");
            assertTrue(e2e.next(), "e2e E2E1 resolves");
            assertEquals(A1, e2e.getString("arrival_id"));

            // exact/sargable: the raw value is stored, no lower()/trim rewriting.
            ResultSet exact = s.executeQuery(
                    "SELECT count(*) FROM rpt.v_correlation_index WHERE key_value = 'msg1'");
            assertTrue(exact.next());
            assertEquals(0, exact.getInt(1), "values are exact, not case-folded");
        }
    }

    // ----- (c') v_emission_visibility + v_psr_watermark_lag evidence --------------------------

    @Test
    void emissionVisibilityAndWatermarkLagExposeEvidence() throws Exception {
        try (Connection c = RptSecurityIT.forRole("rpt_internal"); Statement s = c.createStatement()) {
            ResultSet vis = s.executeQuery(
                    "SELECT visible_count, first_crw_visible_at FROM rpt.v_emission_visibility "
                            + "WHERE group_id = '00000000-0000-0000-0000-0000000000f1'");
            assertTrue(vis.next());
            assertEquals(1, vis.getInt("visible_count"), "one visible emission in the group");
            assertTrue(vis.getString("first_crw_visible_at") != null, "first_crw_visible_at populated");

            ResultSet lag = s.executeQuery(
                    "SELECT non_terminal_rows, hours_since_last_advance FROM rpt.v_psr_watermark_lag "
                            + "WHERE client = 'FNBCC01'");
            assertTrue(lag.next());
            assertEquals(14, lag.getInt("non_terminal_rows"),
                    "fail-closed watermark counting includes ambiguous CTV FAIL until provenance exists");
            assertTrue(lag.getDouble("hours_since_last_advance") > 24, "watermark frozen for many hours");
        }
    }

    // ----- (c'') AX fixture guarantee: external FAILX stays non-terminal ----------------------

    @Test
    void externalFailxStaysNonTerminalAndNeverBecomesACtvVerdict() throws Exception {
        // The AX fixture comment's claim, asserted: the FAILX PBSR row carries neither response
        // identity (emission_id NULL, orgnl_msg_id matches no outbound_msg_id), so it binds
        // nowhere; AX keeps its CTV evidence and FAILX is never reported as a CTV FAIL_* verdict
        // or as any terminal outcome.
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            ResultSet tx = s.executeQuery(
                    "SELECT status, ctv_outcome FROM rpt.v_tx WHERE arrival_id = '" + AX + "'");
            assertTrue(tx.next());
            assertEquals("CTV_PASS", tx.getString("status"),
                    "unbound FAILX leaves AX at its CTV evidence, not a terminal verdict");
            assertEquals("PASS", tx.getString("ctv_outcome"), "FAILX is not a CTV FAIL_* outcome");
            assertFalse(tx.next());

            ResultSet fails = s.executeQuery(
                    "SELECT count(*) FROM rpt.v_fails WHERE e2e = 'E2E-EXTERNAL-FAIL'");
            assertTrue(fails.next());
            assertEquals(0, fails.getInt(1), "FAILX must never surface as a failure row");
        }
    }

    // ----- (d) client-scoped-safe views: session scoping + zero filenames/job names -----------

    @Test
    void arrivalStatusIsClientScopedAndLeaksNoFilenamesOrJobNames() throws Exception {
        try (Connection c = RptSecurityIT.forRole("fnbcc01")) {
            Set<String> clients = new HashSet<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT client FROM rpt.v_arrival_status")) {
                while (rs.next()) {
                    clients.add(rs.getString("client"));
                }
            }
            assertEquals(Set.of("FNBCC01"), clients, "fnbcc01 sees only its own arrivals");
            assertNoNameLeak(columnNames(c, "rpt.v_arrival_status"), "v_arrival_status");
        }
        try (Connection c = RptSecurityIT.forRole("fnbcc01")) {
            Set<String> clients = new HashSet<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT client FROM rpt.v_client_day")) {
                while (rs.next()) {
                    clients.add(rs.getString("client"));
                }
            }
            assertEquals(Set.of("FNBCC01"), clients, "fnbcc01 sees only its own day rollup");
            assertNoNameLeak(columnNames(c, "rpt.v_client_day"), "v_client_day");
        }
        // rpt_internal sees every client through the OR-branch of the session-identity predicate.
        try (Connection c = RptSecurityIT.forRole("rpt_internal"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT count(DISTINCT client) FROM rpt.v_arrival_status");
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 2, "internal role sees FNBCC01 and FNBCC02");
        }
    }

    @Test
    void arrivalStatusConservativeStateIsEvidenceBased() throws Exception {
        try (Connection c = RptSecurityIT.forRole("rpt_internal"); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT conservative_state FROM rpt.v_arrival_status WHERE arrival_id = '" + A1 + "'");
            assertTrue(rs.next());
            assertEquals("REPLIED", rs.getString("conservative_state"),
                    "A1 emitted 2 members, both PBSR-replied");
            ResultSet f = s.executeQuery(
                    "SELECT conservative_state FROM rpt.v_arrival_status WHERE arrival_id = '" + A2 + "'");
            assertTrue(f.next());
            assertEquals("VALIDATION_FAILED", f.getString("conservative_state"),
                    "A2 failed validation and never emitted");
        }
    }

    // ----- (e) internal views deny / zero-row a client role -----------------------------------

    @Test
    void internalViewsAreNotVisibleToClientRoles() throws Exception {
        // agt_ops: no client grant at all -> hard 42501 denial.
        try (Connection c = RptSecurityIT.forRole("agt_ops", "fnbcc01"); Statement s = c.createStatement()) {
            for (String view : List.of("v_ops_attempts", "v_ops_stuck", "v_ops_client_day")) {
                SQLException ex = assertThrows(SQLException.class,
                        () -> s.executeQuery("SELECT count(*) FROM rpt." + view));
                assertEquals("42501", ex.getSQLState(), "no client grant on agt_ops view rpt." + view);
            }
        }
        // business: default-privilege grant exists, so the current_user predicate returns zero rows.
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            for (String view : List.of("v_stuck", "v_correlation_index", "v_emission_visibility",
                    "v_psr_watermark_lag")) {
                ResultSet rs = s.executeQuery("SELECT count(*) FROM rpt." + view);
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "internal business view rpt." + view + " zero-rows a client role");
            }
        }
    }

    // ----- helpers ---------------------------------------------------------------------------

    private Set<String> columnNames(final Connection c, final String view) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM " + view + " WHERE 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                names.add(md.getColumnName(i).toLowerCase());
            }
        }
        return names;
    }

    private void assertNoNameLeak(final Set<String> cols, final String view) {
        for (String col : cols) {
            assertFalse(col.contains("file") || col.contains("job") || col.contains("path")
                            || col.equals("physical_filename") || col.equals("response_file"),
                    view + " client-safe view must not expose column '" + col + "'");
        }
    }
}
