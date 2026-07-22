-- Task Agent — initial schema (docs/task-agent.md §6).
-- Flyway owns this schema; Hibernate only validates against it.
--
-- Conventions:
--   * All timestamps are timestamptz (stored UTC; the zone the user *meant* is a separate
--     column where it matters — see §20).
--   * tenant_id + user_id on every table from day one (§23): retrofitting a tenant key is a
--     migration nightmare, carrying an unused one is free.
--   * gen_random_uuid() comes from pgcrypto (built into core since PG13, extension kept for
--     portability to older servers).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── recurrence ───────────────────────────────────────────────────────────────────────
CREATE TABLE recurrence (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rrule       TEXT        NOT NULL,               -- RFC-5545 RRULE
    dtstart     TIMESTAMPTZ NOT NULL,
    tz          TEXT        NOT NULL,               -- evaluated in this zone (DST-correct, §20)
    until_at    TIMESTAMPTZ,
    count_left  INTEGER
);

-- ── task ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE task (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    title         TEXT        NOT NULL,
    notes         TEXT,
    status        TEXT        NOT NULL DEFAULT 'PENDING',   -- §17
    priority      SMALLINT    NOT NULL DEFAULT 2,           -- 0 highest .. 4 lowest
    category      TEXT,
    due_at        TIMESTAMPTZ,                              -- UTC; null = someday
    due_tz        TEXT,                                     -- IANA zone the user meant
    recurrence_id UUID REFERENCES recurrence(id),
    parent_id     UUID REFERENCES task(id),                -- recurrence instances / subtasks
    source        TEXT        NOT NULL DEFAULT 'agent',     -- agent | rest | recurrence
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,           -- optimistic lock
    CONSTRAINT chk_task_priority CHECK (priority BETWEEN 0 AND 4)
);
CREATE INDEX idx_task_user_status ON task (tenant_id, user_id, status);
CREATE INDEX idx_task_due         ON task (status, due_at) WHERE status IN ('PENDING', 'SCHEDULED');

-- ── reminder ─────────────────────────────────────────────────────────────────────────
CREATE TABLE reminder (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id       UUID        NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    fire_at       TIMESTAMPTZ NOT NULL,                     -- UTC instant this is due
    lead_label    TEXT,                                     -- "1 day before", "at time" (UI)
    channels      TEXT[]      NOT NULL DEFAULT '{in_app}',  -- in_app | voice | push ...
    status        TEXT        NOT NULL DEFAULT 'PENDING',   -- PENDING|FIRED|MISSED|CANCELLED
    fired_at      TIMESTAMPTZ,
    attempts      SMALLINT    NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX idx_reminder_due ON reminder (status, fire_at);

-- ── scheduled_job — the durable job ledger the scheduler scans (§7) ───────────────────
CREATE TABLE scheduled_job (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL,
    kind       TEXT        NOT NULL,                        -- REMINDER | RECURRENCE_ROLL | ESCALATION
    ref_id     UUID        NOT NULL,                        -- reminder.id or recurrence.id
    run_at     TIMESTAMPTZ NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'PENDING',      -- PENDING|CLAIMED|DONE|DEAD
    claimed_by TEXT,                                        -- node id (multi-instance safety)
    claimed_at TIMESTAMPTZ,
    attempts   SMALLINT    NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_due ON scheduled_job (status, run_at);

-- ── notification ─────────────────────────────────────────────────────────────────────
CREATE TABLE notification (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    task_id    UUID REFERENCES task(id) ON DELETE SET NULL,
    channel    TEXT        NOT NULL,
    body       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'PENDING',      -- PENDING|SENT|DELIVERED|FAILED|SUPPRESSED
    dedupe_key TEXT,                                        -- idempotency (§21)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ,
    CONSTRAINT uq_notification_dedupe UNIQUE (tenant_id, dedupe_key)
);

-- ── task_audit — append-only ─────────────────────────────────────────────────────────
CREATE TABLE task_audit (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  UUID        NOT NULL,
    task_id    UUID,
    actor      TEXT        NOT NULL,                        -- agent | scheduler | rest:user | system
    event_type TEXT        NOT NULL,
    payload    JSONB       NOT NULL,
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_task ON task_audit (task_id, at);
