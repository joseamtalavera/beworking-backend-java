# BeWorking Backend (Spring Boot)

Spring Boot API for authentication, bookings, leads, mailroom, and integrations.

## Entry Point
- Main class: `src/main/java/com/beworking/JavaApplication.java`

## Run Locally (Dev)
```bash
./mvnw spring-boot:run
```
Or run the full stack via `../beworking-orchestration/docker-compose.yml`.

## Tests
```bash
./mvnw test
```

## Key Files
- Config: `src/main/resources/application*.properties`
- Security config: `src/main/java/com/beworking/auth/SecurityConfig.java`
- Leads + Turnstile: `src/main/java/com/beworking/leads/`
- Docker: `Dockerfile`, `Dockerfile.dev`

## Deployment
See `../beworking-orchestration/docs/deployment/ops-runbook.md`.
