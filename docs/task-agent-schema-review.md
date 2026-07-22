# Task Agent Schema — Production Review (V1 → V2)

> Reviewer stance: Principal Database Architect / Staff Engineer reviewing a production
> database for an enterprise, multi-tenant, AI-first SaaS.
> Subject: [`V1__task_agent.sql`](../soul-orchestrator/src/main/resources/db/migration/V1__task_agent.sql)
> Output: [`V2__task_agent_hardening.sql`](../soul-orchestrator/src/main/resources/db/migration/V2__task_agent_hardening.sql)
> (additive, backward-compatible, **validated** end-to-end against PostgreSQL 16 — see §Validation).

---

## 1. Review Summary

The V1 schema is **well above average for an initial cut**. It already does the things most
first drafts get wrong: `tenant_id`/`user_id` on every table, `timestamptz` everywhere,
optimistic-lock `version` columns, a separate durable `scheduled_job` ledger, an append-only
audit table, `ON DELETE CASCADE`/`SET NULL` chosen deliberately, and one genuinely good partial
index (`idx_task_due`). The domain modelling (recurrence split out, reminders separate from
tasks) is sound DDD. **I did not need to redesign anything.**

What it lacked is what separates "correct" from "production-grade at enterprise scale": type
safety on status fields, delivery/observability metadata, lease-based scheduling for multiple
nodes, distributed tracing, soft delete, search, FK indexes, DB-maintained `updated_at`, and an
event-outbox to support the Notification-Agent split. **V2 adds all of it additively.**

**The single most important finding is timing, not a table.** The schema is at **phase 0 with
zero rows**. Every conversion in this review — most importantly `TEXT → ENUM`, which is an
`ACCESS EXCLUSIVE` full-table rewrite on a populated 100M-row table — is an **instant catalog
change today**. Doing this review now converts a future multi-hour, lock-heavy migration into a
free one. That is the headline recommendation: **apply V2 before any data lands.**

**Rating: V1 = 7.5/10 (strong start). Post-V2 = 9/10 (enterprise-ready; the remaining
point is deferred-by-design: partitioning, RLS, pgvector, UUIDv7 — see §9/§10).**

---

## 2. List of Improvements

Each item: **what**, **why**, and **the decision** (including where I deliberately changed
*nothing*).

### 2.1 Status safety — native ENUMs (review area 1) ✅ done
`task_status`, `reminder_status`, `scheduled_job_status`, `notification_status` created as
native `ENUM`s; the four `status` columns converted in place. Also enum'd the closed sets
`scheduled_job_kind`, `notification_channel`, `audit_actor_type`.

- **Why:** free-form `TEXT` status is the classic source of silent data drift — a typo'd
  `'complete'` vs `'COMPLETED'`, a Python worker writing a value the Java side never expects.
  An enum makes the catalog reject anything off-list (verified: `UPDATE … status='BOGUS'` →
  `invalid input value for enum`). 4-byte storage, self-documenting, introspectable.
- **Migration strategy (the important part):** on the **empty** phase-0 table this is instant.
  On a **populated** table it is an `ACCESS EXCLUSIVE` rewrite — the online path is: add a new
  enum column, backfill in batches, dual-write via trigger, swap, drop old. Documented in §5.
- **Enum-evolution caveat (called out in the SQL):** new values are added later with
  `ALTER TYPE … ADD VALUE`, which cannot run in the same transaction that then *uses* the value
  — keep such additions in their own Flyway migration. Never rely on enum ordinal order.
- **Considered alternative:** `TEXT + CHECK` constraint (or a lookup table). Cheaper to evolve,
  but weaker typing across a polyglot (Java + Python) writer set. For these small, stable
  lifecycle sets, native enum is the right call; for a set expected to churn weekly, I'd have
  chosen a lookup table. This is a deliberate, per-field judgement, not a blanket rule.

### 2.2 Task types (review area 2) ✅ done
`task_type` enum (`TASK, REMINDER, MEETING, EMAIL, AUTOMATION, WORKFLOW`), column `task.type`
default `'TASK'`.
- **Why:** the Task Agent will grow past reminders. Typing lets the execution/notification path
  branch by type, lets the UI filter, lets analytics group, and lets future `AUTOMATION`/
  `WORKFLOW` rows carry different `payload`/`ai_metadata` shapes without new tables. It is also a
  natural future partition/sharding secondary key.

