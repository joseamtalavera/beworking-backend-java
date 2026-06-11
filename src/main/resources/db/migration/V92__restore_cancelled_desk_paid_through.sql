-- Restore desk occupancy for coworking subscriptions cancelled on 2026-06-11.
-- The cancellation bug set end_date = the cancellation date, freeing the desk
-- immediately even though it was paid through the current billing period —
-- which let the slot be double-booked.
--
-- Paid-through is the monthly anniversary of the start date (a sub that started
-- on the 10th is paid to the 10th of next month, NOT the calendar month-end), so
-- compute the period end per-sub: the first start-date anniversary strictly after
-- the cancellation date (2026-06-11).
-- Idempotent: after the first run end_date is the anniversary, so it won't re-match.
UPDATE beworking.subscriptions
SET end_date = (
        start_date + ((
            (EXTRACT(YEAR  FROM age(DATE '2026-06-11', start_date)) * 12
           + EXTRACT(MONTH FROM age(DATE '2026-06-11', start_date)))::int + 1
        ) * INTERVAL '1 month')
    )::date,
    updated_at = now()
WHERE producto_id IS NOT NULL
  AND active = false
  AND end_date = DATE '2026-06-11'
  AND start_date IS NOT NULL;
