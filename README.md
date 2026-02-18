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

## Deployment

AWS ECS Fargate. See `../beworking-orchestration/docs/deployment/ops-runbook.md`.
