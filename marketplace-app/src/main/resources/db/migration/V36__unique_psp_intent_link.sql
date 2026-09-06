-- V36: enforce the one-to-one local <-> remote payment-intent link at the
-- database level (CodeRabbit #241 finding on V33).
--
-- V33 shipped the psp_intent_id link column with a NON-unique partial index.
-- The entity-side guard (PaymentIntent.assignPspIntentId) only checks the
-- CURRENT row, so two local rows could in principle store the same non-null
-- PSP id; handleStripeWebhook's resolution path then calls findByPspIntentId,
-- whose Optional<PaymentIntent> contract requires a single row — duplicates
-- would raise IncorrectResultSizeDataAccessException and drop the webhook.
--
-- The unique partial index makes the invariant a database fact. Duplicates
-- are not expected (the idempotent createRemoteIntent flow links one remote
-- intent per local intent), so if any exist this migration fails LOUDLY
-- instead of silently keeping the broken state — that failure is the
-- reconciliation signal.
--
-- Index style note: a plain CREATE INDEX (not CONCURRENTLY). This table is
-- small, Flyway runs at boot before the application serves traffic (a failed
-- migration fails startup — the liveness gate), and CREATE INDEX CONCURRENTLY
-- cannot run inside Flyway's transactional migration wrapper. For future
-- migrations that build indexes on LARGE tables, see the migration guidance
-- in docs/CODING_STANDARDS.md (added with this change).

drop index if exists idx_payment_intents_psp;

create unique index if not exists uq_payment_intents_psp_intent_id
    on payment_intents (psp_intent_id)
    where psp_intent_id is not null;
