package za.co.fnb.dcre.rpt.config;

import org.springframework.batch.core.job.Job;
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
import za.co.fnb.dcre.platform.batch.OutcomeFileWriter;
import za.co.fnb.dcre.platform.batch.StaleExecutionSweeper;

import javax.sql.DataSource;
import java.nio.file.Path;

@Configuration
public class RptJobConfig {

    @Bean
    public Job rptJob(JobRepository repo, PlatformTransactionManager tx,
                      @Value("${dcre.exchange-root}") String exchangeRoot) {
        var step = new StepBuilder("schemaOwnerStep", repo)
                .tasklet((contribution, chunkContext) -> RepeatStatus.FINISHED, tx)
                .build();
        return new JobBuilder("rptJob", repo)
                .listener(new SeamListener(exchangeRoot))
                .start(step)
                .build();
    }

    @Bean
    @Order(-10)
    public ApplicationRunner staleExecutionSweep(DataSource dataSource) {
        return args -> StaleExecutionSweeper.abandonStale(dataSource, "RPT_BATCH_", 60);
    }

    record SeamListener(String exchangeRoot) implements org.springframework.batch.core.listener.JobExecutionListener {
        @Override
        public void afterJob(org.springframework.batch.core.job.JobExecution execution) {
            if (execution.getStatus() == org.springframework.batch.core.BatchStatus.COMPLETED) {
                String jobName = System.getenv().getOrDefault("JOB_NAME", "local-" + execution.getId());
                OutcomeFileWriter.write(Path.of(exchangeRoot), jobName, "BUSINESS_ACCEPTED");
            }
        }
    }
}
