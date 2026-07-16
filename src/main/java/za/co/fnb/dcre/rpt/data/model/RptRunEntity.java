package za.co.fnb.dcre.rpt.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * SCRUM-58 seam-name registry: one row per rpt job run, keyed by the seam
 * {@code job_name} (K8s Job name, else {@code local-rpt-<executionId>}). Written
 * write-ahead of the outcome seam file by {@code RptJobConfig}'s
 * {@code OutcomeSeamListener} persistence hook. Read-only aggregate: {@code id}
 * ({@code gen_random_uuid()}) and {@code created_at} ({@code now()}) are
 * DB-assigned on the native ON CONFLICT insert, so there is no client-side
 * {@code Persistable} write path here.
 */
@Table("rpt_run")
public class RptRunEntity {

    @Id
    private UUID id;
    private String jobName;
    private String outcome;
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public String getJobName() {
        return jobName;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