### 2.3 AI metadata (review area 3) ✅ done
`task.ai_metadata JSONB` (GIN-indexed) + two **promoted** scalar columns `ai_confidence
NUMERIC(4,3)` and `ai_summary TEXT`.
- **Why JSONB + promotion:** `extracted_entities`, `generated_tags`, `priority_reason` are
  nested/evolving — JSONB absorbs schema change with no DDL. But `confidence` is used for
  *sorting/filtering* (smart prioritization) and `summary` for *search* — extracting those from
  JSONB on every query is slow and unindexable, so they are promoted to real, typed, indexable
  columns. Hybrid is the correct pattern: flexible bag + promoted hot fields.
- **Future AI use cases:** confidence-weighted prioritization; entity linking → knowledge graph;
  `priority_reason` surfaced as an explanation in the UI; summaries feeding digest generation;
  `ai_metadata` as the staging ground for RAG features before they earn their own tables.
- **Deliberately NOT added now:** an embedding/`vector` column (see §2.17, §8).

### 2.4 Tags (review area 4) ✅ done — normalized, `category` preserved
New `tag(tenant_id, name, color, usage_count, …)` + `task_tag` join. `task.category` untouched.
- **Trade-off & decision:** `TEXT[] + GIN` is simpler and join-free — genuinely fine for a
  single-tenant or filter-only use. I chose the **normalized catalog** because this is explicitly
  an enterprise multi-tenant SaaS: you will want a per-tenant tag catalog, rename-in-one-place,
  colors, usage analytics, and governance/ACLs — none of which `TEXT[]` gives you without
  unnesting gymnastics. The cost is a join on read and two writes on tag-add; acceptable, and
  `idx_task_tag_tag` keeps the reverse lookup fast. `category` stays as the single coarse bucket;
  `tags` are the many fine labels. They are not redundant.

### 2.5 Reminder payload (review area 5) ✅ done
`reminder.payload JSONB` + `channels` upgraded to `notification_channel[]` (enum array) +
`max_attempts`.
- **Generic model:** `payload` is channel-agnostic — `{ template_key, title, body, data{},
  locale, priority, channel_overrides:{ voice:{…}, email:{subject,…}, slack:{blocks…} } }`.
  Adding WhatsApp/Teams/Push later is a **new JSON key, zero DDL**. `channels` says *where*;
  `payload` says *what*; the Notification Agent renders per channel. This is what makes the
  notification fan-out (review area 18) open-ended.

### 2.6 Notification improvements (review area 6) ✅ done
Added `provider`, `provider_message_id`, `provider_response JSONB`, `delivery_metadata JSONB`,
`attempts`, `max_attempts`, `next_retry_at`, `last_error`, `suppression_reason`, `delivered_at`,
`failed_at`, plus tracing columns.
- **Each field:** `provider` = which vendor (twilio/sendgrid/fcm/slack); `provider_message_id` =
  their id, the key inbound **delivery-receipt webhooks** correlate on (indexed);
  `provider_response` = raw ack/error for debugging; `delivery_metadata` = flexible per-channel
  delivery detail; `attempts`/`max_attempts`/`next_retry_at`/`last_error` = the retry state
  machine; `suppression_reason` = *why* a notification was `SUPPRESSED` (quiet-hours / task
  already completed / dedupe) — essential for "why didn't I get notified?" support tickets;
  `delivered_at`/`failed_at` = the delivery timeline distinct from `sent_at`.

### 2.7 Scheduler improvements (review area 7) ✅ done — lease-based, multi-node
Added `lease_until`, `worker_id`, `node_id`, `heartbeat_at`, `priority`, `max_attempts`, plus
tracing. Replaced `idx_job_due` with `idx_job_claimable (priority, run_at) WHERE status='PENDING'`
and `idx_job_lease (lease_until) WHERE status='CLAIMED'`. `fillfactor=80`.
- **Why:** V1's `claimed_by/claimed_at` implies a reaper that guesses staleness by wall-clock.
  A **lease** is explicit and correct: a job is owned until `lease_until`; a long job extends it
  via `heartbeat_at`; a dead node's jobs are reclaimed the instant `lease_until < now()`. This is
  the durable, multi-instance-safe pattern, and it pairs with the existing `FOR UPDATE SKIP
  LOCKED` claim. `priority` lets urgent reminders jump the queue. `fillfactor 80` leaves page
  room so the frequent `CLAIMED`/`heartbeat`/`DONE` updates are **HOT** (heap-only-tuple, no
  index write) — a real throughput win on the hottest table.

