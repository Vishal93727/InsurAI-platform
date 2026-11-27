# Merge notes

How three separate projects became this one, what changed along the way, and what is still open.

| Origin | Was | Became |
| --- | --- | --- |
| InsurAI (backend + frontend) | Spring Boot 3.5.5, React, MySQL, JPA | `portal` module and `frontend/` |
| ClaimPilot | Spring Boot 3.5.16, PostgreSQL, Kafka, S3, JDBC | `core` module |
| Fraud Detection Engine | Spring Boot 3.3.5, PostgreSQL, Redis, Kafka, JPA | `fraudengine` module |

Java: 321 source files (82 + 153 + 86) in, 319 out (275 main, 44 test): 12 removed, 10 added.

- **Removed (12):** the three entry points (`InsuraiBackendApplication`, `Main`, `FraudEngineApplication`); the four
  path-scoped portal JWT filters; the portal's and core's `SecurityConfig`; core's `WebConfig` (replaced by
  `CoreWebConfig`); the portal's `DatabaseConnectionTest` diagnostic; and the portal's empty context-load test.
- **Added (10):** `PlatformApplication`; the shared kernel (`PlatformSecurityProperties`,
  `PlatformSecurityConfig`, `ProblemResponses`, `KafkaDefaultsConfig`); the fraud engine's `KafkaBeans`;
  `CoreWebConfig`; and three tests (`PlatformSecurityConfigTest`, `CallerResolverTest`, `ModuleBoundariesTest`).
- Everything else is the original code with its package renamed, plus the targeted edits listed below.

## Maps

### Java packages

| Original | Now |
| --- | --- |
| `com.insurai.insurai_backend` | `com.insurai.platform.portal` |
| `com.claimpilot` | `com.insurai.platform.core` |
| `com.fraudengine` | `com.insurai.platform.fraudengine` |

### Database tables

| Original | Now |
| --- | --- |
| InsurAI: `claims`, `policies`, `employees`, ... (15 tables, plus `claim_documents`) | `portal_claims`, `portal_policies`, `portal_employees`, ... |
| ClaimPilot: `policy`, `claim`, `outbox`, ... | unchanged |
| Fraud: `audit_log`, `account_limits`, `fraud_reports` | `fraud_audit_log`, `fraud_account_limits`, `fraud_reports` |
| Flyway: ClaimPilot `V1`, `V2`; Fraud `V1`, `V2` (both created the same Spring Batch tables) | `V1__core`, `V2__spring_batch`, `V3__fraud_engine` |

### Environment variables

