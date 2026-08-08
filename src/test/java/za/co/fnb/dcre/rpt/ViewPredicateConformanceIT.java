package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-58 review O1: schema-level tenant-boundary conformance over the WHOLE business-DB
 * {@code rpt.*} view surface, so the "a client role sees zero rows" proof is EXHAUSTIVE rather
 * than a hand-maintained per-view list (FileTraceViewIT + SupportViewIT each check a fixed subset;
 * a future internal view added without the predicate would silently leak cross-client filenames).
 *
 * <p>The static {@code verifyViewPredicates} Gradle gate (SCRUM-51, wired into {@code check})
 * already fails the build if any {@code <createView>} body in the changelog omits {@code current_user}.
 * This IT does NOT duplicate that static scan; it adds what the gate cannot see: RUNTIME behaviour
 * against a live CockroachDB, enumerated from the deployed schema (not the changelog text), proving
 * that every internally-scoped view actually returns zero rows to a client role WHILE data is present.
 *
 * <p>Boundary model DERIVED from observed definitions (not from a "filename-bearing" heuristic:
 * {@code v_recon_daily} legitimately exposes {@code ce.file_name} yet is client-scoped, a client
 * reconciling its OWN emitted files). The tenant boundary is the predicate CLASS:
 * <ul>
 *   <li>INTERNAL views carry {@code current_user IN ('rpt_internal', 'root')} - zero rows to any
 *       client role (the filenames/job-names trace surface): v_file_index, v_flow_trace,
 *       v_correlation_index, v_emission_visibility, v_psr_watermark_lag, v_stuck.</li>
 *   <li>CLIENT-SCOPED views carry {@code client = upper(current_user) OR current_user='rpt_internal'}
 *       - a client sees only its OWN rows (the v_tx family, v_recon_daily, v_arrival_status, ...).</li>
 * </ul>
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class ViewPredicateConformanceIT {

    /** Regression tripwire: none of these may silently lose the internal boundary and become client-visible. */
    private static final Set<String> KNOWN_INTERNAL = Set.of(
            "v_file_index", "v_flow_trace", "v_correlation_index",
            "v_emission_visibility", "v_psr_watermark_lag", "v_stuck");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest::businessDbUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    /** viewName -> its stored definition (owner-visible), for every view in the business rpt schema. */
    private Map<String, String> rptViewDefinitions() throws SQLException {
        Map<String, String> defs = new LinkedHashMap<>();
        try (Connection root = DriverManager.getConnection(
                RptJobTest.businessDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT table_name, view_definition FROM information_schema.views "
                             + "WHERE table_schema = 'rpt' ORDER BY table_name")) {
            while (rs.next()) {
                defs.put(rs.getString("table_name"), rs.getString("view_definition"));
            }
        }
        return defs;
    }

    /** Internal iff the stored definition carries the {@code IN ('rpt_internal', 'root')} boundary. */
    private static boolean isInternal(final String definition) {
        return definition.contains("'root'");
    }

    // ----- (A) conformance: every rpt view carries a session-identity predicate ----------------

    @Test
    void everyBusinessRptViewCarriesASessionIdentityPredicate() throws Exception {
        Map<String, String> defs = rptViewDefinitions();
        assertTrue(defs.size() >= 18,
                "expected the full business rpt view surface (18 views), saw " + defs.keySet());

        List<String> unscoped = new ArrayList<>();
        List<String> internal = new ArrayList<>();
        defs.forEach((view, def) -> {
            assertFalse(def == null || def.isBlank(),
                    "view_definition not owner-visible for rpt." + view + " (adjust the scan)");
            if (!def.toLowerCase().contains("current_user")) {
                unscoped.add(view);
            } else if (isInternal(def)) {
                internal.add(view);
            }
        });

        assertTrue(unscoped.isEmpty(),
                "rpt views missing a current_user session-identity predicate (cross-client leak): " + unscoped);
        assertTrue(internal.containsAll(KNOWN_INTERNAL),
                "an internal trace view silently lost its IN ('rpt_internal','root') boundary; "
                        + "internal set now " + internal + ", expected superset of " + KNOWN_INTERNAL);
    }

    // ----- (B) exhaustive boundary: every internal view zero-rows every client role, with data --

    @Test
    void everyInternalRptViewZeroRowsEveryClientRoleWhileDataIsPresent() throws Exception {
        seedCrossClientData();
        Map<String, String> defs = rptViewDefinitions();
        List<String> internalViews = defs.entrySet().stream()
                .filter(e -> isInternal(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        assertTrue(internalViews.containsAll(KNOWN_INTERNAL),
                "internal-view discovery incomplete: " + internalViews);

        for (String view : internalViews) {
            assertTrue(count("rpt_internal", "rpt." + view) > 0,
                    "rpt_internal must see seeded rows in rpt." + view + " (else the zero-row check is vacuous)");
            // Internal views are NOT client-scoped: even the data's OWNING client sees zero rows.
            for (String client : List.of("fnbcc01", "fnbcc02")) {
                assertEquals(0, count(client, "rpt." + view),
                        "client role " + client + " must see ZERO rows in internal view rpt." + view);
            }
        }
    }

    private int count(final String role, final String view) throws SQLException {
        try (Connection c = RptSecurityIT.forRole(role); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + view)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    /** Minimal FNBCC02 rows so every internal view is non-empty for rpt_internal before the boundary check. */
    private void seedCrossClientData() throws SQLException {
        final String arrival = "cccccccc-0000-0000-0000-000000000001";
        try (Connection root = DriverManager.getConnection(
                RptJobTest.businessDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
             Statement s = root.createStatement()) {
            s.execute("DELETE FROM public.rpt_run; DELETE FROM public.prg_watermark;"
                    + "DELETE FROM public.cir_response; DELETE FROM public.crw_emission_group;"
                    + "DELETE FROM public.tx_header");
            s.execute("""
                INSERT INTO public.tx_header (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at)
                VALUES ('cccccccc-0000-0000-0000-0000000000b1','%s','MSGO1','FNBCC02','20260701',1,'2026-07-01T08:00:00Z')"""
                    .formatted(arrival));
            s.execute("""
                INSERT INTO public.crw_emission_group (id, arrival_id, client, source_msg_id, run_date)
                VALUES ('cccccccc-0000-0000-0000-0000000000f1','%s','FNBCC02','MSGO1','2026-07-01')"""
                    .formatted(arrival));
            s.execute("""
                INSERT INTO public.cir_response (arrival_id, client, msg_id, route_id, outcome, file_name,
                                                 accepted_count, total_count, written_at, created_at)
                VALUES ('%s','FNBCC02','MSGO1','onhost-req','ACK','FNBCC02_MSGO1_RESP.txt',1,1,
                        NULL,'2026-07-01T08:15:00Z')""".formatted(arrival));
            s.execute("""
                INSERT INTO public.prg_watermark (client, e2e, last_status, updated_at)
                VALUES ('FNBCC02','E2EO1','CTV_PASS','2026-07-01T10:20:00Z')""");
            s.execute("""
                INSERT INTO public.rpt_run (job_name, outcome, created_at)
                VALUES ('local-rpt-o1','BUSINESS_ACCEPTED','2026-07-01T17:00:00Z')""");
        }
    }
}
