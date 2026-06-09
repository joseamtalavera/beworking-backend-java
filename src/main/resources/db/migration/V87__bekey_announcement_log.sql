-- #255 BeKey announcement: idempotency log so the one-shot send is safe to
-- re-run (resume after a hiccup) — each email is sent at most once.
CREATE TABLE beworking.bekey_announcement_log (
    email    VARCHAR(255) PRIMARY KEY,
    sent_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
