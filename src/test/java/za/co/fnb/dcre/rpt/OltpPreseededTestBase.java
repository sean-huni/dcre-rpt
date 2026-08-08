package za.co.fnb.dcre.rpt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Guarantees the OLTP tables exist in the shared test container BEFORE the Spring context
 * (and therefore Liquibase) runs: CockroachDB validates view dependencies at CREATE time,
 * so the 003 view changesets require {@code public.*} tables to pre-exist, exactly as they
 * do on the real cluster. Every IT that consumes the rpt views extends this class.
 */
public abstract class OltpPreseededTestBase {
    static {
        ensureOltpTables();
    }

    /**
     * Idempotent: starts the shared container if needed and applies the IF NOT EXISTS OLTP DDL.
     * Uses {@link DriverManagerDataSource} (URL-based driver resolution) because the postgresql
     * driver is a runtimeOnly dependency and not on the test compile classpath.
     */
    static void ensureOltpTables() {
        if (!RptJobTest.CRDB.isRunning()) {
            RptJobTest.CRDB.start();
        }
        var ds = new DriverManagerDataSource(
                RptJobTest.businessDbUrl(), RptJobTest.CRDB.getUsername(), RptJobTest.CRDB.getPassword());
        FixtureSeeder.createOltpTables(new JdbcTemplate(ds));
    }
}