### 2.8 Distributed tracing (review area 8) ✅ done
`trace_id`, `correlation_id` added to `task`, `scheduled_job`, `notification`, `task_audit`;
`command_id` on `scheduled_job`; `causation_id` on `notification`; `created_by_command_id` on
`task`.
- **Where they belong & why:** `trace_id` = one end-to-end OpenTelemetry trace (Manager →
  command → Task Agent → scheduler → notification). `correlation_id` = the whole user-intent flow
  across agents/turns. `command_id` = the exact `AgentCommand` that caused this row (ties DB state
  back to the event log). `causation_id` = the event that directly caused this notification. This
  is what turns "a reminder didn't fire" into a single indexed trace lookup instead of log
  archaeology. `idx_audit_trace` supports it.

### 2.9 Soft delete (review area 9) ✅ done
`task.deleted_at TIMESTAMPTZ`; all hot task indexes made partial `WHERE deleted_at IS NULL`.
- **Advantages:** recoverability/undo; referential safety (audit + notification rows survive);
  analytics on deleted work; GDPR "mark then purge" workflow; and — because the indexes exclude
  soft-deleted rows — **no read-path cost** for the live set. Note it is distinct from
  `CANCELLED` (a user decision, still visible) — `deleted_at` means removed from view. Physical
  delete is retained for the hard-purge (GDPR) path.

### 2.10 Search (review area 10) ✅ done — Soul needs it
`task.search_vector tsvector GENERATED ALWAYS … STORED` (weighted A=title, B=notes) + GIN;
`pg_trgm` GIN on `title`.
- **Does Soul need it?** Yes — users will search their tasks by words ("dentist", "invoice").
  A generated `tsvector` is always-correct (no trigger to maintain, no drift) and GIN makes it
  fast. `pg_trgm` additionally gives **typo-tolerant / substring** search for autocomplete
  (verified: "dentis" fuzzy-matched "dentist"). FTS for word queries, trigram for fuzzy — the two
  cover the real query shapes. Chose `'english'` config; switch to `'simple'` if multilingual
  tenants make stemming wrong.

### 2.11 Foreign-key indexes (review area 11) ✅ done
Postgres does **not** auto-index FKs. Added: `idx_task_recurrence`, `idx_task_parent`,
`idx_reminder_task` (was missing — this one matters most: it speeds the `ON DELETE CASCADE` from
`task` and every "reminders for task" lookup), `idx_notification_task`, `idx_job_ref`,
`idx_task_tag_tag`.
- **Expected improvement:** without them, a parent delete or a join scans the child table; a
  cascade delete of a task with reminders would seq-scan `reminder`. At 10M reminders that is the
  difference between an index probe and a full scan, and it removes a lock-escalation risk on the
  parent.

### 2.12 Automatic `updated_at` (review area 12) ✅ done — triggers
`set_updated_at()` trigger function; `BEFORE UPDATE` triggers on `task`, `reminder`,
`scheduled_job`, `notification`, `tag`.
- **Why trigger, not `DEFAULT now()`:** the V1 default only fires on **INSERT**; an UPDATE never
  refreshes it, so `updated_at` was quietly wrong. Application-managed (`@UpdateTimestamp`) would
  work for Hibernate writes but **not** for the scheduler, raw SQL, or the Python workers — the DB
  trigger is the single source of truth for *every* writer (verified: UPDATE advanced it, INSERT
  left it equal to `created_at`).

