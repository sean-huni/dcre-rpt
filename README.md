# dcre-rpt

Reporting schema owner for DCRE Collections: Liquibase-managed, session-identity-scoped SQL views over the shared CockroachDB.

## What it does

Owns the `rpt` reporting surface of DCRE Collections (SCRUM-51). It is a one-shot Spring Boot 4.1 / Spring Batch 6 job whose real work happens in its two Liquibase runs: the primary changelog creates the `rpt` schema, the reporting roles and ten client-scoped views inside `dcre_collections`; a secondary Liquibase run creates two internal-only ops views (stage health, SLA) inside `agt_ops`. The Batch step itself is a no-op tasklet: schema ownership lives in the changelogs, and the JVM exit code carries the job verdict (`ExitCodeMain` from platform-batch).

Views owned by this module:

- Core (`dcre_collections`, schema `rpt`): `v_tx` (per-transaction status spine), `v_tx_daily`, `v_fails`, `v_reason_daily`, `v_debtor_daily`
- Extended: `v_funnel_daily`, `v_latency`, `v_recon_daily` (recon R-24), `v_cure`, `v_amount_buckets`
- Ops (`agt_ops`, schema `rpt`, internal-only): `v_ops_stage_health`, `v_ops_sla`

### Fintegrate status classification

Migration `2026/07/007-fintegrate-status-classification.xml` is the reporting authority for the
confirmed Fintegrate boundary:

- Terminal success: `ACSC`, `ACCC`.
- Terminal non-success: `RJCT`, `CANC`.
- Accepted non-terminal: `ACSP`, `ACTC`, `ACCP`, `ACFC`.
- Pending/interim: `RCVD`, `PDNG`, `PART`, `PATC`.
- Unsupported by Fintegrate: `ACWC`, `ACWP`.

Unsupported and unknown Fintegrate codes remain non-terminal and are never inferred as success or
failure by reporting. In particular, a response code merely beginning with `FAIL` is still unknown;
early validation failures come only from `ctv_outcome`. Terminal non-success is recognised at the
deepest available Fintegrate response leg, including an ISR-level `RJCT`.

`v_psr_watermark_lag.non_terminal_rows` is deliberately conservative. The legacy watermark stores
only `(client, e2e, last_status)`, so only the four explicit Fintegrate terminal codes close a row;
an ambiguous CTV `FAIL_*` remains counted rather than being falsely attributed to a particular
transaction. Precise attribution requires the upstream watermark to add `arrival_id + sequence`
(or equivalent emission/stage provenance).

## Why this service exists (LGTM does not replace it)

A frequent question: the kind cluster already runs Grafana (the LGTM bundle), so why a separate `rpt` service? Because they sit in different layers and do not overlap.

- **LGTM** is presentation plus ops telemetry, at runtime: Grafana renders dashboards and Prometheus stores operational metrics. Grafana connects directly to CockroachDB and runs `SELECT ... FROM rpt.v_tx_daily`. It ships zero DCRE business SQL.
- **`rpt`** is the data / schema-owner layer, at migration time: it runs once to create the `rpt.*` views plus the client roles and grants in CockroachDB, then exits. It is never in the query path and is not a running service.

Grafana is the eyes; `rpt` is the schema those eyes read. Neither replaces the other.

### "Why not give Grafana a full-access datasource and drop rpt?"

