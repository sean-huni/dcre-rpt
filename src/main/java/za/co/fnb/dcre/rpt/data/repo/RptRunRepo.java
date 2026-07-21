package za.co.fnb.dcre.rpt.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.rpt.data.model.RptRunEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * SCRUM-58 rpt_run seam-name ledger. The insert is idempotent on the full
 * business identity {@code job_name} (uq_rpt_run_job): a K8s Job relaunch after
 * a killed pod (same JOB_NAME) or a duplicate {@code afterJob} is a silent
 * ON CONFLICT no-op, never a duplicate row. {@code id}/{@code created_at} are
 * DB-assigned ({@code gen_random_uuid()}/{@code now()}).
 */
public interface RptRunRepo extends Repository<RptRunEntity, UUID> {

    /** Seam-name capture: committed write-ahead of the outcome file, idempotent on job_name. */
    @Modifying
    @Query("""
            INSERT INTO rpt_run (job_name, outcome)
            VALUES (:jobName, :outcome)
            ON CONFLICT (job_name) DO NOTHING""")
    void insert(@Param("jobName") String jobName, @Param("outcome") String outcome);

    @Query("SELECT * FROM rpt_run WHERE job_name = :jobName")
    Optional<RptRunEntity> findByJobName(@Param("jobName") String jobName);

    @Query("SELECT count(*) FROM rpt_run WHERE job_name = :jobName")
    long countByJobName(@Param("jobName") String jobName);
}