### 2.13 Constraints (review area 13) ✅ done
Added `CHECK`s: `ai_confidence ∈ [0,1]`; `completed_at IS NULL OR status='COMPLETED'`;
`attempts >= 0` on reminder/notification; `attempts <= max_attempts+1` on jobs. Added the `tag`
uniqueness (`tenant_id,name`). Used **generated column** for `search_vector`. Kept the V1
`chk_task_priority`.
- **Deliberately NOT added:** a generated `is_overdue` column — "overdue" depends on `now()`,
  which is not `IMMUTABLE`, so it cannot be generated; it stays a query predicate (correct).
  Status-transition validity (e.g. can't go `COMPLETED → PENDING`) is **not** a DB constraint —
  it belongs in the domain aggregate (DDD), where the transition table lives; enforcing it in SQL
  would duplicate and fossilize domain logic.

### 2.14 Performance (review area 14) ✅ done + documented
Composite `(tenant_id, user_id, status)` **covering** index `INCLUDE (title, due_at, priority)`
for index-only list scans; partial indexes throughout (`WHERE deleted_at IS NULL`, `WHERE
status='PENDING'`); `fillfactor` lowered on the two hottest tables for HOT updates. Partitioning &
UUIDv7 are **documented, not applied** — see §7 and §9.

### 2.15 Audit improvements (review area 15) ✅ done
Added `event_version`, `actor_id` (UUID), `actor_type` (enum), `trace_id`, `correlation_id`,
`event_source`. Made it **truly append-only** via a `BEFORE UPDATE OR DELETE` trigger that raises
(verified: UPDATE rejected). Added `idx_audit_trace`, `idx_audit_tenant`.
- **Why:** `event_version` lets payload schemas evolve without breaking replay; structured
  `actor_id/actor_type` beats parsing `'rest:user'` strings; immutability is now DB-enforced, not
  a naming convention. (A tamper-evident hash-chain is noted as a future enterprise option in §9.)

### 2.16 Multi-tenancy (review area 16) ✅ indexes done; RLS documented
All hot task indexes lead with `tenant_id`; `idx_audit_tenant` added. **Row-Level Security is
recommended but deliberately NOT enabled** — see §9. Rationale: enabling RLS requires the app to
set a per-connection GUC (`app.tenant_id`) and complicates connection pooling; turning it on today
would break the current app. It is the right *future* defense-in-depth layer, with the policy
sketched in §9.

### 2.17 AI readiness (review area 17) ✅ sufficient; pgvector deferred
`ai_metadata` + `ai_confidence` + `ai_summary` + FTS cover prioritization, summarization, and
smart-suggestion inputs (history already in `task_audit`) **today**. For **RAG / semantic
search**, the right design is a **separate `task_embedding(task_id, model, embedding vector,
created_at)` table with `pgvector` + HNSW**, introduced *when that feature is built* — not a
`vector` column bolted onto the 100M-row `task` table (it would bloat the hot table and the
embedding model/dimensions will change). **Recommendation: add nothing more now**; the JSONB bag
is the forward-compatible staging area.

### 2.18 Notification architecture — Task Agent should NOT own delivery (review area 18) ✅ outbox added
New `outbox_event` table (transactional outbox).
- **Evaluation:** the Task Agent should own *task state*, not *delivery*. The target architecture
  (`Task Agent → TaskTriggered → Notification Agent → voice/email/slack/…`) is correct DDD — two
  bounded contexts. The schema change that makes it **reliable** is the transactional outbox: the
  Task Agent writes its state change **and** an `outbox_event` row in **one transaction**; a relay
  publishes to the Notification Agent and marks it `PUBLISHED`. This avoids the dual-write problem
  (DB commit succeeds, event publish fails → lost notification) that plagues naïve EDA. The
  `notification` table then becomes the Notification Agent's ledger, populated from consumed
  events. `idx_outbox_unpublished` drives the relay.

### 2.19 Scalability (review area 19) — documented, see §7 & §9
100M tasks / 10M reminders / multi-node / multi-region assessed in §7 (performance) and §9
(enterprise). Headlines: partition the append-heavy tables by time; make `tenant_id` the
distribution key for a future Citus/sharding move; adopt time-ordered UUIDs to stop index
fragmentation at scale.

### 2.20 What I did NOT change, and why
- **Did not touch the core table shapes, PKs, or relationships** — they are sound.
- **Did not replace `category`** — kept it; added `tags` alongside (as instructed).
- **Did not enable RLS / partitioning / pgvector / UUIDv7** — each is either premature (no data),
  breaking (RLS needs app changes), or best introduced with its feature (pgvector). All are
  documented as staged next steps rather than smuggled in.

---

## 3. SQL Migration Scripts

The single migration is
[`V2__task_agent_hardening.sql`](../soul-orchestrator/src/main/resources/db/migration/V2__task_agent_hardening.sql).
It is **idempotent within Flyway's versioning**, ordered so type conversions precede dependent
index/constraint creation, and was **validated by applying V1 then V2 to a throwaway PostgreSQL
16 database** (see §Validation). It groups changes by concern (enums → task → tags → reminder →
scheduled_job → notification → audit → outbox) so it reads as a review, not a diff.

Two portability notes encoded in the SQL:
- The `task` status conversion drops `idx_task_user_status`/`idx_task_due` **first**, because a
  partial index whose predicate compares `status` can't be recast to the new enum mid-`ALTER`.
- The `reminder.channels` array conversion uses an `IMMUTABLE` helper function (dropped after),
  because `ALTER COLUMN … USING` forbids a subquery — this is also the technique that works on
  populated data.

---

## 4. Updated DDL (effective shape after V2)

Key tables post-V2 (abridged to the columns that changed; unchanged V1 columns omitted):

```
task
  status        task_status          -- was TEXT
  type          task_type            -- NEW
  ai_metadata   jsonb                 -- NEW (GIN)
  ai_confidence numeric(4,3)          -- NEW
  ai_summary    text                  -- NEW
  deleted_at    timestamptz           -- NEW (soft delete; partial indexes)
  search_vector tsvector GENERATED    -- NEW (GIN) + pg_trgm on title
  trace_id / correlation_id / created_by_command_id  -- NEW
  + updated_at trigger, fillfactor 90, chk_completed_at, chk_ai_confidence

reminder
  status        reminder_status       -- was TEXT
  channels      notification_channel[]-- was text[]
  payload       jsonb                 -- NEW (generic delivery model)
  max_attempts, updated_at            -- NEW; + idx_reminder_task (FK), trigger

scheduled_job
  status        scheduled_job_status  -- was TEXT
  kind          scheduled_job_kind    -- was TEXT
  lease_until, worker_id, node_id, heartbeat_at, priority, max_attempts  -- NEW
  trace_id, correlation_id, command_id, updated_at                       -- NEW
  + idx_job_claimable, idx_job_lease, idx_job_ref, trigger, fillfactor 80

notification
  status        notification_status   -- was TEXT
  channel       notification_channel  -- was TEXT
  provider, provider_message_id, provider_response, delivery_metadata    -- NEW
  attempts, max_attempts, next_retry_at, last_error, suppression_reason  -- NEW
  delivered_at, failed_at, updated_at, trace_id, correlation_id, causation_id -- NEW
  + idx_notification_task/retry/provider, trigger

task_audit  (+ event_version, actor_id, actor_type, trace_id, correlation_id, event_source;
             append-only trigger; idx_audit_trace/tenant)

NEW: tag, task_tag (normalized tags) · outbox_event (transactional outbox)
NEW enums: task_status, reminder_status, scheduled_job_status, notification_status,
           task_type, scheduled_job_kind, notification_channel, audit_actor_type
```

---

## 5. Migration Strategy

**Now (phase 0, empty tables):** apply V2 as an ordinary Flyway migration. It becomes V2 in
`flyway_schema_history`; the app restart runs it. Cost ≈ zero — all conversions are metadata-only
on empty tables. **This is the recommended path and the reason to do it immediately.**

**If this were a populated table (the general enterprise playbook, documented for the team):**
1. **TEXT→ENUM online:** add `status_new <enum>`; backfill in batched `UPDATE … WHERE id IN (…)`
   to avoid a long lock; add a `BEFORE INSERT/UPDATE` trigger to dual-write; once caught up, in a
   short transaction `ALTER TABLE … RENAME`/swap and drop the trigger + old column. Never a single
   blocking `ALTER TYPE` on a large hot table.
2. **New columns:** all V2 additions are nullable or have defaults → in PG11+ adding a column with
   a constant default is metadata-only (no rewrite). Safe online.
3. **New indexes:** build with `CREATE INDEX CONCURRENTLY` (outside a transaction; a separate
   Flyway migration with `executeInTransaction=false`) so writes aren't blocked.
4. **`fillfactor`:** takes effect for new/updated rows; run `VACUUM (FULL, …)` off-peak only if
   you need it applied to existing rows.
5. **Sequencing:** enums → columns → backfill → indexes concurrently → constraints `NOT VALID`
   then `VALIDATE CONSTRAINT` (validates without an exclusive lock).

**Rollback:** V2 is additive, so a down-path is drop-the-new-objects; but the ENUM conversions
are the only non-trivial reversal (`TYPE TEXT USING status::text`). Because we apply while empty,
rollback is trivial. Flyway "undo" is Teams-edition; the practical rollback is a forward V3 that
reverts if ever needed.

---

## 6. Backward Compatibility Notes

- **Additive:** every change is a new column/table/index/trigger or an in-place type tightening.
  No column dropped, renamed, or repurposed. No relationship changed.
- **Hibernate (`ddl-auto: validate`):** the JPA entities must be updated to match before the app
  boots against a V2 database (new columns/enum types), or validation fails. Enums map via
  `@Enumerated(EnumType.STRING)` + a Postgres enum type handler, or as `TEXT` with a `@Check` — the
  entity work lands in **phase 1** (domain), which is where these columns get consumed anyway. V2
  is deliberately ahead of the entities; until phase 1, `soul.task.enabled=false` keeps the app
  from touching these tables.
- **Reads:** existing V1 queries keep working — enum columns compare to string literals
  transparently (`status = 'PENDING'`), arrays and new columns are additive.
- **The `channels` array element case changed** (`in_app` → `IN_APP`) to match the enum. Any code
  writing lowercase channel strings must use the enum labels; there is no data to migrate (empty).

---

## 7. Performance Considerations

- **List view is now index-only:** `idx_task_user_status … INCLUDE (title, due_at, priority)
  WHERE deleted_at IS NULL` serves the dominant "my active tasks" query from the index alone.
- **Scheduler claim is O(log n) and contention-light:** `idx_job_claimable (priority, run_at)
  WHERE status='PENDING'` + `FOR UPDATE SKIP LOCKED`; the reaper uses `idx_job_lease`. `fillfactor
  80` keeps the hot claim/heartbeat/done updates HOT.
- **HOT-update discipline:** frequently-mutated columns (`status`, `lease_until`, `heartbeat_at`,
  `updated_at`) are kept *out* of most index keys so updates don't churn indexes — the reason not
  to over-index the hot tables.
- **Search:** GIN on `search_vector` (words) + GIN `pg_trgm` on `title` (fuzzy). GIN is write-
  amplifying; acceptable because tasks are read-searched far more than bulk-written, and the
  generated column means no trigger overhead.
- **Large-table growth / partitioning readiness (100M tasks, 10M reminders):**
  - Append-heavy, time-scoped tables — `task_audit`, `notification`, `outbox_event`,
    `scheduled_job` (DONE rows) — are the partitioning candidates: **`RANGE (created_at)` monthly**,
    with old partitions detached/archived. This is the biggest single scale lever.
  - **Readiness caveat (a real cost to flag):** converting a non-partitioned table to partitioned
    later requires a rewrite/attach, and the partition key must be in the PK/unique constraints.
    `task_audit.id` (BIGSERIAL) would need `(id, at)` as PK to partition by `at`. Since tables are
    empty, if the team commits to partitioning, **change those PKs now in a V3** — I did not do it
    unilaterally because it's a policy decision with a modelling trade-off.
  - `task` itself: partition by **`HASH (tenant_id)`** for tenant locality/parallelism, or leave
    unpartitioned and rely on the tenant-leading indexes until a single tenant's volume demands it.

---

## 8. AI Architecture Considerations

- **Today:** `ai_metadata` (JSONB, GIN) + `ai_confidence` (sortable) + `ai_summary` (searchable)
  support smart prioritization, summarization, deadline/priority reasoning, and auto-categorization
  — with the deterministic guards the domain enforces (the schema stores AI *outputs*; it does not
  trust them — `ai_confidence` is advisory, a user-set `priority` always wins).
- **Smart suggestions / memory:** history already lives in the immutable `task_audit`; suggestions
  are rules over it. No schema change needed now.
- **RAG / semantic search (future):** a dedicated `task_embedding` table with **`pgvector`** and an
  **HNSW** index, populated by an embedding worker off the outbox stream — deliberately separate so
  the hot `task` table stays lean and the embedding model/dimension can change independently.
- **Event-driven AI:** the `outbox_event` stream is also the clean hook for AI enrichment — an AI
  worker consumes `TaskCreated`, writes `ai_metadata`/`ai_summary` back asynchronously, never on
  the user's critical path (matching the "AI is an enhancement, not a dependency" rule from the
  spec).

