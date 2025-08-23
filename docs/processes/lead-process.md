
# Lead Process (Step-by-Step)

This document describes the backend process for handling leads in the BeWorking system.

## Step-by-Step Flow (Component → Component)

### 1) Client → LeadController

- The client issues `POST /api/leads` with a JSON LeadRequest (example: `{name, email, phone, source}`).
- LeadController receives the HTTP request and maps it to the DTO.

### 2) LeadController → Validation

- Controller validates the DTO (usually via `@Valid`).
- If validation fails → Controller returns 400/422 and stops. No DB write, no event published.

### 3) Validation → (optional) LeadService

- If you use a LeadService, the controller calls it to apply business rules: dedupe by email, normalize/enrich fields, etc.
- If dedupe finds an existing lead, service decides whether to update/return conflict/skip.

### 4) Service/Controller → Map DTO → Lead entity

- The DTO is mapped to a JPA entity `Lead`.
- Prepare the entity for persistence (set `createdAt`, `source`, default sync status `PENDING`, etc.).

### 5) Persist: LeadRepository.save(lead)

- Save the entity to the `leads` table.
- The DB now contains a new lead row. (Check `leads` table for a new record and generated id.)

### 6) After save → Publish LeadCreatedEvent (reliable)

- Still inside the same transaction, code calls `applicationEventPublisher.publishEvent(new LeadCreatedEvent(lead.getId()))`.
- Important: listeners should use `@TransactionalEventListener(phase = AFTER_COMMIT)` so they only run after the DB commit (avoids handling rolled-back saves).

### 7) LeadCreatedEvent → LeadEmailListener (async)

- The LeadEmailListener receives the event (executed after commit).
- It is typically annotated `@Async` so it runs in a separate thread and won't block the request.
- It builds email templates (via LeadEmailService) and calls EmailService to send user/internal notification emails.
- Success path: emails sent; failure: log and optionally retry/send alert.

### 8) LeadCreatedEvent → LeadHubspotSyncListener (async)

- LeadHubspotSyncListener also receives the event (`AFTER_COMMIT` + `@Async`).
- It loads the Lead (or receives minimal snapshot) and calls `HubspotService.syncLead(lead)`.

### 9) HubspotService.syncLead(lead) → HubSpot API

- HubspotService builds a JSON payload mapping lead fields to HubSpot properties (email, firstname, phone, company, source).
- It makes an HTTP POST to `https://api.hubapi.com/crm/v3/objects/contacts` with header `Authorization: Bearer {HUBSPOT_TOKEN}`.
- Handle timeouts and response codes.

### 10) HubSpot response → HubspotSyncResult

- On success (201/200) the response contains a HubSpot id. HubspotService returns a HubspotSyncResult (`success=true`, `hubspotId`, maybe response body).
- On error:
   - 429: rate limit — service should read Retry-After and retry with exponential backoff.
   - 5xx: transient — retry up to N attempts with backoff.
   - 4xx: permanent client error — record error and stop.

### 11) LeadHubspotSyncListener updates Lead and saves

- The listener takes the HubspotSyncResult and:
   - sets `lead.hubspot_id` (String) if provided
   - sets `lead.hubspot_sync_status` (`PENDING` → `SYNCED` or `FAILED`)
   - sets `lead.hubspot_synced_at` to `Instant.now()` on success
   - increments `hubspot_sync_attempts` (Integer) and sets `last_hubspot_attempt_at` (Instant)
   - sets `hubspot_error` (TEXT) when errors occur
- Then it saves the updated Lead via `LeadRepository.save(lead)`.

### 12) Observability and checks

- To verify flow: create a lead and:
   - Confirm the DB row exists in `leads` table.
   - Check application logs for event publishing and listener execution (look for AFTER_COMMIT listener logs).
   - Check email logs (JavaMailSender) for sent messages.
   - Check HubSpot logs or API responses. For failures look at `hubspot_error` column and `hubspot_sync_attempts`.
   - Watch for Retry-After handling on 429s.

---

## Files & fields to inspect quickly

- Controller / DTO: `LeadController.java`, `LeadRequest.java`
- Optional service: `LeadService.java`
- Entity + repo: `Lead.java` (fields below), `LeadRepository.java`
- Event: `LeadCreatedEvent.java`
- Listeners: `LeadEmailListener.java`, `LeadHubspotSyncListener.java`
- Integration: `HubspotService.java`, HubspotSyncResult (class or inner)
- DB fields added by Flyway:
   - `hubspot_id` VARCHAR(255)
   - `hubspot_sync_status` VARCHAR(32) (enum PENDING, SYNCED, FAILED)
   - `hubspot_synced_at` TIMESTAMP
   - `hubspot_error` TEXT
   - `hubspot_sync_attempts` INTEGER
   - `last_hubspot_attempt_at` TIMESTAMP

---

## Edge cases & recommendations

- Ensure listeners use `TransactionPhase.AFTER_COMMIT` and `@Async` to avoid running inside the request transaction.
- Keep events small — publish the lead id rather than the full entity, and reload inside listeners if needed.
- Redaction: never log HUBSPOT_TOKEN or full request bodies with secrets.
- Idempotency: if HubSpot may create duplicates, implement upsert/search-by-email before creating, or use `hubspot_id` to PATCH on retries.
- Tests: unit test each listener with mocked HubspotService and an integration test that creates a lead and verifies listener side-effects.
