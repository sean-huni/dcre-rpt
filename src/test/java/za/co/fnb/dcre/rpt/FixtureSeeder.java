package za.co.fnb.dcre.rpt;

import org.springframework.jdbc.core.JdbcTemplate;

public final class FixtureSeeder {

    private FixtureSeeder() {}

    /** Live-verified minimal OLTP shapes (SHOW CREATE, kind crdb-0, 2026-07-15). */
    public static void createOltpTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.tx_header (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              arrival_id UUID NOT NULL UNIQUE,
              msg_id VARCHAR(35) NOT NULL,
              client_token VARCHAR(16),
              business_date VARCHAR(8) NOT NULL,
              tx_count INT8 NOT NULL,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.tx_entry (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              arrival_id UUID NOT NULL,
              sequence INT8 NOT NULL,
              e2e VARCHAR(35) NOT NULL,
              creditor_account VARCHAR(23) NOT NULL DEFAULT 'CRED-1',
              currency VARCHAR(3) NOT NULL DEFAULT 'ZAR',
              amount DECIMAL(18,2) NOT NULL,
              debtor_name VARCHAR(35),
              debtor_account VARCHAR(23),
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (arrival_id, sequence))""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.validation_log (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              arrival_id UUID NOT NULL,
              sequence INT8 NOT NULL,
              outcome VARCHAR(32) NOT NULL,
              detail VARCHAR(256),
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (arrival_id, sequence))""");
        // response_file is VARCHAR(512) here to mirror the ixr/sxr/pxr 128->512 widening
        // (SCRUM-58 Tasks 7-9): the file-trace width-conformance IT asserts >= 512 on every
        // externally-received name column the views surface. emission_id is the crw_emission FK
        // added by prg 003-reporting / {ixr,sxr,pxr} 003-emission-fk that the reply view arms join on.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.pbsr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(512) NOT NULL,
              orgnl_msg_id VARCHAR(35) NOT NULL DEFAULT 'M',
              e2e VARCHAR(35) NOT NULL,
              status VARCHAR(8) NOT NULL,
              reason VARCHAR(8),
              emission_id UUID,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (response_file, e2e))""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.sbsr_resp (LIKE public.pbsr_resp INCLUDING ALL)""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.isr_resp (LIKE public.pbsr_resp INCLUDING ALL)""");
        // crw_emission batch-grain shape (crw 003-split): group_id links to crw_emission_group,
        // visible_at drives the VISIBLE state derivation in the PAIN008 arm. file_name stays
        // VARCHAR(128) (owner reality: internally generated, never externally received).
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.crw_emission (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              arrival_id UUID NOT NULL,
              run_date DATE NOT NULL,
              file_name VARCHAR(128) NOT NULL,
              state VARCHAR(32) NOT NULL DEFAULT 'EMITTED',
              group_id UUID,
              visible_at TIMESTAMPTZ,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (arrival_id, run_date))""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.crw_emission_group (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              arrival_id UUID NOT NULL,
              client VARCHAR(16) NOT NULL,
              source_msg_id VARCHAR(35) NOT NULL,
              run_date DATE NOT NULL)""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.crw_emission_member (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              emission_id UUID NOT NULL,
              sequence INT8 NOT NULL,
              e2e VARCHAR(35) NOT NULL,
              amount DECIMAL(18,2) NOT NULL,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (emission_id, sequence))""");
        // prg_report base (prg 003-reporting) WITHOUT job_name: the file-trace changelog's
        // MARK_RAN addColumn pre-create (byte-matching prg 004) adds job_name at migration time,
        // exercising the bootstrap-order guard exactly as on a rpt-runs-first cluster.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.prg_report (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              client VARCHAR(16) NOT NULL,
              report_type VARCHAR(12) NOT NULL DEFAULT 'PSR',
              trigger_kind VARCHAR(16) NOT NULL,
              window_key VARCHAR(64) NOT NULL DEFAULT 'W',
              parent_source_msg_id VARCHAR(35),
              file_name VARCHAR(128) NOT NULL,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
        // prg_watermark (prg 001-prg.xml 003-prg-watermark-prg): per-client delta watermark keyed
        // (client, e2e). Pre-dates SCRUM-58, so the support views treat it as assumed-present owner
        // reality (like crw_emission / prg_report), NOT a MARK_RAN pre-create. Seeded here so the
        // v_psr_watermark_lag CREATE VIEW resolves its dependency on a fresh test container.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.prg_watermark (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              client VARCHAR(16) NOT NULL,
              e2e VARCHAR(35) NOT NULL,
              last_status VARCHAR(32) NOT NULL,
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (client, e2e))""");
    }

    /**
     * agt_ops operational shapes (Task 6 brief), applied to the agt_ops database. Extended for
     * SCRUM-58 file trace: file_arrival gains physical_filename (VARCHAR(512), owner width, the
     * external inbound name the ops index surfaces) + quarantine_reason; launch_intent gains
     * job_name (the outcome-seam name) + attempt; stage_outcome gains exit_code (agt 001 owner
     * column). duplicate_delivery is deliberately NOT created here: the file-trace ops changelog
     * MARK_RAN pre-creates it (byte-matching agt 006), so the pre-create actually runs.
     */
    public static void createOpsTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.file_arrival (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              route_id VARCHAR(64) NOT NULL DEFAULT 'onhost-req',
              client_token VARCHAR(64), status VARCHAR(32) NOT NULL DEFAULT 'ROUTED',
              physical_filename VARCHAR(512) NOT NULL DEFAULT 'seed.txt',
              quarantine_reason VARCHAR(128),
              arrived_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.launch_intent (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              arrival_id UUID, stage VARCHAR(16) NOT NULL,
              job_name VARCHAR(63),
              status VARCHAR(16) NOT NULL DEFAULT 'LAUNCHED',
              attempt INT8 NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.stage_outcome (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              intent_id UUID NOT NULL, outcome VARCHAR(32) NOT NULL, exit_code INT8,
              observed_at TIMESTAMPTZ NOT NULL DEFAULT now(), attempt INT8 NOT NULL DEFAULT 0)""");
    }

    public static void seed(JdbcTemplate jdbc) {
        jdbc.execute("DELETE FROM public.crw_emission_member; DELETE FROM public.crw_emission;"
                + "DELETE FROM public.pbsr_resp; DELETE FROM public.validation_log;"
                + "DELETE FROM public.tx_entry; DELETE FROM public.tx_header");
        // arrivals: a1 CC01 d1, a2 CC01 d2, a3 CC02 d1, a4 RF01 d1
        jdbc.execute("""
            INSERT INTO public.tx_header (id, arrival_id, msg_id, client_token, business_date, tx_count, created_at) VALUES
            ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000a1','MSG-CC1-D1','FNBCC01','20260701',4,'2026-07-01T08:00:00Z'),
            ('00000000-0000-0000-0000-0000000000b2','00000000-0000-0000-0000-0000000000a2','MSG-CC1-D2','FNBCC01','20260702',2,'2026-07-02T08:00:00Z'),
            ('00000000-0000-0000-0000-0000000000b3','00000000-0000-0000-0000-0000000000a3','MSG-CC2-D1','FNBCC02','20260701',2,'2026-07-01T08:05:00Z'),
            ('00000000-0000-0000-0000-0000000000b4','00000000-0000-0000-0000-0000000000a4','MSG-RF1-D1','FNBRF01','20260701',1,'2026-07-01T08:10:00Z')""");
        jdbc.execute("""
            INSERT INTO public.tx_entry (arrival_id, sequence, e2e, amount, debtor_account) VALUES
            ('00000000-0000-0000-0000-0000000000a1',1,'E2E-CC1-001',50.00,'D-CC1-A'),
            ('00000000-0000-0000-0000-0000000000a1',2,'E2E-CC1-002',100.00,'D-CC1-B'),
            ('00000000-0000-0000-0000-0000000000a1',3,'E2E-CC1-003',200.00,'D-CC1-C'),
            ('00000000-0000-0000-0000-0000000000a1',4,'E2E-CC1-004',300.00,'D-CC1-D'),
            ('00000000-0000-0000-0000-0000000000a2',1,'E2E-CC1-005',400.00,'D-CC1-D'),
            ('00000000-0000-0000-0000-0000000000a2',2,'E2E-CC1-006',100.00,'D-CC1-E'),
            ('00000000-0000-0000-0000-0000000000a3',1,'E2E-CC2-001',1000.00,'D-CC2-A'),
            ('00000000-0000-0000-0000-0000000000a3',2,'E2E-CC2-002',2000.00,'D-CC2-B'),
            ('00000000-0000-0000-0000-0000000000a4',1,'E2E-RF1-001',500.00,'D-RF1-A')""");
        jdbc.execute("""
            INSERT INTO public.validation_log (arrival_id, sequence, outcome) VALUES
            ('00000000-0000-0000-0000-0000000000a1',1,'FAIL_ACCOUNT_NOT_FOUND'),
            ('00000000-0000-0000-0000-0000000000a1',2,'PASS'),
            ('00000000-0000-0000-0000-0000000000a1',3,'PASS'),
            ('00000000-0000-0000-0000-0000000000a1',4,'PASS'),
            ('00000000-0000-0000-0000-0000000000a2',1,'PASS'),
            ('00000000-0000-0000-0000-0000000000a2',2,'PASS'),
            ('00000000-0000-0000-0000-0000000000a3',1,'PASS'),
            ('00000000-0000-0000-0000-0000000000a3',2,'PASS'),
            ('00000000-0000-0000-0000-0000000000a4',1,'PASS')""");
        jdbc.execute("""
            INSERT INTO public.pbsr_resp (response_file, e2e, status, reason, created_at) VALUES
            ('PBSR_D1','E2E-CC1-002','ACSC',NULL,'2026-07-01T15:00:00Z'),
            ('PBSR_D1','E2E-CC1-003','ACSC',NULL,'2026-07-01T15:00:00Z'),
            ('PBSR_D1','E2E-CC1-004','RJCT','AC04','2026-07-01T15:00:00Z'),
            ('PBSR_D2','E2E-CC1-005','ACSC',NULL,'2026-07-02T15:00:00Z'),
            ('PBSR_D2','E2E-CC1-006','RJCT','AM04','2026-07-02T15:00:00Z'),
            ('PBSR_D1','E2E-CC2-001','ACSC',NULL,'2026-07-01T15:05:00Z'),
            ('PBSR_D1','E2E-CC2-002','ACSC',NULL,'2026-07-01T15:05:00Z'),
            ('PBSR_D1','E2E-RF1-001','ACSC',NULL,'2026-07-01T15:10:00Z')""");
        jdbc.execute("""
            INSERT INTO public.crw_emission (id, arrival_id, run_date, file_name) VALUES
            ('00000000-0000-0000-0000-0000000000e1','00000000-0000-0000-0000-0000000000a1','2026-07-01','CC01_D1_PAIN008'),
            ('00000000-0000-0000-0000-0000000000e2','00000000-0000-0000-0000-0000000000a2','2026-07-02','CC01_D2_PAIN008'),
            ('00000000-0000-0000-0000-0000000000e3','00000000-0000-0000-0000-0000000000a3','2026-07-01','CC02_D1_PAIN008'),
            ('00000000-0000-0000-0000-0000000000e4','00000000-0000-0000-0000-0000000000a4','2026-07-01','RF01_D1_PAIN008')""");
        jdbc.execute("""
            INSERT INTO public.crw_emission_member (emission_id, sequence, e2e, amount) VALUES
            ('00000000-0000-0000-0000-0000000000e1',1,'E2E-CC1-002',100.00),
            ('00000000-0000-0000-0000-0000000000e1',2,'E2E-CC1-003',200.00),
            ('00000000-0000-0000-0000-0000000000e1',3,'E2E-CC1-004',300.00),
            ('00000000-0000-0000-0000-0000000000e2',1,'E2E-CC1-005',400.00),
            ('00000000-0000-0000-0000-0000000000e2',2,'E2E-CC1-006',100.00),
            ('00000000-0000-0000-0000-0000000000e3',1,'E2E-CC2-001',1000.00),
            ('00000000-0000-0000-0000-0000000000e3',2,'E2E-CC2-002',2000.00),
            ('00000000-0000-0000-0000-0000000000e4',1,'E2E-RF1-001',500.00)""");
    }
}