---

## 9. Enterprise Readiness Assessment

| Dimension | Post-V2 status | Gap / next step |
| --- | --- | --- |
| **Type safety** | ✅ enums on all lifecycle fields | — |
| **Observability** | ✅ trace/correlation/causation IDs, provider receipts, audit | wire to OTel in app |
| **Multi-node scheduling** | ✅ lease + heartbeat + priority + SKIP LOCKED | — |
| **Reliability (EDA)** | ✅ transactional outbox | build the relay + Notification Agent |
| **Search** | ✅ FTS + trigram | — |
| **Soft delete / GDPR** | ✅ `deleted_at`; hard-purge path exists | data-retention job |
| **Audit integrity** | ✅ append-only (DB-enforced) | optional tamper-evident hash-chain |
| **Multi-tenancy** | ✅ tenant-scoped indexes | **enable RLS** (defense-in-depth) — future |
| **Scale to 100M** | 🟡 index/fillfactor ready | **partition** append tables; **UUIDv7** PKs |
| **Multi-region** | 🟡 `tenant_id` is a clean shard key | Citus/logical-replication decision |

**Two scale recommendations I flagged but did not apply (they are decisions, not fixes):**
- **UUIDv7 (time-ordered) PKs.** At 100M rows, random `gen_random_uuid()` (v4) PKs fragment the
  B-tree and amplify writes. Time-ordered UUIDv7 restore insert locality. PG18 has `uuidv7()`; on
  PG16 generate in-app or via an extension. Big win at scale, but it changes ID generation across
  the app — a deliberate call.
