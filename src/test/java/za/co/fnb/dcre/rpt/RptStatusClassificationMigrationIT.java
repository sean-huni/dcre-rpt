package za.co.fnb.dcre.rpt;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the recorded pre-007 state can upgrade, re-enter and roll back safely. */
class RptStatusClassificationMigrationIT {

    private static final String UPGRADE_DATABASE = "rpt_status_upgrade";
    private static final String FRESH_DATABASE = "rpt_status_fresh";
    private static final String HALF_MIGRATED_DATABASE = "rpt_status_half";
    private static final String KILLED_DATABASE = "rpt_status_killed";
    private static final String LEGACY_MASTER = "classpath:legacy/db.changelog-pre-007-master.xml";
    private static final String CURRENT_MASTER = "classpath:db/changelog/db.changelog-master.xml";
    private static final List<String> REPLACED_VIEWS = List.of(
            "v_tx", "v_tx_daily", "v_fails", "v_reason_daily", "v_debtor_daily",
            "v_funnel_daily", "v_latency", "v_recon_daily", "v_cure",
            "v_amount_buckets", "v_psr_watermark_lag");

    @Test
    void legacyStateUpgradesIdempotentlyAndRollbackRestoresItsViewSemantics() throws Exception {
        final JdbcTemplate jdbc = database(UPGRADE_DATABASE);
        migrate(jdbc, LEGACY_MASTER);
        FixtureSeeder.seed(jdbc);

        assertEquals(28, historyCount(jdbc));
        assertDailyClassification(1, 0);
        final Map<String, String> legacyDefinitions = viewDefinitions(jdbc);

        migrate(jdbc, CURRENT_MASTER);
        assertEquals(38, historyCount(jdbc),
                "ten new 007 rows; the 006 watermark-lag correction re-runs IN PLACE (runOnChange)");
        assertDailyClassification(2, 1);
        assertClientCanSelectEveryAffectedView(UPGRADE_DATABASE);
        final Map<String, String> upgradedDefinitions = viewDefinitions(jdbc);

        migrate(jdbc, CURRENT_MASTER);
        assertEquals(38, historyCount(jdbc), "a current-master rerun is a no-op");

        rollbackCurrentStatusMigration(jdbc);
        assertEquals(28, historyCount(jdbc));
        assertDailyClassification(1, 0);
        assertClientCanSelectEveryAffectedView(UPGRADE_DATABASE);
        // v_psr_watermark_lag is owned by the 006 runOnChange changeset, not by 007: rolling back
        // the ten 007 changesets leaves the folded-in terminality correction applied (runOnChange
        // ownership is roll-forward; reverting it means editing the 006 text back).
        final Map<String, String> restored = new LinkedHashMap<>(viewDefinitions(jdbc));
        assertEquals(upgradedDefinitions.get("v_psr_watermark_lag"), restored.get("v_psr_watermark_lag"),
                "the 006-owned watermark-lag correction survives the 007 rollback");
        restored.remove("v_psr_watermark_lag");
        final Map<String, String> expected = new LinkedHashMap<>(legacyDefinitions);
        expected.remove("v_psr_watermark_lag");
        assertEquals(expected, restored,
                "every 007 replacement rollback restores the exact pre-007 view definition");
    }

    /**
     * CRDB commits DDL per statement: a kill after 007's DROP VIEW ... CASCADE but before the
     * changeset records leaves the view stack missing with the changeset unrecorded. The restart
     * must converge, which requires the DROP to be IF EXISTS.
     */
    @Test
    void midChangesetKillAfterCascadeDropReconvergesOnRerun() throws Exception {
        final JdbcTemplate jdbc = database(KILLED_DATABASE);
        migrate(jdbc, LEGACY_MASTER);
        assertEquals(28, historyCount(jdbc));

        // Simulate the kill state: the CASCADE drop committed, nothing else did.
        jdbc.execute("DROP VIEW rpt.v_tx CASCADE");

        migrate(jdbc, CURRENT_MASTER);
        assertEquals(38, historyCount(jdbc));
        assertViewsExist(jdbc);
        assertClientCanSelectEveryAffectedView(KILLED_DATABASE);
    }

    @Test
    void freshCurrentMasterIsIdempotent() throws Exception {
        final JdbcTemplate jdbc = database(FRESH_DATABASE);

        migrate(jdbc, CURRENT_MASTER);
        assertEquals(38, historyCount(jdbc));
        final Map<String, String> firstDefinitions = viewDefinitions(jdbc);
        assertClientCanSelectEveryAffectedView(FRESH_DATABASE);

        migrate(jdbc, CURRENT_MASTER);
        assertEquals(38, historyCount(jdbc), "a second fresh-current run is a no-op");
        assertEquals(firstDefinitions, viewDefinitions(jdbc));
    }

