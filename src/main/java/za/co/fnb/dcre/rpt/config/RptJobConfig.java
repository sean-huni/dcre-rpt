package za.co.fnb.dcre.rpt.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;
import za.co.fnb.dcre.platform.batch.StaleExecutionSweeper;
import za.co.fnb.dcre.rpt.data.repo.RptRunRepo;

import javax.sql.DataSource;
import java.util.function.Function;

@Configuration
public class RptJobConfig {

    /**
     * rpt is a one-shot schema-owner job whose real work is the Liquibase runs, NOT an orchestrated
     * AGT stage, so it records its own business-verdict seam to stay queryable. The shared
     * {@link OutcomeSeamListener} (SCRUM-58) replaces the retired inline SeamListener record: it
     * gates on COMPLETED (technical death writes neither the row nor the file, R-33 arbiter clause),
     * resolves the seam name to the K8s JOB_NAME or the self-describing {@code local-rpt-<id>}
     * fallback, then runs the persistence hook BEFORE the outcome file write so {@code rpt_run}
     * commits write-ahead of the filesystem effect. The hook insert is idempotent on {@code job_name}
     * (ON CONFLICT no-op on a same-Job relaunch). Verdict stays {@code BUSINESS_ACCEPTED},
     * byte-exact with the retired seam.
     */
    @Bean
    public Job rptJob(JobRepository repo, PlatformTransactionManager tx, RptRunRepo rptRunRepo,
                      @Value("${dcre.exchange-root}") String exchangeRoot) {
        var step = new StepBuilder("schemaOwnerStep", repo)
                .tasklet((contribution, chunkContext) -> RepeatStatus.FINISHED, tx)
                .build();
        Function<JobExecution, String> verdict = execution -> "BUSINESS_ACCEPTED";
        var seamListener = new OutcomeSeamListener("rpt", exchangeRoot, verdict,
                outcome -> rptRunRepo.insert(outcome.jobName(), outcome.verdict()));
        return new JobBuilder("rptJob", repo)
                .listener(seamListener)
                .start(step)
                .build();
    }

    @Bean
    @Order(-10)
    public ApplicationRunner staleExecutionSweep(DataSource dataSource) {
        return args -> StaleExecutionSweeper.abandonStale(dataSource, "RPT_BATCH_", 60);
    }
}
