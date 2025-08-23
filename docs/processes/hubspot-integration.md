
# HubSpot Integration — Detailed Flow (Diagram-Aligned)

## Purpose

Document the exact flow, payloads, failure modes, DB updates, and operational runbook for syncing Leads to HubSpot. Use this doc to implement/verify `HubspotService`, `LeadHubspotSyncListener`, and migration fields.

## Step-by-Step Flow (matches diagram)

1. **LeadCreatedEvent (AFTER_COMMIT)**
  - Published after DB commit.

2. **LeadHubspotSyncListener (@Async, AFTER_COMMIT)**
  - Receives event, loads Lead from DB.
  - Calls `HubspotService.syncLead(lead)`.

3. **HubspotService (syncLead)**
  - Builds JSON payload.
  - Sends POST to HubSpot API (`/crm/v3/objects/contacts`).
  - Handles 200/201 (success), 429/5xx (rate limit/transient), 4xx (permanent error).

4. **HubSpot API**
  - Receives POST, returns hubspotId on success.

5. **Database (leads table, hubspot_* fields)**
  - Listener updates lead: hubspot_id, hubspot_sync_status, hubspot_synced_at, hubspot_error, hubspot_sync_attempts, last_hubspot_attempt_at.

6. **Retry Queue / Scheduler (backoff + jitter)**
  - On 429/5xx, enqueue retry respecting Retry-After header.
  - Retry worker calls `syncLead` again.

7. **Resync Endpoint**
  - Manual resync via `POST /internal/leads/resync-failed` enqueues failed leads for retry.

## HTTP Call Example

- Endpoint: `POST https://api.hubapi.com/crm/v3/objects/contacts`
- Headers:
  - Authorization: Bearer `${HUBSPOT_TOKEN}`
  - Content-Type: application/json
- Body:

```json
{
  "properties": {
   "email": "ana@example.com",
   "firstname": "Ana",
   "phone": "+341234567",
   "message": "Interested in ..."
  }
}
```

## Idempotency & Dedupe

- Prefer upsert/search-by-email before create to avoid duplicates.
- If already synced, PATCH contact by hubspot_id.
- DB unique constraint on email recommended if business allows.

## Retry, Backoff, Rate-Limit

- 429/5xx: enqueue retry, respect Retry-After, use backoff + jitter.
- 4xx: mark as FAILED, log error, do not retry.
- Manual resync via endpoint.

## DB Fields (Flyway Migration)

- `hubspot_id` VARCHAR(255)
- `hubspot_sync_status` VARCHAR(32) DEFAULT 'PENDING'
- `hubspot_synced_at` TIMESTAMP
- `hubspot_error` TEXT
- `hubspot_sync_attempts` INT DEFAULT 0
- `last_hubspot_attempt_at` TIMESTAMP

## Error Payloads

- Store short message + code (e.g., "429 RATE_LIMIT Retry-After=10").
- Truncate long responses, keep full HTTP response in logs.

## Files to Inspect / Implement

- `LeadHubspotSyncListener.java`
- `HubspotService.java`
- `Lead.java`, `LeadRepository.java`
- Flyway migration SQL
- Tests: `LeadHubspotSyncListenerTest`, `HubspotServiceTest`

## Tests & QA

- Unit tests: WireMock for 200, 400, 429, 500.
- Listener test: persist lead, publish event, assert listener invoked.
- Integration test: full flow with local DB and mocked HubSpot.

## Monitoring & Runbook

- Metrics: sync attempts, failed leads, sync latency, alert on 4xx/429 spikes.
- Logs: request id, lead id, hubspot id, http status, error message.
- Manual re-sync: inspect failed leads, rotate token, requeue via endpoint.

## Security

- Store `HUBSPOT_TOKEN` in env/secret manager.
- Use TLS, mask token in logs.

## Minimal Java Contract

```java
class HubspotSyncResult { boolean success; String hubspotId; int status; String error; }
HubspotSyncResult syncLead(Lead lead) throws TransientHubspotException;
```

## Next Improvements

- Add durable job queue for retries.
- Background reconciliation job for failed leads.
- Safe backoff scheduler with Spring Scheduler.

---
Update this document as the process evolves and diagrams change.
