-- Task Agent — V2: production hardening.
-- Full rationale + trade-offs: docs/task-agent-schema-review.md.
--
-- This migration is ADDITIVE and BACKWARD-COMPATIBLE. It is applied while the phase-0 tables
-- are still EMPTY, which is the entire reason to do it now: the TEXT→ENUM conversions below are
-- instant catalog changes today, but would be ACCESS EXCLUSIVE table rewrites on a populated
-- 100M-row table (the review documents the online strategy for that case).
--
-- Enum-evolution note: new enum values are added later with `ALTER TYPE x ADD VALUE 'Y'`. That
-- statement cannot run in the same transaction that then USES the value; keep such additions in
-- their own migration. Never depend on enum ordinal order.

CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- fuzzy / typo-tolerant search (§10)

-- ══ 1 & 2. Status safety + task type — native ENUM types ═══════════════════════════════
CREATE TYPE task_status          AS ENUM ('DRAFT','PENDING','SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED');
CREATE TYPE reminder_status      AS ENUM ('PENDING','FIRED','MISSED','CANCELLED');
CREATE TYPE scheduled_job_status AS ENUM ('PENDING','CLAIMED','DONE','DEAD');
CREATE TYPE notification_status  AS ENUM ('PENDING','SENT','DELIVERED','FAILED','SUPPRESSED');
CREATE TYPE task_type            AS ENUM ('TASK','REMINDER','MEETING','EMAIL','AUTOMATION','WORKFLOW');
CREATE TYPE scheduled_job_kind   AS ENUM ('REMINDER','RECURRENCE_ROLL','ESCALATION');
CREATE TYPE notification_channel AS ENUM
    ('IN_APP','VOICE','EMAIL','PUSH','DESKTOP','WHATSAPP','SLACK','TEAMS','SMS','WEBHOOK');
CREATE TYPE audit_actor_type     AS ENUM ('AGENT','SCHEDULER','USER','SYSTEM');

-- ── shared triggers ───────────────────────────────────────────────────────────────────
-- updated_at maintained by the DB, not the app (§12): correct even for writers that bypass
-- Hibernate — the scheduler, raw SQL, REST — which is exactly where app-managed timestamps rot.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Append-only enforcement for the audit log (§15).
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only table %: % is not permitted', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

-- ══ task ══════════════════════════════════════════════════════════════════════════════
-- Drop the status-dependent indexes FIRST: idx_task_due's predicate (status IN (...)) is
-- re-checked when the column is retyped and would fail to cast text literals to the new enum.
DROP INDEX idx_task_user_status;
DROP INDEX idx_task_due;

-- Status conversion is its own statement: a CHECK comparing status = 'COMPLETED' in the SAME
-- ALTER that retypes the column can't cast the literal to the not-yet-committed enum type.
ALTER TABLE task
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN status TYPE task_status USING status::task_status,
    ALTER COLUMN status SET DEFAULT 'PENDING';

ALTER TABLE task
    ADD COLUMN type                  task_type NOT NULL DEFAULT 'TASK',       -- §2
    ADD COLUMN ai_metadata           JSONB NOT NULL DEFAULT '{}'::jsonb,      -- §3
    ADD COLUMN ai_confidence         NUMERIC(4,3),                            -- §3 promoted, sortable
    ADD COLUMN ai_summary            TEXT,                                    -- §3 promoted, searchable
    ADD COLUMN deleted_at            TIMESTAMPTZ,                             -- §9 soft delete
    ADD COLUMN trace_id              UUID,                                    -- §8 tracing
    ADD COLUMN correlation_id        UUID,
    ADD COLUMN created_by_command_id UUID,
    ADD CONSTRAINT chk_task_ai_confidence CHECK (ai_confidence IS NULL OR ai_confidence BETWEEN 0 AND 1),
    ADD CONSTRAINT chk_task_completed_at  CHECK (completed_at IS NULL OR status = 'COMPLETED');

-- Full-text search over title + notes, kept current by a generated column (§10).
ALTER TABLE task ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(notes, '')), 'B')
    ) STORED;

-- Recreate task indexes (dropped above before the type change): exclude soft-deleted rows,
-- lead with tenant_id, cover the list view for index-only scans (§11,§13,§14).
CREATE INDEX idx_task_user_status ON task (tenant_id, user_id, status)
    INCLUDE (title, due_at, priority) WHERE deleted_at IS NULL;
