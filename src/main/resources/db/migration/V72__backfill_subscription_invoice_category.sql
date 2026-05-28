-- Sub-originated facturas (created via SubscriptionService.createInvoiceFromSubscription)
-- insert their facturasdesglose row with idbloqueovinculado = NULL — there is no booking
-- behind a subscription invoice. V64 backfilled category by joining
-- facturasdesglose -> bloqueos -> productos, so it silently skipped every sub invoice
-- that pre-dates the live category-on-insert path. Result: PT shows a handful of
-- Pendientes (recent post-V64 subs), GT shows 0 even though it has many unpaid
-- subscription invoices (most predate V64).
--
-- Fix: for any factura still NULL that originated from a Stripe subscription
-- webhook (stripeinvoiceid IS NOT NULL), derive the category from the contact's
-- subscription product type. Falls back to 'virtual_office' (default sub product).
--
-- Idempotent — only touches rows where category IS NULL.

WITH contact_sub AS (
    -- Most recent subscription per contact, with its derived category.
    SELECT DISTINCT ON (s.contact_id)
           s.contact_id,
           CASE p.tipo
               WHEN 'Aula'            THEN 'meeting_room'
               WHEN 'Mesa'            THEN 'coworking'
               WHEN 'Oficina Virtual' THEN 'virtual_office'
               ELSE
                   CASE
                       WHEN s.description ILIKE '%coworking%'
                         OR s.description ILIKE '%mesa%'
                         OR s.description ILIKE '%desk%' THEN 'coworking'
                       ELSE 'virtual_office'
                   END
           END AS category
      FROM beworking.subscriptions s
      LEFT JOIN beworking.productos p ON p.id = s.producto_id
     ORDER BY s.contact_id, s.created_at DESC
)
UPDATE beworking.facturas f
   SET category = cs.category
  FROM contact_sub cs
 WHERE f.category IS NULL
   AND f.stripeinvoiceid IS NOT NULL
   AND f.idcliente = cs.contact_id;