In Grafana the datasource's DB role IS the data boundary. Org walls isolate dashboards and users; they do NOT scope what a query can reach. So a full-access datasource and per-client data isolation are mutually exclusive: give a client org a full-access CockroachDB role and that client can run `SELECT * FROM tx_entry` and read every other client's data. That is broken access control (OWASP A01 - https://owasp.org/Top10/A01_2021-Broken_Access_Control/) and a multi-tenant leak. Least privilege applies to the credential, not the dashboard: the datasource role's grants define the blast radius.

The client-facing requirement (each client self-serves only its own stats) therefore forces a scoped role, which forces scoped views. Row-level security on the base tables was rejected: RLS does not filter through CockroachDB views, and putting it on the hot OLTP tables would tax the batch write path and still need per-client roles.

| Consumers | Full-access datasource? | Is rpt needed? |
|---|---|---|
| External client orgs (current spec: fnbcc01 / fnbcc02 / fnbrf01 see only their own rows) | Impossible, leaks every tenant | Yes: the scoped views + per-client roles ARE the isolation |
| Internal FNB teams only, fully trusted, read-only role | Workable | Views optional (correctness only); this module trimmable |

Even internal-only, two things stay separable:

1. **The views (semantic layer)** stay valuable even with full access, because the raw joins are easy to get wrong: status resolution across `pbsr_resp` / `sbsr_resp` / `isr_resp` / `validation_log`, latest-response-wins `DISTINCT ON`, re-drive dedup, and the early-CTV-versus-late-PBSR fail split. The views encode this once, so every dashboard computes the same, correct numbers.
2. **The module** is only the delivery vehicle for those views (Liquibase owner plus the golden-number, isolation and predicate-gate test harness). That is the trimmable part if tenant isolation were ever dropped.

Bottom line: as long as external client orgs self-serve their own data, `rpt` is necessary and a full-access datasource is not an option. If the scope were ever reduced to internal-FNB-only, a read-only full-access datasource plus hand-written SQL becomes viable and `rpt` could shrink to a few optional correctness views.

## Architecture and principles

- **SOLID, single responsibility**: the service does exactly one thing (own the reporting schema). `OpsLiquibaseConfig` owns migration wiring only, `RptJobConfig` owns the job and the startup sweeper only; each changeset carries one concern (roles, Batch metadata, one view family, grants).
- **Session-identity row scoping**: every client-facing view carries `client = upper(current_user) OR current_user = 'rpt_internal'`; the ops views require `current_user IN ('rpt_internal', 'root')`. The `verifyViewPredicates` Gradle task (wired into `check`) fails the build if any `<createView>` lacks the predicate.
- **Roles and grants wall**: client roles `fnbcc01`, `fnbcc02`, `fnbrf01` plus `rpt_internal` get USAGE + SELECT on schema `rpt` only; client roles cannot read the `public` OLTP tables. All four roles default `default_transaction_use_follower_reads = 'on'`, so reporting reads are served ~4.8 s stale by design and do not contend with the OLTP pipeline.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment with committed working defaults (a clean clone runs with no `.env`), stateless one-shot process, CockroachDB as an attached backing resource, dev/prod parity (same CockroachDB engine in tests, compose and kind).
- **Idempotent restart semantics**: per-service Liquibase history (`rpt_databasechangelog` + lock) in BOTH databases (the only DCRE module with history in both); `replaceIfExists` views and `IF NOT EXISTS` role/grant DDL converge on re-run; `StaleExecutionSweeper` abandons stale `RPT_BATCH_` executions at startup so a killed pod never blocks a same-identity relaunch.
- **Boot 4 Liquibase note**: `LiquibaseAutoConfiguration` backs off entirely once any user-defined `SpringLiquibase` bean exists, so `OpsLiquibaseConfig` declares the primary `dcre_collections` migration explicitly (wired from `LiquibaseProperties`) alongside the secondary `agt_ops` migration, which uses a deliberately non-pooling `SimpleDriverDataSource`.

## Prerequisites

- JDK 25 (Gradle toolchain; wrapper 9.5.1 committed)
- Docker (Testcontainers and image builds)
- Platform libs in mavenLocal: `za.co.fnb.dcre:platform-persistence:0.1.0` and `za.co.fnb.dcre:platform-batch:0.1.0`
- A CockroachDB with the `dcre_collections` and `agt_ops` databases (the dcre-infra compose and kind bootstraps create both)

## Quickstart

Clean clone, no `.env`: the committed defaults target the dcre-infra compose CRDB on `localhost:26257`.

```zsh
# platform libs once
(cd ../platform-persistence && ./gradlew publishToMavenLocal)
(cd ../platform-batch && ./gradlew publishToMavenLocal)

# backing DB (compose inner loop; init creates dcre_collections + agt_ops)
(cd ../../../../../infra/dcre-infra && docker compose up -d)

# apply both changelogs, run the no-op job, exit with the Batch exit code
./gradlew bootRun
```

Ordering matters: run rpt only after AGT and the stage services have applied their schemas at least once. CockroachDB validates view dependencies at CREATE time, so the `public.*` OLTP tables must pre-exist in both databases. A re-run of the job needs a fresh identifying parameter (Spring Batch job-instance identity), e.g. `./gradlew bootRun --args="window=$(date +%Y%m%dT%H%M%S)"`.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_collections?sslmode=disable` | Primary datasource: OLTP database where the `rpt` schema, roles and client views are created |
| `DCRE_DB_USER` | `root` | DB user (also used for the `agt_ops` migration connection) |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_RPT_OPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Target of the secondary ops-views Liquibase run |
| `DCRE_EXCHANGE_ROOT` | `../../../../../infra/dcre-infra/exchange` | Exchange root; on COMPLETED the job stages a `BUSINESS_ACCEPTED` outcome file under `outcomes/` |
| `JOB_NAME` | `local-<executionId>` | Outcome-file identity when launched as a k8s Job |

Fixed by the changelogs (not env-tunable): Batch metadata under the `RPT_BATCH_` prefix (`spring.batch.jdbc.initialize-schema: never`, Liquibase owns the DDL, `EXIT_MESSAGE` widened to TEXT for CockroachDB) and Liquibase history in `rpt_databasechangelog` / `rpt_databasechangeloglock` in both databases.

## Testing

```zsh
./gradlew test                   # full suite, Docker required
./gradlew check                  # test + verifyViewPredicates
./gradlew verifyViewPredicates   # session-identity gate alone (no Docker)
```

Tests run against Testcontainers CockroachDB `cockroachdb/cockroach:v26.2.3`. The shared container pre-creates `agt_ops` and the OLTP fixture tables before any Spring context boots, because the view changesets validate their `public.*` dependencies at CREATE time. Suite: `RptJobTest` (job COMPLETED, 38 changesets in the rpt history), `RptCoreViewsIT` / `RptExtendedViewsIT` (golden results), `RptBatchCorrelationIT` (batch-scoped binding, fail-closed negatives), `RptEmissionCurrencyIT` (current-emission pick, re-emission no-double-count), `RptStatusClassificationMigrationIT` (legacy/fresh/half-applied/mid-changeset-kill convergence + rollback), `RptIsolationIT` (enumerates every `rpt` view and asserts zero foreign-client rows per client role), `RptSecurityIT` (grants wall, follower-read role defaults), `RptOpsViewsIT` (`agt_ops` views internal-only), `SupportViewIT` / `FileTraceViewIT` / `ViewPredicateConformanceIT` (support views, file trace, session-identity conformance).

## Local cluster deployment

rpt is not an AGT-launched pipeline stage (dcre-infra `switch-version.sh` stage list excludes it). It is a schema owner: run it one-shot whenever its changelogs change, after the OLTP schema exists.

Against the kind cluster `dcre-dev`, port-forward CRDB and override the two URLs (`scripts/crdb-forward.sh` maps SQL to host port 26258):

```zsh
(cd ../../../../../infra/dcre-infra && scripts/crdb-forward.sh)
DCRE_DB_URL='jdbc:postgresql://localhost:26258/dcre_collections?sslmode=disable' \
DCRE_RPT_OPS_DB_URL='jdbc:postgresql://localhost:26258/agt_ops?sslmode=disable' \
./gradlew bootRun
```

Container image (fleet parity; no k8s manifest for rpt is committed in dcre-infra):

```zsh
./gradlew bootJar
docker build -t dcre-rpt:2.0 .
kind load docker-image --name dcre-dev dcre-rpt:2.0
```

dcre-infra's `env-reset.sh` pre-seeds the rpt Liquibase history+lock tables in both databases (first-run bootstrap-race guard) and refuses to scale AGT up until they exist.

## Related repositories

- Orchestrator: https://github.com/sean-huni/dcre-agt
- Stage services: https://github.com/sean-huni/dcre-crr, https://github.com/sean-huni/dcre-ctv, https://github.com/sean-huni/dcre-cde, https://github.com/sean-huni/dcre-cir, https://github.com/sean-huni/dcre-crw, https://github.com/sean-huni/dcre-ixr, https://github.com/sean-huni/dcre-sxr, https://github.com/sean-huni/dcre-pxr, https://github.com/sean-huni/dcre-prg, https://github.com/sean-huni/dcre-ais, https://github.com/sean-huni/dcre-hcs
- Platform libs: https://github.com/sean-huni/dcre-platform-model, https://github.com/sean-huni/dcre-platform-files, https://github.com/sean-huni/dcre-platform-batch, https://github.com/sean-huni/dcre-platform-persistence
- Infra and tooling: https://github.com/sean-huni/dcre-infra, https://github.com/sean-huni/dcre-fixture-toolkit, https://github.com/sean-huni/dcre-design-register