CREATE INDEX idx_task_due ON task (tenant_id, status, due_at)
    WHERE deleted_at IS NULL AND status IN ('PENDING','SCHEDULED');
CREATE INDEX idx_task_recurrence ON task (recurrence_id) WHERE recurrence_id IS NOT NULL;  -- FK
CREATE INDEX idx_task_parent     ON task (parent_id)     WHERE parent_id IS NOT NULL;      -- FK
CREATE INDEX idx_task_search     ON task USING GIN (search_vector);
CREATE INDEX idx_task_title_trgm ON task USING GIN (title gin_trgm_ops);                   -- fuzzy
CREATE INDEX idx_task_ai_meta    ON task USING GIN (ai_metadata);                          -- §3 queryable

CREATE TRIGGER trg_task_updated_at BEFORE UPDATE ON task
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Lower fillfactor leaves room for HOT (heap-only-tuple) updates on this hot table (§14).
ALTER TABLE task SET (fillfactor = 90);

-- ══ tags — normalized catalog, category preserved (§4) ════════════════════════════════
CREATE TABLE tag (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    color       TEXT,
    usage_count BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tag_tenant_name UNIQUE (tenant_id, name)
);
CREATE TRIGGER trg_tag_updated_at BEFORE UPDATE ON tag
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE task_tag (
    task_id    UUID        NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    tag_id     UUID        NOT NULL REFERENCES tag(id)  ON DELETE CASCADE,
    tenant_id  UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, tag_id)
);
CREATE INDEX idx_task_tag_tag ON task_tag (tag_id);   -- reverse lookup + FK index (§11)

-- ══ reminder — generic delivery payload + enum status/channels (§1,§5) ════════════════
-- ALTER COLUMN ... USING forbids a subquery, so element-wise array conversion goes through an
-- IMMUTABLE helper (this is also the technique that works on POPULATED data, not just here).
CREATE FUNCTION _v2_to_channels(text[]) RETURNS notification_channel[] LANGUAGE sql IMMUTABLE AS
    $$ SELECT COALESCE(array_agg(upper(c)::notification_channel), '{}'::notification_channel[])
       FROM unnest($1) AS c $$;

ALTER TABLE reminder
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN status TYPE reminder_status USING status::reminder_status,
    ALTER COLUMN status SET DEFAULT 'PENDING',
    ALTER COLUMN channels DROP DEFAULT,
    ALTER COLUMN channels TYPE notification_channel[] USING _v2_to_channels(channels),
    ALTER COLUMN channels SET DEFAULT '{IN_APP}',
    ADD COLUMN payload      JSONB NOT NULL DEFAULT '{}'::jsonb,     -- §5 channel-agnostic body
    ADD COLUMN max_attempts SMALLINT NOT NULL DEFAULT 5,
    ADD COLUMN updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT chk_reminder_attempts CHECK (attempts >= 0);

DROP FUNCTION _v2_to_channels(text[]);
CREATE INDEX idx_reminder_task ON reminder (task_id);   -- FK index (was missing; cascade + lookups)
CREATE TRIGGER trg_reminder_updated_at BEFORE UPDATE ON reminder
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ══ scheduled_job — lease-based claiming for multiple scheduler nodes (§1,§7,§8) ══════
ALTER TABLE scheduled_job
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN status TYPE scheduled_job_status USING status::scheduled_job_status,
    ALTER COLUMN status SET DEFAULT 'PENDING',
    ALTER COLUMN kind TYPE scheduled_job_kind USING kind::scheduled_job_kind,
    ADD COLUMN lease_until    TIMESTAMPTZ,                          -- claim expiry; reclaimed when past
    ADD COLUMN worker_id      TEXT,                                 -- which worker thread holds it
    ADD COLUMN node_id        TEXT,                                 -- which scheduler instance
    ADD COLUMN heartbeat_at   TIMESTAMPTZ,                          -- long jobs extend the lease
    ADD COLUMN priority       SMALLINT NOT NULL DEFAULT 5,          -- lower = sooner
    ADD COLUMN max_attempts   SMALLINT NOT NULL DEFAULT 5,
    ADD COLUMN updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN trace_id       UUID,
    ADD COLUMN correlation_id UUID,
    ADD COLUMN command_id     UUID,
    ADD CONSTRAINT chk_job_attempts CHECK (attempts >= 0 AND attempts <= max_attempts + 1);