    @Test
    void reachablePartialStatusMigrationResumesToCurrent() throws Exception {
        final JdbcTemplate jdbc = database(HALF_MIGRATED_DATABASE);
        migrate(jdbc, LEGACY_MASTER);
        assertEquals(28, historyCount(jdbc));

        updateCurrentStatusMigration(jdbc, 5);
        assertEquals(32, historyCount(jdbc),
                "five pending changes applied: the in-place 006 watermark-lag re-run (RERAN, no new "
                        + "history row) plus four of ten 007 changesets");
        assertViewsExist(jdbc);
        assertClientCanSelectEveryAffectedView(HALF_MIGRATED_DATABASE);

        migrate(jdbc, CURRENT_MASTER);
        assertEquals(38, historyCount(jdbc));
        assertViewsExist(jdbc);
        assertClientCanSelectEveryAffectedView(HALF_MIGRATED_DATABASE);
    }

    private JdbcTemplate database(final String name) {
        new JdbcTemplate(dataSource(RptJobTest.CRDB.getDatabaseName()))
                .execute("CREATE DATABASE IF NOT EXISTS " + name);
        final JdbcTemplate jdbc = new JdbcTemplate(dataSource(name));
        FixtureSeeder.createOltpTables(jdbc);
        return jdbc;
    }

    private DataSource dataSource(final String database) {
        return new DriverManagerDataSource(
                RoleConnections.forDatabase(RptJobTest.CRDB, database),
                RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
    }

    private void migrate(final JdbcTemplate jdbc, final String master) throws LiquibaseException {
        final SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(jdbc.getDataSource());
        liquibase.setChangeLog(master);
        liquibase.setDatabaseChangeLogTable("rpt_databasechangelog");
        liquibase.setDatabaseChangeLogLockTable("rpt_databasechangeloglock");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    private void rollbackCurrentStatusMigration(final JdbcTemplate jdbc) throws Exception {
        runDirectLiquibase(jdbc, liquibase ->
                liquibase.rollback(10, new Contexts(), new LabelExpression()));
    }

    private void updateCurrentStatusMigration(final JdbcTemplate jdbc, final int changesets)
            throws Exception {
        runDirectLiquibase(jdbc, liquibase ->
                liquibase.update(changesets, new Contexts(), new LabelExpression()));
    }

    private void runDirectLiquibase(final JdbcTemplate jdbc, final LiquibaseAction action)
            throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            final var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDatabaseChangeLogTableName("rpt_databasechangelog");
            database.setDatabaseChangeLogLockTableName("rpt_databasechangeloglock");
            try (var liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                    new ClassLoaderResourceAccessor(), database)) {
                action.run(liquibase);
            }
        }
    }

    @FunctionalInterface
    private interface LiquibaseAction {
        void run(Liquibase liquibase) throws LiquibaseException;
    }

    private int historyCount(final JdbcTemplate jdbc) {
        final Integer count = jdbc.queryForObject("SELECT count(*) FROM rpt_databasechangelog", Integer.class);
        return count == null ? -1 : count;
    }

    private Map<String, String> viewDefinitions(final JdbcTemplate jdbc) {
        final Map<String, String> definitions = new LinkedHashMap<>();
        for (String view : REPLACED_VIEWS) {
            definitions.put(view, jdbc.queryForObject("""
                    SELECT view_definition FROM information_schema.views
                    WHERE table_schema = 'rpt' AND table_name = ?""", String.class, view));
        }
        return definitions;
    }

    private void assertViewsExist(final JdbcTemplate jdbc) {
        assertEquals(REPLACED_VIEWS.size(), jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.views
                WHERE table_schema = 'rpt' AND table_name IN (
                  'v_tx', 'v_tx_daily', 'v_fails', 'v_reason_daily', 'v_debtor_daily',
                  'v_funnel_daily', 'v_latency', 'v_recon_daily', 'v_cure',
                  'v_amount_buckets', 'v_psr_watermark_lag')
                """, Integer.class), "no public view may disappear during a resumable migration");
    }

    private void assertClientCanSelectEveryAffectedView(final String database) throws Exception {
        try (Connection connection = RptSecurityIT.forRole(database, "fnbcc01");
             Statement statement = connection.createStatement()) {
            for (String view : REPLACED_VIEWS) {
                try (ResultSet ignored = statement.executeQuery("SELECT count(*) FROM rpt." + view)) {
                    assertTrue(ignored.next(), "fnbcc01 retains SELECT on rpt." + view);
                }
            }
        }
    }

    private void assertDailyClassification(final int settled, final int rejected) throws Exception {
        try (Connection connection = RptSecurityIT.forRole(UPGRADE_DATABASE, "fnbcc01");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT settled_count, rejected_late_count FROM rpt.v_tx_daily
                     WHERE process_date = '2026-07-01'""")) {
            assertTrue(result.next());
            assertEquals(settled, result.getInt("settled_count"));
            assertEquals(rejected, result.getInt("rejected_late_count"));
        }
    }
}
