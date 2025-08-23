# User Registration Flow — Detailed (Diagram-Aligned)

## Purpose
Document the step-by-step backend registration process, matching the provided diagram. Covers validation, persistence, event publishing, email verification, onboarding, and error handling.

## Step-by-Step Flow

1. **User / Client**
   - Sends `POST /api/register` with registration data (name, email, password, phone).

2. **RegistrationController**
   - Receives request, parses body into `RegistrationRequest` DTO.
   - Validates DTO (`@Valid`): checks name, email, password, phone.
   - On validation error, returns error response (400/422) with validation errors.
   - If valid, calls `RegistrationService`.

3. **RegistrationService (business rules)**
   - Hashes password (BCrypt).
   - Assigns default roles.
   - Maps DTO to `User` entity.
   - Persists user to database (`users` table).

4. **Password Hashing & Role Assignment**
   - Hash password securely.
   - Assign default roles to user.

5. **User Entity & Database**
   - Save user entity to `users` table.

6. **Verification Token Table**
   - Generate verification token (with expiry).
   - Store token in `verification_token` table (`user_id`, `token`, `expires_at`).

7. **Publish UserRegisteredEvent**
   - After commit, publish `UserRegisteredEvent` (async).

8. **Email Verification Listener**
   - Receives event, sends verification email with token to user (async).

9. **Welcome Flow Listener**
   - Receives event, assigns initial onboarding tasks to user (async).

## Error Handling
- Validation errors: return 400/422 with details.
- All errors logged with request context.

## Database Tables
- `users`: stores user info, hashed password, roles.
- `verification_token`: stores verification tokens, expiry.

## Event Listeners
- `EmailVerificationListener`: sends verification email.
- `WelcomeFlowListener`: assigns onboarding tasks.

## Security
- Passwords hashed with BCrypt.
- Tokens stored securely, expire after set time.

## Monitoring & Runbook
- Monitor registration errors, email delivery failures.
- Manual token resend: endpoint to resend verification email if needed.

## Minimal Java Contract (pseudocode)
```java
class RegistrationRequest { String name; String email; String password; String phone; }
class User { ... }
class VerificationToken { Long userId; String token; Instant expiresAt; }
void register(RegistrationRequest req);
void sendVerificationEmail(User user, String token);
void assignOnboardingTasks(User user);
```

---
Update this document as the process or diagram evolves.