DROP INDEX idx_job_due;
-- The claim scan: cheapest possible index for "PENDING and due, highest priority first".
CREATE INDEX idx_job_claimable ON scheduled_job (priority, run_at) WHERE status = 'PENDING';
-- The reaper: find CLAIMED jobs whose lease has expired.
CREATE INDEX idx_job_lease     ON scheduled_job (lease_until)      WHERE status = 'CLAIMED';
CREATE INDEX idx_job_ref       ON scheduled_job (ref_id);          -- FK-ish lookup
CREATE TRIGGER trg_job_updated_at BEFORE UPDATE ON scheduled_job
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE scheduled_job SET (fillfactor = 80);   -- very hot (claim/heartbeat/done) → HOT updates

-- ══ notification — provider + delivery + retry metadata (§1,§6,§8) ════════════════════
ALTER TABLE notification
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN status TYPE notification_status USING status::notification_status,
    ALTER COLUMN status SET DEFAULT 'PENDING',
    ALTER COLUMN channel TYPE notification_channel USING upper(channel)::notification_channel,
    ADD COLUMN provider            TEXT,                            -- twilio | sendgrid | fcm | slack …
    ADD COLUMN provider_message_id TEXT,                            -- the id the provider returned
    ADD COLUMN provider_response   JSONB,                           -- raw provider ack / error
    ADD COLUMN delivery_metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN attempts            SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts        SMALLINT NOT NULL DEFAULT 5,
    ADD COLUMN next_retry_at       TIMESTAMPTZ,
    ADD COLUMN last_error          TEXT,
    ADD COLUMN suppression_reason  TEXT,                            -- quiet-hours | completed | dedupe …
    ADD COLUMN delivered_at        TIMESTAMPTZ,
    ADD COLUMN failed_at           TIMESTAMPTZ,
    ADD COLUMN updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN trace_id            UUID,
    ADD COLUMN correlation_id      UUID,
    ADD COLUMN causation_id        UUID,                            -- the event that caused this send
    ADD CONSTRAINT chk_notification_attempts CHECK (attempts >= 0);
CREATE INDEX idx_notification_task    ON notification (task_id) WHERE task_id IS NOT NULL;   -- FK
CREATE INDEX idx_notification_retry   ON notification (next_retry_at) WHERE status = 'FAILED';
CREATE INDEX idx_notification_provider ON notification (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;   -- inbound delivery-receipt webhooks look up by this
CREATE TRIGGER trg_notification_updated_at BEFORE UPDATE ON notification
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ══ task_audit — richer, immutable event history (§8,§15) ═════════════════════════════
ALTER TABLE task_audit
    ADD COLUMN event_version  SMALLINT NOT NULL DEFAULT 1,          -- payload schema version
    ADD COLUMN actor_id       UUID,                                 -- structured, not just free text
    ADD COLUMN actor_type     audit_actor_type,
    ADD COLUMN trace_id       UUID,
    ADD COLUMN correlation_id UUID,
    ADD COLUMN event_source   TEXT;                                 -- 'task-agent','scheduler','rest'
-- Enforce append-only at the database, not by convention.
CREATE TRIGGER trg_audit_immutable BEFORE UPDATE OR DELETE ON task_audit
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE INDEX idx_audit_trace  ON task_audit (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_audit_tenant ON task_audit (tenant_id, at);

-- ══ outbox_event — transactional outbox for the Notification Agent (§18) ══════════════
-- The Task Agent writes its domain change AND the event in ONE transaction; a relay publishes
-- to the Notification Agent. This is what makes "Task Agent → TaskTriggered → Notification Agent"
-- exactly-once at the boundary instead of a dual-write that can drop events on a crash.
CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    aggregate_type TEXT        NOT NULL,                            -- 'task' | 'reminder'
    aggregate_id   UUID        NOT NULL,
    event_type     TEXT        NOT NULL,                            -- 'ReminderDue' | 'TaskCompleted'
    payload        JSONB       NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'PENDING',          -- PENDING | PUBLISHED | FAILED
    attempts       SMALLINT    NOT NULL DEFAULT 0,
    trace_id       UUID,
    correlation_id UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE status = 'PENDING';
