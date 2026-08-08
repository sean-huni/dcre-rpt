# dcre-rpt

Per-family reporting schema owner for DCRE: Liquibase-managed, session-identity-scoped SQL views, one run per family against that family's own database.

## The boundary, first

`rpt` lives in `shared/` and appears on no diagram sheet (R-49), because it is a cross-family helper rather than a DAG stage. "Cross-family" describes the CODE, never a query:

- **One run owns one family.** `dcre.rpt.family` selects both the changelog master and the database it is allowed to touch. `FamilyGuard` compares `current_database()` against the declared family's own database and refuses to start on a mismatch, so pointing the collections read model at `dcre_pay` fails closed instead of quietly reporting payments figures under a collections heading.
- **No view spans two databases, and none ever will.** With one database per family there is no lane discriminator to filter on, which is exactly why `tx_header.flow` is being deleted: every row in `dcre_col` IS a collection. A figure that genuinely spans families is composed from each family's own published read model, never by a query across databases ([Database per Service](https://microservices.io/patterns/data/database-per-service.html), [API Composition](https://microservices.io/patterns/data/api-composition.html)).
- **`rpt` ships no DDL for a table it does not own.** The only table it creates is `rpt_run`. Everything else it reads belongs to the family's stage services, and it runs after they have migrated.

Two gates enforce this in `check`, so neither claim rests on the prose above:

| Gate | What it fails on |
|---|---|
| `verifyViewPredicates` | a `createView` body without the session-identity predicate |
| `verifyFamilyTableBoundary` | a changelog reading a table no owner in that lane publishes, or creating a table `rpt` does not own |

Both refuse to pass on a zero-match scan, because a sweep that finds nothing is a broken sweep rather than a clean one.

## What it does

Owns the `rpt` reporting surface for one family. It is a one-shot Spring Boot 4.1 / Spring Batch 6 job whose real work happens in its two Liquibase runs: the family changelog creates the `rpt` schema, the reporting roles and the client-scoped views inside that family's database; a secondary run creates the orchestration views inside `agt_ops`. The Batch step is a no-op tasklet: schema ownership lives in the changelogs, and the JVM exit code carries the job verdict (`ExitCodeMain` from platform-batch).

### The collections read model (`dcre_col`, schema `rpt`): 18 views

- Core: `v_tx` (per-transaction status spine), `v_tx_daily`, `v_fails`, `v_reason_daily`, `v_debtor_daily`
- Extended: `v_funnel_daily`, `v_latency`, `v_recon_daily` (recon R-24), `v_cure`, `v_amount_buckets`
- File trace: `v_file_index`, `v_flow_trace`
- Prod support: `v_arrival_status`, `v_client_day`, `v_correlation_index`, `v_emission_visibility`, `v_psr_watermark_lag`, `v_stuck`

Base tables read, all owned by collections services: `tx_header`/`tx_entry` (CRR), `validation_log` (CTV), `crw_emission`/`_group`/`_member` (CRW), `isr_resp`/`sbsr_resp`/`pbsr_resp` (CIX/CSX/CPX), `cir_response` (CIR), `prg_report`/`prg_watermark` (CRG).

**Names in this repo follow R-49.** Step labels in `v_flow_trace` name the SERVICE that produced the row, so they read `CIX_REPLY`, `CSX_REPLY`, `CPX_REPLY` and `CRG_REPORTED`. The TABLE names do not change with them: CRG kept `prg_report`, `prg_watermark` and its seven `prg_*` views when the service was renamed, so a rename applied to those references would break the views. `PRG` now names the PAYMENTS report generator, in `dcre_pay`.

### The orchestration read model (`agt_ops`, schema `rpt`, internal-only): 7 views

`v_ops_stage_health`, `v_ops_sla`, `v_ops_file_index`, `v_ops_flow`, `v_ops_attempts`, `v_ops_stuck`, `v_ops_client_day`.

This is the one genuinely cross-family surface, and it costs no cross-database join: AGT's bounded context is "launch every family's stages and record what happened", so a view grouping by `launch_intent.stage` spans CRR, PRR and MRR inside one database that already holds the fact.