| Original | Now |
| --- | --- |
| ClaimPilot `CLAIMPILOT_*` (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA`, `S3_*`, `PARTY_HMAC_KEY`, `BOOTSTRAP_MANAGERS`, `QUOTES_*`, `RENEWAL_*`, ...) | `PLATFORM_*` with the same suffix (`DB_URL`, `DB_USER`, `DB_PASSWORD` are now shared by all modules) |
| ClaimPilot `CLAIMPILOT_SMTP_HOST/PORT`, `CLAIMPILOT_ISSUER`, `CLAIMPILOT_PORT` | `PLATFORM_SMTP_*` (one mail sender for both mailing modules), *(issuer removed, see below)*, `PORT` |
| Fraud `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST/PORT`, `KAFKA_BOOTSTRAP`, `ADMIN_TOKEN` | `PLATFORM_DB_*`, `PLATFORM_REDIS_*`, `PLATFORM_KAFKA`, `FRAUD_ADMIN_TOKEN` |
| Fraud `FRAUD_TRACE_SAMPLE`, `FRAUD_OTLP_ENDPOINT` | `PLATFORM_TRACE_SAMPLE`, `PLATFORM_OTLP_ENDPOINT` |
| Portal `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_DRIVER`, `HIBERNATE_DIALECT` | `PLATFORM_DB_*` (PostgreSQL only; driver and dialect fixed) |
| Portal `MAIL_*`, `SPRING_MAIL_*` | `PLATFORM_SMTP_*` |
| Portal `SUPABASE_*`, `COHERE_API_KEY`, `PORT` | unchanged |
| *(new)* | `PLATFORM_JWT_SECRET`, `PLATFORM_JWT_TTL`, `PLATFORM_ADMIN_EMAIL`, `PLATFORM_ADMIN_PASSWORD`, `PLATFORM_CORS_ORIGINS`, `PLATFORM_OPEN_DEV_ENDPOINTS`, `PLATFORM_DDL_AUTO`, `FRAUD_CONSUMER_GROUP` |

Property keys (`claimpilot.*`, `fraud.*`) were left as they are; only the environment names changed.

### Ports and URLs

One backend on `8080` (was 8080 / 8080 / 8940), the frontend on `5173`. The HTTP routes of all three modules are
unchanged. They do not overlap (`/auth ... /notifications` for the portal, `/v1` for core, `/api/v1` for the fraud
engine), so no route was renamed.

## Collisions found and how each was resolved

Merging independently written Spring applications fails in specific, mostly silent ways. Each of these was found
by analysis of the three trees, not by observing a failure; the reasoning is in
[ARCHITECTURE.md](ARCHITECTURE.md#cross-cutting-decisions-made-by-the-merge).

| Collision | Effect if left alone | Resolution |
| --- | --- | --- |
| 9 stereotype classes with the same simple name (`WebConfig` ×3, `SecurityConfig` ×2, `ClaimService`, `ClaimController`, `PolicyController`, `FraudService`, `NotificationService`, `RenewalService`, `RenewalController`) | `ConflictingBeanDefinitionException` at startup | Fully-qualified bean names via the component scan. The portal's `allow-bean-definition-overriding=true` removed. |
| Global `spring.kafka.*` with different serializers, ack modes and a global error handler | Core's String payloads JSON-encoded (or fraud's objects failing to serialise); core's notifications dead-lettered into fraud topics | Per-module Kafka beans, no global serializers, error handler not a bean. |
| Two `@RestControllerAdvice` with catch-all handlers | Core's handler rewriting the portal's and fraud engine's errors | Each scoped to its own packages. |
| Core's security chain ended in `denyAll()` | Portal and fraud routes unreachable | One chain with explicit rules for all modules. |
| Fraud's `@WebMvcTest` slices | Would pull in core's `RoleLookup`-dependent resolver and Spring Security defaults | Resolver registered via `@Bean`; slices run with `addFilters = false`. |
| `spring.jpa.hibernate.ddl-auto`: `update` (portal) vs `validate` (fraud) | `validate` would reject the portal's tables | `update`, overridable; see [Database](#database). |
| `spring.web.resources.static-locations=file:./uploads/` (portal) | Replaces Boot's defaults, so the fraud dashboard under `classpath:/static` would 404 | Removed. The portal's `/uploads/**` handler already serves that directory. |
| `spring.servlet.multipart.enabled=false` (core) vs uploads needed by the portal | Portal claim uploads fail | Enabled, 10 MB. |
| Duplicate Spring Batch schema in two Flyway sets | Migration conflict | One `V2`. The two files differed only in formatting. |
| Core's ITs start only Postgres and Kafka, but the merged context includes the fraud seeder (Redis) | Every core integration test fails at startup | Redis container added to core's test infrastructure. |
| Jacoco coverage gate matched `com.fraudengine.*` class patterns | After the rename it would match nothing and pass vacuously | Patterns updated. |
| Two JJWT jars (0.9.1 and 0.11.5) with overlapping packages; `unirest-java` and the MySQL driver unused/irrelevant | Classpath clash; dead weight | 0.11.5 only; `unirest`, MySQL removed. |

## Behaviour changes from the originals

### Security fixes (found while merging)

| Finding in the original | Fix |
| --- | --- |
| **JWT signing secret hard-coded** in `JwtUtil` (`.env.example` advertised `JWT_SECRET`, but the code ignored it). Anyone who could read the source could mint an admin token for any deployment. | Secret comes from `PLATFORM_JWT_SECRET`, at least 32 characters, no default outside the `local` profile. |
| **Admin credentials hard-coded** in `AdminService` (a fixed e-mail and password, public in the original repository) and compared in plaintext. | From `PLATFORM_ADMIN_EMAIL` / `PLATFORM_ADMIN_PASSWORD`, compared with BCrypt, no default outside `local`. |
| `SecurityConfig` made `/admin/**`, `/hr/**`, `/agent/**` `permitAll()` ahead of the role-restricted rules, so those rules could never match. Some endpoints were still protected by in-method checks; others had neither: `POST /hr/claims/approve/{id}` and `/reject/{id}`, `POST /agent/register`, the agent availability and query endpoints, and the `GET /agent`, `GET /hr`, `GET /auth/employees` lists. | Role-based URL rules, deny by default. The in-method checks remain. |
| `EmployeeService.isEmployee` / `getEmployeeFromToken` verified tokens against a separate, too-short placeholder secret and could only ever fail. Nothing called them. | They use `JwtUtil` now. |
| CORS allowed the author's own deployment and a `https://*.vercel.app` wildcard, with credentials, so any site hosted on that platform could call the API. | Origins come from `PLATFORM_CORS_ORIGINS`; default is localhost only. |
| The frontend's `.env.production` pointed a production build at the original author's deployed backend. | Removed; the API URL is a build argument. |
| The fraud engine's ingestion API, WebSocket and Swagger were unauthenticated; its admin endpoints used a default token. | Ingestion and reports require an ADMIN token; dashboard, WebSocket and Swagger are closed unless `open-dev-endpoints`; `FRAUD_ADMIN_TOKEN` has no default outside `local`. |

**Existing tokens and credentials stop working.** Anything signed with the old hard-coded secret, and the old admin
login, is rejected. Rotate any real deployment that used them: the secret and admin password were public in the
source.

### Other behaviour changes

- **ClaimPilot no longer needs an identity provider.** Its mock OAuth server is gone; it validates the portal's
  tokens. The standard resource-server mechanism is still used, but only one issuer (the platform) is configured.
  Pointing core at an external IdP would mean replacing the `JwtDecoder` bean, which would also replace portal
  login, so it is not offered as a switch.
- **Core's role resolution gained a fallback** (staff table → platform role claim → customer). Tokens with no role
  claim behave exactly as before, which is what its tests use.
- **PostgreSQL only.** MySQL support is dropped. The portal's Hibernate dialect was selected by an environment
  variable defaulting to MySQL; it is now fixed to PostgreSQL.
- **Unknown paths under `/v1`** should now fall through to Boot's default error body instead of core's problem
  JSON, because core's error advice is scoped to its own controllers (by design of the scoping; not observed).
  Errors raised by core's own controllers are unchanged.
- **Logs are JSON outside the `local` profile** (the fraud engine forced JSON everywhere) and now include
  `traceId` and `spanId` (its logback config whitelisted only `transactionId`).
- **Tracing is off by default** (sample rate 0). The fraud engine defaulted to 1.0 and logged exporter errors
  without a collector.
- **Spring Boot 3.3.5 → 3.5.16** for the fraud engine and **3.5.5 → 3.5.16** for the portal; springdoc 2.6.0 →
  2.8.9 (the version line that supports Boot 3.5); AWS SDK pinned once, to ClaimPilot's version.
- **Fraud consumer ignores the `__TypeId__` header**, so messages do not depend on the Java package name. The
  consumer group is now `fraud.kafka.consumer-group` (default unchanged, `fraud-engine`).
- **Removed:** the portal's `DatabaseConnectionTest` (a startup diagnostic that told you to check MySQL),
  `GET /hello`, and the ledger-engine dashboard, alert rules and scrape job (that service is not in this
  repository; the `LedgerTransferListener` that consumes its events is kept).

## Verification: what was and was not done

The merge was prepared in an environment with **no access to Maven Central**, so the Java code was never
compiled against its dependencies and no test was executed. That is the main caveat on everything above.
What *was* checked:

| Check | Result |
| --- | --- |
| Every `.java` file (319) parsed with the JDK 21 compiler front-end; a deliberate syntax error is caught (negative control) | 0 syntax errors |
| Full symbol resolution with no dependencies on the classpath, filtered to errors involving this project's own classes and packages | no unresolved internal reference (4,646 other errors, all missing third-party jars, as expected) |
| Every package declaration matches its directory; no references to the three old package names remain | pass |
| Call sites of every signature that was changed (`CallerResolver`, `KafkaConfig`, `JwtUtil`, ...) | consistent |
| `@Bean` method names unique across all modules; JPA table names unique; portal tables absent from Flyway, fraud entity tables all present in it; Flyway versions contiguous | pass |
| No hard-coded secrets or stale identifiers from the originals in the tree | pass |
| `application.yml`, `application-local.yml`, `pom.xml`, both compose files, the Prometheus/Tempo/Grafana files, the CI and Dependabot files parse | pass |
| All 83 distinct API calls the frontend makes, evaluated against the new URL rules for the role of the calling page | every call that has a real backend route is allowed |
| Frontend: `npm ci` and `vite build` | succeed; the API base URL is injected, an override replaces it, and no other hard-coded backend URL is in the bundle |
| The bash-minted JWT in `scripts/lib.sh` checked against an independent HS256 implementation | signature and claims valid |
| All scripts pass `bash -n` | pass |

**Not run, and the first things to run:**

1. `cd backend && mvn verify`: compile, unit, architecture and integration tests. Expect to fix small things;
   nothing here has seen a compiler with the dependencies present.
2. `./scripts/up.sh && ./scripts/demo.sh`: image builds, context startup, Flyway, and the end-to-end flow
   including the cross-module token and the Kafka fraud pipeline.
3. Click through the four portal dashboards once. The access rules were checked statically against the
   frontend's calls, not exercised.

The riskiest single assumption is that the portal's JJWT tokens validate under Spring's Nimbus decoder.
`PlatformSecurityConfigTest` exists to prove exactly that and will fail loudly if it is wrong.

## Database

The portal's tables are still produced by Hibernate (`PLATFORM_DDL_AUTO=update`), because that is how the
original worked and the exact schema Hibernate expects cannot be reproduced reliably by hand. To move everything
under Flyway:

```bash
scripts/up.sh                              # Hibernate creates the portal_* tables
scripts/export-portal-schema.sh            # dumps them to db/migration/V4__portal_schema.sql
# review V4, then run with PLATFORM_DDL_AUTO=validate
```

An existing database needs `flyway baseline` at version 4 first. **There is no data migration** from the three
original databases (one MySQL, two PostgreSQL, with different table names); this is a new schema.

## Found while merging, not fixed

Carried over from the originals; each is confirmed in the code.

- `GET /notifications/user/{id}` checks that the caller is signed in but not that `{id}` is theirs.
- Claim documents under `/api/files/**` and `/uploads/**` are served without authentication.
- The frontend calls three endpoints that do not exist in the backend, so those screens already failed:
  `GET /employee/enrollments/{id}`, `GET /employees` (HR fraud page), and `POST /hr/reimbursements/initiate`
  (the backend has `POST /hr/claims/{claimId}/initiate-reimbursement`).
- 99 ESLint errors in the frontend (mostly unused variables). CI reports lint without failing on it.
- The portal and core have separate claim workflows and notification code paths.
- Each module's own known-limitations list still applies: [core](modules/core.md#limitations),
  [fraud engine](modules/fraud-engine.md#known-limitations--trade-offs).
