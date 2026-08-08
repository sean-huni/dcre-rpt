package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.fnb.dcre.rpt.domain.Family;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptJobTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        // Every test class references CRDB from its @DynamicPropertySource, so this static block
        // runs before ANY Spring context boots. Three things must be true before the first one:
        //
        //  1. the BUSINESS database is named dcre_col, not the Testcontainers default. FamilyGuard
        //     compares current_database() against the declared family's own database, so a suite
        //     running against the default name would either fail every context or, worse, force
        //     the guard to be disabled in tests and prove nothing;
        //  2. the OLTP tables pre-exist, because CockroachDB validates view dependencies at CREATE
        //     time exactly as it does on the real cluster;
        //  3. the same holds for agt_ops, which every context migrates through opsLiquibase.
        try (var c = DriverManager.getConnection(CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword());
             var s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS dcre_col");
            s.execute("CREATE DATABASE IF NOT EXISTS agt_ops");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        FixtureSeeder.createOltpTables(new JdbcTemplate(
                new DriverManagerDataSource(businessDbUrl(), CRDB.getUsername(), CRDB.getPassword())));
        FixtureSeeder.createOpsTables(new JdbcTemplate(
                new DriverManagerDataSource(opsDbUrl(), CRDB.getUsername(), CRDB.getPassword())));
    }

    /** Container URL rewritten to dcre_col; the collections family's own database, per Family. */
    static String businessDbUrl() {
        return RoleConnections.forDatabase(CRDB, Family.COLLECTIONS.database());
    }

    /** Container URL rewritten to the agt_ops database; target of dcre.rpt.ops-db-url in EVERY IT. */
    static String opsDbUrl() {
        return RoleConnections.forDatabase(CRDB, "agt_ops");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest::businessDbUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Autowired Job rptJob;
    @Autowired JobOperator jobOperator;
    @Autowired JdbcTemplate jdbc;

    @Test
    void schemaOwnerJobCompletesAndLiquibaseHistoryExists() throws Exception {
        JobExecution run = jobOperator.start(rptJob, new JobParametersBuilder()
                .addString("window", "t1", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        Integer changesets = jdbc.queryForObject(
                "SELECT count(*) FROM rpt_databasechangelog", Integer.class);
        assertEquals(25, changesets,
                "the COLLECTIONS read model, version 1: 1 roles + 1 batch-metadata + 6 core-view "
                        + "+ 6 extended-view + 1 rpt-run + 3 file-trace (2 views + grant, no "
                        + "cross-service pre-creates) + 7 support-view (6 views + grant), applied "
                        + "via rpt-prefixed history. It was 38 while the 007 append-only "
                        + "corrections and three foreign-table pre-creates still existed.");
    }
}