`[!CONVENTION-OVERRIDE]` Canon is that a service owns its own database and no other ([Database per Service](https://microservices.io/patterns/data/database-per-service.html)). `rpt` creates the `rpt` schema inside `agt_ops`, which is AGT's database. These views are AGT's read model and their correct home is AGT; they live here because `rpt` already carries the session-identity and grants discipline they need. Migrating them is an AGT change and is specified in the SCRUM-107 report.

### Payments and mandates read models

Not built, deliberately. Nothing in the register requires payments or mandates reporting yet, the payments emission tables differ in shape from CRW's, and a speculative copy of 18 views over tables whose columns are not settled would be a second definition of a contract nobody has asked for. When one is required it arrives as `db/changelog/db.changelog-payments-master.xml` over `dcre_pay`'s own tables, selected by `DCRE_RPT_FAMILY=payments`, and joins to nothing here.

### Fintegrate status classification

The reporting authority for the confirmed Fintegrate boundary:

- Terminal success: `ACSC`, `ACCC`.
- Terminal non-success: `RJCT`, `CANC`.
- Accepted non-terminal: `ACSP`, `ACTC`, `ACCP`, `ACFC`.
- Pending/interim: `RCVD`, `PDNG`, `PART`, `PATC`.
- Accepted warehoused (SLA-suppressed): `ACWP` future-dated, `ACWC` auto-bumped per the RMB DebiCheck profile (SCRUM-68; the SLA suppression itself is enforced in CRG's `prg_sla_pending`, rpt treats both as plain non-terminal).

Warehoused and unknown Fintegrate codes remain non-terminal and are never inferred as success or failure by reporting. In particular, a response code merely beginning with `FAIL` is still unknown; early validation failures come only from `ctv_outcome`. Terminal non-success is recognised at the deepest available Fintegrate response leg, including an ISR-level `RJCT`.

`v_psr_watermark_lag.non_terminal_rows` is deliberately conservative. The watermark stores only `(client, e2e, last_status)`, so only the four explicit Fintegrate terminal codes close a row; an ambiguous CTV `FAIL_*` remains counted rather than being falsely attributed to a particular transaction. Precise attribution requires the upstream watermark to add `arrival_id + sequence` (or equivalent emission/stage provenance).

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

- **SOLID, single responsibility**: the service does exactly one thing (own one family's reporting schema). `OpsLiquibaseConfig` owns migration wiring, `FamilyGuard` owns the boundary check, `RptJobConfig` owns the job and the startup sweeper; each changeset carries one concern (roles, Batch metadata, one view family, grants).
- **Session-identity row scoping**: every client-facing view carries `client = upper(current_user) OR current_user = 'rpt_internal'`; the internal views require `current_user IN ('rpt_internal', 'root')`.
- **Roles and grants wall**: client roles `fnbcc01`, `fnbcc02`, `fnbrf01` plus `rpt_internal` get USAGE + SELECT on schema `rpt` only; client roles cannot read the `public` OLTP tables. All four default `default_transaction_use_follower_reads = 'on'`, so reporting reads are served ~4.8 s stale by design and do not contend with the OLTP pipeline.
- **12FactorApp Alignment - https://12factor.net/**: config strictly from the environment with committed working defaults (a clean clone runs with no `.env`), stateless one-shot process, CockroachDB as an attached backing resource, dev/prod parity (the test container now builds a real `dcre_col` and `agt_ops`, not the Testcontainers default database).
- **Version 1 migrations**: all databases are dropped and recreated, so there is one definition per object and no migration scaffolding. No `validCheckSum ANY`, no `MARK_RAN` guard for an object that cannot exist on an empty database, and no `IF NOT EXISTS` except where the reason survives a fresh database: `CREATE ROLE` (CockroachDB roles are cluster-scoped, so the second of the two changelog runs legitimately meets an existing role) and the Batch metadata `.sql` (A-81: a pod SIGKILLed mid-file leaves tables created and sequences missing with no changelog row, on a fresh database as readily as on an old one).
- **Boot 4 Liquibase note**: `LiquibaseAutoConfiguration` backs off entirely once any user-defined `SpringLiquibase` bean exists, so `OpsLiquibaseConfig` declares the primary family migration explicitly (wired from `LiquibaseProperties`) alongside the secondary `agt_ops` migration, which uses a deliberately non-pooling `SimpleDriverDataSource`. `FamilyGuard` runs INSIDE that factory method, so it precedes `afterPropertiesSet()` by construction rather than by bean-order luck.

## Prerequisites

- JDK 25 (Gradle toolchain; wrapper 9.5.1 committed)
- Docker (Testcontainers and image builds)
- Platform libs in mavenLocal: `za.co.fnb.dcre:platform-persistence:0.1.0` and `za.co.fnb.dcre:platform-batch:0.1.0`
- A CockroachDB with the family's database and `agt_ops` (the dcre-infra compose and kind bootstraps create them)

## Quickstart

Clean clone, no `.env`: the committed defaults target the dcre-infra compose CRDB on `localhost:26257` for the collections family.

```zsh
# platform libs once
(cd ../../platform/platform-persistence && ./gradlew publishToMavenLocal)
(cd ../../platform/platform-batch && ./gradlew publishToMavenLocal)

# backing DB (compose inner loop; init creates dcre_col + agt_ops)
(cd ../../../../../../infra/dcre-infra && docker compose up -d)

# apply both changelogs, run the no-op job, exit with the Batch exit code
./gradlew bootRun
```

Ordering matters: run rpt only after AGT and the family's stage services have applied their schemas at least once. CockroachDB validates view dependencies at CREATE time, so the `public.*` OLTP tables must pre-exist in both databases. `rpt` no longer pre-creates any of them, which is the point: it reads what the owners publish. A re-run needs a fresh identifying parameter (Spring Batch job-instance identity), e.g. `./gradlew bootRun --args="window=$(date +%Y%m%dT%H%M%S)"`.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DCRE_RPT_FAMILY` | `collections` | Which family's read model this run owns. Selects `db.changelog-<family>-master.xml` AND the database the run is allowed to touch. `payments` and `mandates` are valid tokens with no changelog yet |
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_col?sslmode=disable` | Primary datasource. Must resolve to the declared family's own database or `FamilyGuard` refuses to start |
| `DCRE_DB_USER` | `root` | DB user (also used for the `agt_ops` migration connection) |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_RPT_OPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Target of the secondary orchestration-views Liquibase run |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root; on COMPLETED the job stages a `BUSINESS_ACCEPTED` outcome file under `outcomes/` |
| `JOB_NAME` | `local-<executionId>` | Outcome-file identity when launched as a k8s Job |

Fixed by the changelogs (not env-tunable): Batch metadata under the `RPT_BATCH_` prefix (`spring.batch.jdbc.initialize-schema: never`, Liquibase owns the DDL, `EXIT_MESSAGE` widened to TEXT for CockroachDB) and Liquibase history in `rpt_databasechangelog` / `rpt_databasechangeloglock` in both databases.

The family-to-database mapping is domain fact and lives in `domain/Family`, not in config. A boundary that can be set wrong silently is not a boundary.

## Testing

```zsh
./gradlew test                       # full suite, Docker required
./gradlew check                      # test + both verification gates
./gradlew verifyViewPredicates verifyFamilyTableBoundary   # the gates alone (no Docker)
```

Tests run against Testcontainers CockroachDB `cockroachdb/cockroach:v26.2.3`. The shared container creates real `dcre_col` and `agt_ops` databases and the OLTP fixture tables before any Spring context boots, because the view changesets validate their `public.*` dependencies at CREATE time. The fixture creates `cir_response`, `prg_report.job_name` and `duplicate_delivery` because their OWNERS do on a real cluster and `rpt` no longer ships that DDL.

Suite: `RptJobTest` (job COMPLETED, 25 changesets in the collections history), `FamilyBoundaryIT` (the guard refuses another family's database with the specific message, the family map is total with no silent default, both read models exist and their views depend only on local tables), `RptCoreViewsIT` / `RptExtendedViewsIT` (golden results), `RptBatchCorrelationIT` (batch-scoped binding, fail-closed negatives), `RptEmissionCurrencyIT` (current-emission pick, re-emission no-double-count), `RptIsolationIT` (enumerates every `rpt` view and asserts zero foreign-client rows per client role), `RptSecurityIT` (grants wall, follower-read role defaults), `RptOpsViewsIT` (`agt_ops` views internal-only, 11 changesets), `SupportViewIT` / `FileTraceViewIT` / `ViewPredicateConformanceIT` (support views, file trace with the R-49 step labels and a negative arm for the retired ones, session-identity conformance).

Removed with the version-1 collapse: `RptStatusClassificationMigrationIT` and `src/test/resources/legacy/`. They asserted convergence from legacy, half-applied and mid-changeset-kill states of the 007 append-only corrections. Those states cannot exist on a database that is dropped and recreated, so the tests were asserting the behaviour of changesets that no longer exist.

## Local cluster deployment

rpt is not an AGT-launched pipeline stage (dcre-infra `switch-version.sh` stage list excludes it). It is a schema owner: run it one-shot per family whenever its changelogs change, after that family's OLTP schema exists.

Against the kind cluster `dcre-dev`, port-forward CRDB and override the URLs (`scripts/crdb-forward.sh` maps SQL to host port 26258):

```zsh
(cd ../../../../../../infra/dcre-infra && scripts/crdb-forward.sh)
DCRE_RPT_FAMILY=collections \
DCRE_DB_URL='jdbc:postgresql://localhost:26258/dcre_col?sslmode=disable' \
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
- Collections stages: https://github.com/sean-huni/dcre-crr, https://github.com/sean-huni/dcre-ctv, https://github.com/sean-huni/dcre-cde, https://github.com/sean-huni/dcre-crw, https://github.com/sean-huni/dcre-cir, https://github.com/sean-huni/dcre-cix, https://github.com/sean-huni/dcre-csx, https://github.com/sean-huni/dcre-cpx, https://github.com/sean-huni/dcre-crg
- Payments stages: https://github.com/sean-huni/dcre-prr, https://github.com/sean-huni/dcre-ptv, https://github.com/sean-huni/dcre-pai, https://github.com/sean-huni/dcre-prw, https://github.com/sean-huni/dcre-pir
- Shared: https://github.com/sean-huni/dcre-hcs
- Platform libs: https://github.com/sean-huni/dcre-platform-model, https://github.com/sean-huni/dcre-platform-files, https://github.com/sean-huni/dcre-platform-batch, https://github.com/sean-huni/dcre-platform-persistence, https://github.com/sean-huni/dcre-platform-copybook
- Infra and tooling: https://github.com/sean-huni/dcre-infra, https://github.com/sean-huni/dcre-fixture-toolkit, https://github.com/sean-huni/dcre-design-register

Retired repository names that appear in dated documents: `dcre-ixr`, `dcre-sxr`, `dcre-pxr` (now `dcre-cix`, `dcre-csx`, `dcre-cpx`), `dcre-ais` (now `dcre-pai`). `dcre-prg` named the COLLECTIONS generator before 2026-08-08 and names the PAYMENTS one after it; see the service name map in the design register.
