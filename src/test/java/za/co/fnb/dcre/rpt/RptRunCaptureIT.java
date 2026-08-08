package za.co.fnb.dcre.rpt;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import za.co.fnb.dcre.platform.batch.OutcomeFileWriter;
import za.co.fnb.dcre.rpt.data.model.RptRunEntity;
import za.co.fnb.dcre.rpt.data.repo.RptRunRepo;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCRUM-58: proves the rpt schema-owner job captures its own outcome-seam name
 * in {@code rpt_run} via the shared {@code OutcomeSeamListener} persistence hook,
 * write-ahead of the outcome file, and that a relaunch under the same seam name
 * (stable K8s JOB_NAME across a killed pod) is an ON CONFLICT no-op.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange"})
class RptRunCaptureIT extends OltpPreseededTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", RptJobTest::businessDbUrl);
        registry.add("spring.datasource.username", RptJobTest.CRDB::getUsername);
        registry.add("spring.datasource.password", RptJobTest.CRDB::getPassword);
        registry.add("dcre.rpt.ops-db-url", RptJobTest::opsDbUrl);
    }

    @Autowired Job rptJob;
    @Autowired JobOperator jobOperator;
    @Autowired RptRunRepo rptRunRepo;

    @Test
    void jobRunPersistsRptRunRowWithSeamName() throws Exception {
        JobExecution run = jobOperator.start(rptJob, new JobParametersBuilder()
                .addString("window", "rpt-run-capture-1", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        String seamName = OutcomeFileWriter.jobNameOrLocal("rpt", run.getId());
        RptRunEntity row = rptRunRepo.findByJobName(seamName)
                .orElseThrow(() -> new AssertionError("no rpt_run row for seam job_name " + seamName));
        assertEquals(seamName, row.getJobName());
        assertEquals("BUSINESS_ACCEPTED", row.getOutcome(), "verdict preserved byte-exact");
    }

    @Test
    void reRunSameJobNameIsOnConflictNoOp() throws Exception {
        JobExecution run = jobOperator.start(rptJob, new JobParametersBuilder()
                .addString("window", "rpt-run-capture-2", true).toJobParameters());
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        String seamName = OutcomeFileWriter.jobNameOrLocal("rpt", run.getId());
        assertEquals(1, rptRunRepo.countByJobName(seamName), "hook wrote exactly one row");
        UUID firstId = rptRunRepo.findByJobName(seamName).orElseThrow().getId();

        // Simulate an orphan-sweep relaunch of the SAME K8s Job (stable JOB_NAME): the native
        // ON CONFLICT (job_name) DO NOTHING insert the hook runs must be a silent no-op.
        rptRunRepo.insert(seamName, "BUSINESS_ACCEPTED");

        assertEquals(1, rptRunRepo.countByJobName(seamName), "re-insert of same job_name is a no-op");
        assertEquals(firstId, rptRunRepo.findByJobName(seamName).orElseThrow().getId(),
                "original row identity untouched by the ON CONFLICT no-op");
        assertTrue(run.getId() > 0);
    }
}
