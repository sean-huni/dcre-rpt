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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-58 Task 12 conformance matrix for the four file-trace views
 * ({@code rpt.v_file_index}/{@code rpt.v_flow_trace} in the business DB,
 * {@code rpt.v_ops_file_index}/{@code rpt.v_ops_flow} in agt_ops). Proves, per plan Task 12:
 * (a) every file class resolves with correct client/direction/kind/route; (b) the surfaced
 * file_name byte-equals the owner column; (c) rpt's MARK_RAN pre-created guard tables byte-match
 * the owner DDL; (d) every externally-received name column is >= 512 wide; (e) a non-rpt_internal
 * role sees zero rows (business) / is denied (ops).
 *
 * <p>Owner tables are pre-seeded in {@link RptJobTest}'s static block before any context boots
 * (CRDB validates view dependencies at CREATE time); this IT seeds owner ROWS as root and reads
 * the views back as {@code rpt_internal} pinned to present-time via {@link RptSecurityIT#forRole}.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class FileTraceViewIT {

    private static final String A1 = "00000000-0000-0000-0000-0000000000a1";
    private static final String A2 = "00000000-0000-0000-0000-0000000000a2";
    private static final String A3 = "00000000-0000-0000-0000-0000000000a3";

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
            s.execute("DELETE FROM public.rpt_run; DELETE FROM public.prg_report;"
                    + "DELETE FROM public.cir_response; DELETE FROM public.pbsr_resp;"
                    + "DELETE FROM public.sbsr_resp; DELETE FROM public.isr_resp;"
                    + "DELETE FROM public.crw_emission_member; DELETE FROM public.crw_emission;"
                    + "DELETE FROM public.crw_emission_group; DELETE FROM public.validation_log;"
                    + "DELETE FROM public.tx_entry; DELETE FROM public.tx_header");
            s.execute("""
                INSERT INTO public.tx_header (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000b1','%s','MSG1','FNBCC01','20260701',4,'2026-07-01T08:00:00Z')"""
                    .formatted(A1));
            s.execute("""
                INSERT INTO public.validation_log (arrival_id, sequence, outcome, created_at) VALUES
                ('%s',1,'PASS','2026-07-01T08:01:00Z'),('%s',2,'PASS','2026-07-01T08:01:00Z')"""
                    .formatted(A1, A1));
            s.execute("""
                INSERT INTO public.crw_emission_group (id, arrival_id, client, source_msg_id, run_date)
                VALUES ('00000000-0000-0000-0000-0000000000f1','%s','FNBCC01','MSGP1','2026-07-01')"""
                    .formatted(A1));
            s.execute("""
                INSERT INTO public.crw_emission (id, arrival_id, run_date, file_name, state, group_id, visible_at, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000e1','%s','2026-07-01','FNBCC01_MSGP1_PAIN008.txt',
                        'MATERIALIZED','00000000-0000-0000-0000-0000000000f1','2026-07-01T09:00:00Z','2026-07-01T08:30:00Z')"""
                    .formatted(A1));
            s.execute("""
                INSERT INTO public.isr_resp (response_file, e2e, status, emission_id, created_at)
                VALUES ('ISR_REPLY_a1.txt','E2E1','ACSC','00000000-0000-0000-0000-0000000000e1','2026-07-01T10:00:00Z')""");
            s.execute("""
                INSERT INTO public.sbsr_resp (response_file, e2e, status, emission_id, created_at)
                VALUES ('SBSR_REPLY_a1.txt','E2E1','ACSC','00000000-0000-0000-0000-0000000000e1','2026-07-01T10:05:00Z')""");
            s.execute("""
                INSERT INTO public.pbsr_resp (response_file, e2e, status, emission_id, created_at)
                VALUES ('PBSR_REPLY_a1.txt','E2E1','ACSC','00000000-0000-0000-0000-0000000000e1','2026-07-01T10:10:00Z')""");
            s.execute("""
                INSERT INTO public.cir_response (arrival_id, client, msg_id, route_id, outcome, file_name,
                                                 reason, accepted_count, total_count, written_at, created_at)
                VALUES ('%s','FNBCC01','MSG1','onhost-req','ACK','FNBCC01_MSG1_onhost-req_RESP.txt',
                        NULL,4,4,'2026-07-01T08:20:00Z','2026-07-01T08:15:00Z')""".formatted(A1));
            s.execute("""
                INSERT INTO public.prg_report (client, report_type, trigger_kind, window_key, parent_source_msg_id,
                                               file_name, job_name, created_at)
                VALUES ('FNBCC01','PSR','SCHEDULED','W1','MSGP1','FNBCC01_PSR_20260701.txt','local-prg-501',
                        '2026-07-01T16:00:00Z')""");
            s.execute("""
                INSERT INTO public.rpt_run (job_name, outcome, created_at)
                VALUES ('local-rpt-777','BUSINESS_ACCEPTED','2026-07-01T17:00:00Z')""");
        }
    }

    private void seedOps() throws SQLException {
        try (Connection root = DriverManager.getConnection(
                RptJobTest.opsDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DELETE FROM public.duplicate_delivery; DELETE FROM public.stage_outcome;"
                    + "DELETE FROM public.launch_intent; DELETE FROM public.file_arrival");
            s.execute("""
                INSERT INTO public.file_arrival (id, route_id, client_token, status, physical_filename, quarantine_reason, arrived_at) VALUES
                ('%s','onhost-req','FNBCC01','ROUTED','FNBCC01_INBOUND_a1.txt',NULL,'2026-07-01T08:00:00Z'),
                ('%s','fint-resp','FNBCC01','ROUTED','FINT_REPLY_a2.txt',NULL,'2026-07-01T10:00:00Z'),
                ('%s','onhost-req','FNBCC02','QUARANTINED','BAD_a3.txt','UNPARSEABLE_FILENAME','2026-07-01T08:02:00Z')"""
                    .formatted(A1, A2, A3));
            s.execute("""
                INSERT INTO public.launch_intent (id, arrival_id, stage, job_name, status, attempt, created_at)
                VALUES ('00000000-0000-0000-0000-0000000000c1','%s','CRR','dcre-crr-a1','LAUNCHED',0,'2026-07-01T08:03:00Z')"""
                    .formatted(A1));
            s.execute("""
                INSERT INTO public.stage_outcome (intent_id, outcome, exit_code, attempt, observed_at)
                VALUES ('00000000-0000-0000-0000-0000000000c1','BUSINESS_ACCEPTED',0,0,'2026-07-01T08:05:00Z')""");
            s.execute("""
                INSERT INTO public.duplicate_delivery (claim_id, original_arrival_id, route_id, client_token,
                                                       physical_filename, payload_sha256, sunk_path, observed_at)
                VALUES ('00000000-0000-0000-0000-0000000000d1','%s','onhost-req','FNBCC01','DUP_a1_redelivery.txt',
                        'abc123','/exchange/archive/duplicates/d1_DUP.txt','2026-07-01T11:00:00Z')""".formatted(A1));
        }
    }

    // ----- (a) per-file-class resolution + (b) name-parity -----------------------------------

    @Test
    void opsFileClassesResolveWithCorrectClientDirectionKindRoute() throws Exception {
        try (Connection c = RptSecurityIT.forRole("agt_ops", "rpt_internal")) {
            Map<String, String> copybook = indexRow(c, "rpt.v_ops_file_index", "FNBCC01_INBOUND_a1.txt");
            assertEquals("FNBCC01", copybook.get("client"));
            assertEquals("INBOUND", copybook.get("direction"));
            assertEquals("COPYBOOK", copybook.get("kind"));
            assertEquals("onhost-req", copybook.get("route"));
            assertEquals("FNBCC01_INBOUND_a1.txt", copybook.get("file_name"), "name-parity: file_arrival.physical_filename");

            Map<String, String> reply = indexRow(c, "rpt.v_ops_file_index", "FINT_REPLY_a2.txt");
            assertEquals("FINT_REPLY", reply.get("kind"));
            assertEquals("fint-resp", reply.get("route"));
            assertEquals("INBOUND", reply.get("direction"));

            Map<String, String> quar = indexRow(c, "rpt.v_ops_file_index", "BAD_a3.txt");
            assertEquals("QUARANTINE", quar.get("kind"));
            assertEquals("FNBCC02", quar.get("client"));
            assertEquals("INBOUND", quar.get("direction"));
            assertTrue(quar.get("state").contains("UNPARSEABLE_FILENAME"), "quarantine reason surfaced as state");

            Map<String, String> dup = indexRow(c, "rpt.v_ops_file_index", "DUP_a1_redelivery.txt");
            assertEquals("DUPLICATE", dup.get("kind"));
            assertEquals("FNBCC01", dup.get("client"));
            assertEquals(A1, dup.get("related_arrival_id"), "duplicate -> original arrival hop");

            Map<String, String> seam = indexRow(c, "rpt.v_ops_file_index", "dcre-crr-a1");
            assertEquals("OUTCOME_SEAM", seam.get("kind"));
            assertEquals("INTERNAL", seam.get("direction"));
            assertEquals("outcomes", seam.get("route"));
            assertEquals("dcre-crr-a1", seam.get("job_name"));
        }
    }

    @Test
    void businessFileClassesResolveWithCorrectClientDirectionKindRoute() throws Exception {
        try (Connection c = RptSecurityIT.forRole("rpt_internal")) {
            Map<String, String> pain = indexRow(c, "rpt.v_file_index", "FNBCC01_MSGP1_PAIN008.txt");
            assertEquals("FNBCC01", pain.get("client"));
            assertEquals("OUTBOUND", pain.get("direction"));
            assertEquals("PAIN008", pain.get("kind"));
            assertEquals("fint-req", pain.get("route"));
            assertEquals(A1, pain.get("arrival_id"));

            for (String[] arm : List.of(
                    new String[]{"ISR_REPLY_a1.txt", "ISR"},
                    new String[]{"SBSR_REPLY_a1.txt", "SBSR"},
                    new String[]{"PBSR_REPLY_a1.txt", "PBSR"})) {
                Map<String, String> reply = indexRow(c, "rpt.v_file_index", arm[0]);
                assertEquals(arm[1], reply.get("kind"), arm[0]);
                assertEquals("INBOUND", reply.get("direction"), arm[0]);
                assertEquals("fint-resp", reply.get("route"), arm[0]);
                assertEquals("FNBCC01", reply.get("client"), arm[0]);
                assertEquals(A1, reply.get("arrival_id"), arm[0] + " resolves via emission_id -> crw_emission.arrival_id");
                assertEquals(arm[0], reply.get("file_name"), "name-parity: *_resp.response_file");
            }

            Map<String, String> resp = indexRow(c, "rpt.v_file_index", "FNBCC01_MSG1_onhost-req_RESP.txt");
            assertEquals("RESP", resp.get("kind"));
            assertEquals("OUTBOUND", resp.get("direction"));
            assertEquals("onhost-resp", resp.get("route"));
            assertEquals(A1, resp.get("arrival_id"));
            assertTrue(resp.get("state").startsWith("ACK"), "outcome surfaced");
            assertTrue(resp.get("state").contains("4/4"), "acceptance ratio surfaced from accepted_count/total_count");

            Map<String, String> psr = indexRow(c, "rpt.v_file_index", "FNBCC01_PSR_20260701.txt");
            assertEquals("PSR", psr.get("kind"));
            assertEquals("OUTBOUND", psr.get("direction"));
            assertEquals("onhost-resp", psr.get("route"));
            assertEquals("local-prg-501", psr.get("job_name"));
            assertEquals(A1, psr.get("arrival_id"), "PSR binds to arrival via group.source_msg_id = parent_source_msg_id");

            Map<String, String> seam = indexRow(c, "rpt.v_file_index", "local-rpt-777");
            assertEquals("OUTCOME_SEAM", seam.get("kind"));
            assertEquals("INTERNAL", seam.get("direction"));
            assertEquals("DCRE", seam.get("client"));
            assertEquals("local-rpt-777", seam.get("job_name"));
        }
    }

    // ----- flow views: ordered timeline + time_kind provenance -------------------------------

    @Test
    void flowViewsSurfaceOrderedStepsWithTrueTimeKind() throws Exception {
        try (Connection c = RptSecurityIT.forRole("rpt_internal")) {
            Map<String, String> steps = flowSteps(c, "rpt.v_flow_trace", A1);
            for (String step : List.of("CRR_INGESTED", "CTV_VALIDATED", "CIR_RESP_STAGED", "CIR_RESP_WRITTEN",
                    "CRW_PLANNED", "CRW_VISIBLE", "IXR_REPLY", "SXR_REPLY", "PXR_REPLY", "PRG_REPORTED")) {
                assertTrue(steps.containsKey(step), "business flow missing " + step);
            }
            assertEquals("VISIBLE_AT", steps.get("CRW_VISIBLE"), "time_kind is the timestamp's true meaning");
            assertEquals("WRITTEN_AT", steps.get("CIR_RESP_WRITTEN"));
        }
        try (Connection c = RptSecurityIT.forRole("agt_ops", "rpt_internal")) {
            Map<String, String> steps = flowSteps(c, "rpt.v_ops_flow", A1);
            for (String step : List.of("ARRIVED", "CRR_INTENDED", "CRR_BUSINESS_ACCEPTED")) {
                assertTrue(steps.containsKey(step), "ops flow missing " + step);
            }
            assertEquals("OBSERVED_AT", steps.get("CRR_BUSINESS_ACCEPTED"),
                    "outcome step carries observation time, never job-completion time (spec 11.2)");
            assertEquals("ARRIVED_AT", steps.get("ARRIVED"));
        }
    }

    // ----- (c) shape-parity: pre-created guard tables byte-match the owner DDL ----------------

    @Test
    void preCreatedGuardTablesByteMatchOwnerDdl() throws Exception {
        try (Connection biz = DriverManager.getConnection(
                RptJobTest.CRDB.getJdbcUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword())) {
            assertEquals(Set.of("id", "arrival_id", "client", "msg_id", "route_id", "outcome", "file_name",
                            "reason", "accepted_count", "total_count", "written_at", "created_at"),
                    columns(biz, "cir_response"), "cir_response column set (cir 003)");
            assertEquals(16, charLen(biz, "cir_response", "client"));
            assertEquals(64, charLen(biz, "cir_response", "msg_id"));
            assertEquals(64, charLen(biz, "cir_response", "route_id"));
            assertEquals(4, charLen(biz, "cir_response", "outcome"));
            assertEquals(512, charLen(biz, "cir_response", "file_name"));
            assertEquals(64, charLen(biz, "cir_response", "reason"));

            assertEquals(Set.of("id", "job_name", "outcome", "created_at"),
                    columns(biz, "rpt_run"), "rpt_run column set (rpt 005-rpt-run)");
            assertEquals(63, charLen(biz, "rpt_run", "job_name"));
            assertEquals(32, charLen(biz, "rpt_run", "outcome"));

            assertTrue(columns(biz, "prg_report").contains("job_name"),
                    "prg_report.job_name added by the MARK_RAN addColumn pre-create (prg 004)");
            assertEquals(63, charLen(biz, "prg_report", "job_name"));
        }
        try (Connection ops = DriverManager.getConnection(
                RptJobTest.opsDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword())) {
            assertEquals(Set.of("id", "claim_id", "original_arrival_id", "route_id", "client_token",
                            "physical_filename", "payload_sha256", "sunk_path", "observed_at"),
                    columns(ops, "duplicate_delivery"), "duplicate_delivery column set (agt 006)");
            assertEquals(64, charLen(ops, "duplicate_delivery", "route_id"));
            assertEquals(64, charLen(ops, "duplicate_delivery", "client_token"));
            assertEquals(512, charLen(ops, "duplicate_delivery", "physical_filename"));
            assertEquals(64, charLen(ops, "duplicate_delivery", "payload_sha256"));
            assertEquals(1024, charLen(ops, "duplicate_delivery", "sunk_path"));
        }
    }

    // ----- (d) width-conformance: externally-received name columns are >= 512 ----------------

    @Test
    void externallyReceivedNameColumnsAreAtLeast512() throws Exception {
        try (Connection biz = DriverManager.getConnection(
                RptJobTest.CRDB.getJdbcUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword())) {
            for (String t : List.of("isr_resp", "sbsr_resp", "pbsr_resp")) {
                assertTrue(charLen(biz, t, "response_file") >= 512, t + ".response_file must be >= 512 post-widening");
            }
            assertTrue(charLen(biz, "cir_response", "file_name") >= 512, "cir_response.file_name >= 512");
            // Internally-generated names are pre-existing owner reality (< 512), NOT altered by this program.
            assertEquals(128, charLen(biz, "crw_emission", "file_name"), "crw_emission.file_name is pre-existing 128");
            assertEquals(128, charLen(biz, "prg_report", "file_name"), "prg_report.file_name is an internal report name (128)");
        }
        try (Connection ops = DriverManager.getConnection(
                RptJobTest.opsDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword())) {
            assertTrue(charLen(ops, "file_arrival", "physical_filename") >= 512, "file_arrival.physical_filename >= 512");
            assertTrue(charLen(ops, "duplicate_delivery", "physical_filename") >= 512, "duplicate_delivery.physical_filename >= 512");
        }
    }

    // ----- (e) tenant boundary: a non-rpt_internal role never sees trace rows ----------------

    @Test
    void nonInternalRoleSeesNoTraceRows() throws Exception {
        try (Connection c = RptSecurityIT.forRole("fnbcc01"); Statement s = c.createStatement()) {
            for (String view : List.of("v_file_index", "v_flow_trace")) {
                ResultSet rs = s.executeQuery("SELECT count(*) FROM rpt." + view);
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "client role sees zero rows through the current_user predicate on " + view);
            }
        }
        // agt_ops grants only rpt_internal (wall 1), so a client role is denied outright there.
        try (Connection c = RptSecurityIT.forRole("agt_ops", "fnbcc01"); Statement s = c.createStatement()) {
            for (String view : List.of("v_ops_file_index", "v_ops_flow")) {
                SQLException ex = assertThrows(SQLException.class,
                        () -> s.executeQuery("SELECT count(*) FROM rpt." + view));
                assertEquals("42501", ex.getSQLState(), "no client grant on agt_ops view rpt." + view);
            }
        }
    }

    // ----- M4 regression: PSR arm must not fan out across run_dates (review M4) ---------------

    /**
     * A single parent MsgId re-emitted on two run_dates: {@code crw_emission_group} is unique on
     * {@code (client, source_msg_id, run_date)} (crw 003 {@code uq_emission_group_client_msg_run}),
     * so {@code (client, source_msg_id)} alone matches BOTH groups. prg_report carries no matching
     * run_date dimension (window_key is a free-form label, created_at is report time), so the PSR
     * arm binds to the LATEST-run_date group. One PSR file (one prg_report row) must resolve to
     * exactly ONE arrival, else the killer query's arrival CTE is over-seeded with a cross-run_date
     * twin's distinct arrival_id.
     */
    @Test
    void psrFileWithTwoRunDateCollisionYieldsExactlyOneRowNotFanout() throws Exception {
        final String af1 = "aaaaaaaa-0000-0000-0000-000000000001"; // earlier run_date arrival
        final String af2 = "aaaaaaaa-0000-0000-0000-000000000002"; // later  run_date arrival (the live one)
        try (Connection root = DriverManager.getConnection(
                RptJobTest.CRDB.getJdbcUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("""
                INSERT INTO public.crw_emission_group (id, arrival_id, client, source_msg_id, run_date) VALUES
                ('aaaaaaaa-0000-0000-0000-0000000000fa','%s','FNBCC01','MSGFAN','2026-07-01'),
                ('aaaaaaaa-0000-0000-0000-0000000000fb','%s','FNBCC01','MSGFAN','2026-07-02')"""
                    .formatted(af1, af2));
            s.execute("""
                INSERT INTO public.prg_report (client, report_type, trigger_kind, window_key, parent_source_msg_id,
                                               file_name, job_name, created_at)
                VALUES ('FNBCC01','PSR','SCHEDULED','WFAN','MSGFAN','FNBCC01_PSR_FANOUT.txt','local-prg-777',
                        '2026-07-02T16:00:00Z')""");
        }
        try (Connection c = RptSecurityIT.forRole("rpt_internal")) {
            // exactly one v_file_index row for the PSR file (indexRow asserts a single row),
            // bound to the LATEST run_date's arrival.
            Map<String, String> psr = indexRow(c, "rpt.v_file_index", "FNBCC01_PSR_FANOUT.txt");
            assertEquals("PSR", psr.get("kind"));
            assertEquals(af2, psr.get("related_arrival_id"),
                    "PSR binds to the latest-run_date group, not a cross-run_date fan-out");
            assertEquals(af2, psr.get("arrival_id"));
            // exactly one PRG_REPORTED flow row for that file: one arrival, not one per colliding run_date.
            assertEquals(1, countFlowStepForFile(c, "rpt.v_flow_trace", "PRG_REPORTED", "FNBCC01_PSR_FANOUT.txt"),
                    "one PRG_REPORTED flow row per PSR file, not one per colliding run_date");
        }
    }

    // ----- helpers ---------------------------------------------------------------------------

    /** Row count of a flow-view step keyed on the file name it carries in {@code detail}. */
    private int countFlowStepForFile(final Connection c, final String view, final String step,
                                     final String fileName) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + view
                     + " WHERE step = '" + step + "' AND detail = '" + fileName + "'")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    /** Exactly one index-view row for the given file_name, as a column->string map. */
    private Map<String, String> indexRow(final Connection c, final String view, final String fileName)
            throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT * FROM " + view + " WHERE file_name = '" + fileName + "'")) {
            assertTrue(rs.next(), "no row in " + view + " for " + fileName);
            Map<String, String> row = new HashMap<>();
            int cols = rs.getMetaData().getColumnCount();
            for (int i = 1; i <= cols; i++) {
                row.put(rs.getMetaData().getColumnName(i), rs.getString(i));
            }
            assertTrue(!rs.next(), "expected exactly one row in " + view + " for " + fileName);
            return row;
        }
    }

    /** step -> time_kind for every flow-view row of one arrival. */
    private Map<String, String> flowSteps(final Connection c, final String view, final String arrivalId)
            throws SQLException {
        Map<String, String> steps = new HashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT step, time_kind FROM " + view + " WHERE arrival_id = '" + arrivalId + "'")) {
            while (rs.next()) {
                steps.put(rs.getString("step"), rs.getString("time_kind"));
            }
        }
        return steps;
    }

    private Set<String> columns(final Connection c, final String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private int charLen(final Connection c, final String table, final String column) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT character_maximum_length FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = '" + table
                             + "' AND column_name = '" + column + "'")) {
            assertTrue(rs.next(), "no such column " + table + "." + column);
            return rs.getInt(1);
        }
    }
}
