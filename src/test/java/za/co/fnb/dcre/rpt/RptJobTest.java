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

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptJobTest {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        // Every test class references CRDB from its @DynamicPropertySource, so this static block
        // runs before ANY Spring context boots. The 003 view changesets validate their public.*
        // dependencies at CREATE time, so the OLTP tables must pre-exist even when a single test
        // class (this one included) runs in isolation on a fresh container.
        FixtureSeeder.createOltpTables(new JdbcTemplate(
                new DriverManagerDataSource(CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword())));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", CRDB::getJdbcUrl); // Task 6 repoints to agt_ops
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
        assertEquals(14, changesets,
                "roles + batch-metadata + six core-view + six extended-view changesets applied via rpt-prefixed history");
    }
}