- **Row-Level Security.** `ALTER TABLE task ENABLE ROW LEVEL SECURITY; CREATE POLICY tenant_isolation
  ON task USING (tenant_id = current_setting('app.tenant_id')::uuid);` — DB-enforced isolation even
  if the app has a bug. Requires setting `app.tenant_id` per transaction and pooling care; enable
  when the app is ready, not before.

---

## 10. Final Rating

| | Score | Notes |
| --- | --- | --- |
| **V1 (initial schema)** | **7.5 / 10** | Genuinely strong first cut — tenant-ready, timestamptz, optimistic locking, durable job ledger, one good partial index. Held back only by production concerns it hadn't reached yet. |
| **V2 (post-review)** | **9 / 10** | Enterprise-ready: type-safe, observable, multi-node, searchable, soft-deletable, outbox-backed, correctly indexed. |
| **Remaining 1 point** | deferred *by design* | Partitioning, RLS, UUIDv7, pgvector — each best introduced with its triggering feature or data volume, and each a conscious decision rather than an omission. |

**Bottom line:** no redesign was warranted. V2 is a set of additive, validated, backward-compatible
hardening changes that should be applied **now, while the tables are empty** — which is precisely
what turns the expensive version of this migration into the free one.

---

## Validation

Both migrations were applied in sequence (`V1` then `V2`) to an ephemeral PostgreSQL 16 database,
and the resulting schema was behavior-tested:

- ✅ V2 applies cleanly on top of V1 (after fixing two real ordering bugs the validation caught:
  status-dependent index drop ordering, and the subquery-in-`USING` restriction).
- ✅ 8 enum types created; all four `status` columns + `kind` + `channel` converted.
- ✅ Generated `search_vector` populated with weighted lexemes (`'dentist':3A`); FTS query matched.
- ✅ `pg_trgm` fuzzy search matched a typo ("dentis" → "dentist").
- ✅ `updated_at` trigger: equal to `created_at` on INSERT, advanced on UPDATE.
- ✅ Enum rejects an off-list value (`status='BOGUS'` → error).
- ✅ `task_audit` is append-only (UPDATE/DELETE rejected by trigger).
- ✅ `tag`, `task_tag`, `outbox_event` present; all FK indexes and triggers present.

The live `soul` database was left untouched (still at V1); validation used a throwaway database
that was dropped afterward.
