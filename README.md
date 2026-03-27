# BeWorking Backend

Spring Boot API powering authentication, bookings, invoicing, contacts, leads, mailroom, and integrations.

## Tech Stack

- Java 17, Spring Boot 3.4, Spring Security
- PostgreSQL 13 (via Flyway migrations)
- JWT authentication (httpOnly cookies)
- Apache PDFBox (invoice PDF generation)
- SpringDoc OpenAPI (Swagger)

## Development

```bash
# Standalone
./mvnw spring-boot:run

# Via docker-compose (recommended)
cd ../beworking-orchestration
docker-compose up beworking-backend db
```

Copy `.env.example` to set required variables (`JWT_SECRET`, mail config, etc.).

### Tests

```bash
./mvnw test
```

## Project Structure

```
src/main/java/com/beworking/
├── auth/            # JWT, login, registration, email service
├── bookings/        # Reservas, bloqueos, centros, productos
├── contacts/        # Contact profiles (clients)
├── cuentas/         # Multi-company billing accounts
├── invoices/        # Facturas, PDF generation, email
├── leads/           # Lead capture + HubSpot sync
├── mailroom/        # Document/package management
├── rooms/           # Room catalog (images, amenities)
├── payments/        # Stripe integration
├── config/          # CORS, async, web config
├── health/          # Health check endpoint
└── JavaApplication.java
```

### Database Migrations

Flyway migrations in `src/main/resources/db/migration/` (V1 through V11). Schema: `beworking`.

Run automatically on application startup. Strategy: `validate` in production.

## API

Swagger UI available at `/swagger-ui.html` when the service is running.

### Key endpoints

| Area | Base path | Auth |
|------|-----------|------|
| Auth | `/api/auth/*` | Public |
| Bookings | `/api/bookings/*` | Admin |
| Bloqueos | `/api/bloqueos/*` | Admin |
| Contacts | `/api/contacts/*` | Admin |
| Invoices | `/api/invoices/*` | Admin |
| Leads | `/api/leads/*` | Public |
| Mailroom | `/api/mailroom/*` | Authenticated |
| Public booking | `/api/public/*` | Public |
| Health | `/api/health` | Public |

## Scheduled Jobs (Cron)

| Job | Cron | Schedule | Description |
|-----|------|----------|-------------|
| **MonthlyInvoiceScheduler** | `0 0 5 28 * *` | 28th of each month at 05:00 AM | Finds all uninvoiced bookings (bloqueos) for the **next month**, groups by contact, creates DB invoice + Stripe invoice, sends status email to accounts@be-working.com |
| **LocalSubscriptionScheduler** | `0 0 1 1 * *` | 1st of each month at 01:00 AM | Creates Pendiente invoices for active `bank_transfer` subscriptions due that month. Respects `billing_interval` — skips quarterly (< 3 months) and annual (< 12 months) subscriptions not yet due |
| **DailyReconciliationScheduler** | `0 0 5 * * *` | Every day at 05:00 AM | Reconciles Stripe payments (GT + PT accounts) with DB invoices, sends status email to accounts@be-working.com |

### Invoice generation flows

- **Public bookings (be-spaces.com)**: Invoice created at booking time after Stripe payment succeeds (`BookingService.createPublicBooking`)
- **Admin bookings (dashboard)**: Invoice created in the payment step based on selected option (charge card, send Stripe invoice, invoice without Stripe, book only)
- **Bank transfer subscriptions**: Auto-invoiced by `LocalSubscriptionScheduler` on the 1st of each month
- **Stripe subscriptions**: Auto-charged by Stripe; webhook (`SubscriptionWebhookController`) creates the local DB invoice
- **Uninvoiced bookings**: Auto-invoiced by `MonthlyInvoiceScheduler` on the 28th for the next month

## Deployment

AWS ECS Fargate. See `../beworking-orchestration/docs/deployment/ops-runbook.md`.
