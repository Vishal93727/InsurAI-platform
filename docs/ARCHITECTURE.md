# Architecture

## Shape: a modular monolith

The platform is **one Spring Boot application with three bounded contexts** and a small shared kernel. The three
contexts were written as independent services; they were combined into one deployable because they share the
same infrastructure (PostgreSQL, Kafka, Redis, SMTP, object storage) and the same users, and because running
three JVMs, three databases and three login systems for one product is cost without benefit at this scale.

The boundaries are kept anyway. `ModuleBoundariesTest` (ArchUnit) fails the build if `portal`, `core` or
`fraudengine` imports another, or if the kernel imports a module. The only cross-package edges today are from
`portal` and `core` into `security`. If two modules ever need to talk, the route is an explicit interface in the
kernel, an event on Kafka, or the database, not an import. That keeps each module extractable as a service later.

```
com.insurai.platform
├── PlatformApplication        single entry point; scans everything below
├── security/                  kernel: token validation, the one security chain, problem+json for 401/403
├── config/                    kernel: Kafka producer defaults
├── portal/                    controller / service / repository / model   (layered, JPA)
├── core/                      <module>/{domain,application,adapter}       (hexagonal, JDBC)
└── fraudengine/               api / application / domain / infrastructure (hexagonal, JPA + Redis + Kafka)
```

Each module keeps the internal style it was written in; they are not rewritten to match each other.

## The modules

| | portal | core | fraudengine |
| --- | --- | --- | --- |
| Purpose | Employee-facing insurance operations with four roles | Policy administration and claims engine | Real-time transaction fraud decisions |
| Persistence | Spring Data JPA, `portal_*` tables | `JdbcClient` / `JdbcTemplate`, unprefixed tables | Spring Data JPA, `fraud_*` tables, plus Redis |
| Schema owner | Hibernate (`ddl-auto`), see [Data](#data-ownership) | Flyway `V1`, `V2` | Flyway `V3` |
| HTTP surface | `/auth`, `/admin`, `/hr`, `/agent`, `/employee`, `/notifications`, `/api/files` | `/v1/**` | `/api/v1/transactions`, `/api/v1/reports`, `/api/v1/admin/**`, `/ws`, `/dashboard.html` |
| Messaging | none | produces `claimpilot.policy-events`, `claimpilot.claim-events` (outbox relay), consumes both for e-mail | produces `transactions.incoming`, `transactions.flagged`, `.DLT`; consumes `transactions.incoming` and ledger transfer events |
| Background work | renewal jobs (02:00, 03:00) | outbox relay (every 0.5 s), fraud-graph rebuild, renewal batch | nightly fraud report (Spring Batch, 00:00) |
| External | Supabase S3 (policy documents), Cohere (chatbot), SMTP | S3-compatible store, SMTP | none |

Module-level detail: [portal](modules/portal.md), [core](modules/core.md), [fraud engine](modules/fraud-engine.md).

## Identity and authorisation

One credential works everywhere.

1. A user signs in through a portal endpoint. The portal checks the password (BCrypt) and issues an **HS256 JWT**:
   `sub` = e-mail, `role` = `ADMIN` | `HR` | `AGENT` | `EMPLOYEE`, `iss` = `insurai-platform`, `exp` = now + TTL.
2. Every request passes through **one** `SecurityFilterChain`. A standard Spring Security resource server
   validates the signature, expiry and issuer with a `JwtDecoder` built from the same secret, and turns the `role`
   claim into `ROLE_<ROLE>`.
3. URL rules authorise by role (table below), ending in `denyAll()`.
4. Inside `core`, a `CallerResolver` turns the token into a `Caller(subject, role)` for controller methods, with
   the precedence *staff table → platform role claim → customer*. A claim is honoured only when the token's issuer
   is the platform's, so a token from any other issuer can never grant itself staff rights. Mapping: ADMIN →
   MANAGER, HR → ASSESSOR, AGENT → AGENT.
5. Inside `portal`, controllers additionally check the role with `JwtUtil` (kept from the original as defence in
   depth).

| Rule | Routes |
| --- | --- |
| Public | `POST /auth/{login,register,forgot-password}`, `POST /auth/reset-password/*`, `POST /{admin,hr,agent}/login`, `GET /api/files/**`, `GET /uploads/**`, `/actuator/health/**`, `/actuator/prometheus`, `/error` |
| Any signed-in user | `/v1/**`, `GET /auth/employees`, `GET /employee/policies`, `GET /agent`, `GET /hr`, `GET /agent/available`, `GET /agent/availability/all`, `/notifications/user/**`, `/notifications/*/read` |
| ADMIN | `/admin/**`, `POST /agent/register`, `/api/v1/transactions/**`, `/api/v1/reports` |
| HR | `/hr/**` |
| AGENT | `/agent/**` |
| EMPLOYEE | `/employee/**` |
| HR or ADMIN | the rest of `/notifications/**` |
| `X-Admin-Token` header | `/api/v1/admin/**` (the fraud engine's own guard; service-to-service) |
| Development only (`platform.security.open-dev-endpoints`) | `/dashboard.html`, `/ws/**`, Swagger UI |
| Everything else | denied |

Two details worth knowing:

- **Stale tokens on public routes.** The frontend attaches whatever token is in local storage to *every* request,
  including login. A resource server rejects an expired bearer token with 401 *before* authorisation, even on a
  `permitAll` route, which would lock a user with a stale token out of logging in. The bearer-token resolver
  therefore ignores the `Authorization` header on the public entry points.
- **`/error` must stay open.** Servlet error dispatches are authorised too. Without an explicit rule an uncaught
  exception in any controller would surface as 401/403 instead of its real status.

Failures are returned as `application/problem+json` with a stable `code`, matching what `core` already produced.

## Data ownership

One PostgreSQL database. Ownership is by table prefix so it is visible in `psql`:

| Prefix | Owner | Managed by | Tables |
| --- | --- | --- | --- |
| *(none)* | core | Flyway `V1__core.sql` | `staff`, `product_version`, `approval_limit`, `quote`, `policy`, `policy_version`, `endorsement`, `payment`, `idempotency_record`, `claim`, `claim_party`, `claim_document`, `claim_transition`, `claim_approval`, `settlement`, `renewal`, `renewal_reminder`, `outbox`, `processed_event` |
| `batch_` | Spring Batch | Flyway `V2__spring_batch.sql` | the six `BATCH_*` tables |
| `fraud_` | fraudengine | Flyway `V3__fraud_engine.sql` | `fraud_audit_log`, `fraud_account_limits`, `fraud_reports` |
| `portal_` | portal | Hibernate | `portal_employees`, `portal_hrs`, `portal_agents`, `portal_admins`, `portal_claims` (+ `portal_claim_documents`), `portal_policies`, `portal_enrollments`, `portal_dependents`, `portal_reimbursements`, `portal_employee_queries`, `portal_notifications`, `portal_audit_logs`, `portal_agent_availability`, `portal_renewal_config`, `portal_renewal_history` |

The prefixes exist because the originals used near-identical names for different things (`claim` vs `claims`,
`policy` vs `policies`, `audit_log` vs `audit_logs`), which is a trap for anyone operating the database. Core's
tables stay unprefixed because its SQL is embedded in many files.

The portal's schema is still created by Hibernate. That is how the portal always worked, and a hand-written
migration could not be checked here against what Hibernate expects column by column. The route to a fully
Flyway-owned schema is `scripts/export-portal-schema.sh`, which dumps the schema Hibernate itself produced into
`V4__portal_schema.sql`; then set `PLATFORM_DDL_AUTO=validate`.

The three persistence styles share one `DataSource`. Because JPA is present, the transaction manager is
`JpaTransactionManager`, which also binds the JDBC connection, so core's `JdbcClient` calls and `@Transactional`
boundaries behave as they did with a plain `DataSourceTransactionManager`.

## Messaging

| Topic | Producer | Consumer | Payload |
| --- | --- | --- | --- |
| `claimpilot.policy-events`, `claimpilot.claim-events` | core outbox relay | core notification listener (group `claimpilot-notifications`) | JSON envelope as a **String** |
| `transactions.incoming` (3 partitions) | fraud ingestion API | fraud consumer (group `fraud-engine`, manual ack) | `TransactionRequest` as **JSON** |
| `transactions.flagged` | fraud pipeline on BLOCK | external subscribers | `FraudDecision` as JSON |
| `transactions.incoming.DLT` | fraud error handler | operators | the failed record |

Two modules produce and consume on one broker connection but with different conventions, which Spring Boot's
auto-configuration cannot express globally (it creates exactly one `KafkaTemplate`, one consumer factory, one
default listener factory, and backs off as soon as you declare your own). So:

- Global `spring.kafka.*` contains only what both can share (bootstrap servers, `acks=all`, idempotence, earliest
  offsets, manual commit off, observation on). **No serializers, no ack mode, no error handler.**
- `KafkaDefaultsConfig` declares the String/String `KafkaTemplate` that core's outbox relay injects.
- The fraud engine declares its own **named** beans: `fraudKafkaTemplate` (JSON producer),
  `fraudListenerContainerFactory` and `ledgerTransferListenerContainerFactory` (manual ack, error handler), and
  qualifies every injection point.
- The dead-letter error handler is deliberately **not a bean**. Boot attaches a lone `CommonErrorHandler` bean to
  the default listener factory, which would have dead-lettered core's notification records into fraud topics.
- The fraud consumer ignores the `__TypeId__` header and always deserialises into `TransactionRequest`, so the
  wire format does not carry (or depend on) a Java class name.

Redis (fraud engine only): `fraud:blacklist:merchants` (set), `fraud:locations:high_risk` (set),
`fraud:velocity:{accountId}` (counter with TTL).

## Cross-cutting decisions made by the merge

| Problem | Decision |
| --- | --- |
| Nine classes share a simple name across modules (`WebConfig` ×3, `SecurityConfig` ×2, `ClaimService`, `PolicyController`, ...), so default bean names collide and startup fails | Component scan uses `FullyQualifiedAnnotationBeanNameGenerator`, declared on an explicit `@ComponentScan` so `@SpringBootTest` and test slices behave like the real app. The original portal papered over this with `allow-bean-definition-overriding=true`; that flag is gone. |
| `@RestControllerAdvice` is global, and both `core` and `fraudengine` have a catch-all `@ExceptionHandler(Exception.class)` (core's also extends `ResponseEntityExceptionHandler`) | Each advice is scoped to its own module's packages, so core's handler no longer rewrites the portal's and fraud engine's error responses. |
| Three entry points, three `@Enable*` sets | One `PlatformApplication` with `@EnableScheduling` and `@ConfigurationPropertiesScan` once. |
| Core's `WebMvcConfigurer`/argument-resolver would be pulled into the fraud engine's `@WebMvcTest` slices and fail (no `RoleLookup`) | Registered through a `@Bean WebMvcConfigurer` in a `@Configuration`, which slices skip. |
| Global properties that mean different things per module | Resolved per module (Kafka above); multipart enabled for the portal's uploads; `open-in-view` kept on for the portal's lazy JPA reads; the portal's `static-locations` override removed so the fraud dashboard under `classpath:/static` is served. |
| Logging: the fraud engine forced JSON everywhere | `local` profile uses Boot's readable console format; every other profile emits JSON with `transactionId`, `traceId` and `spanId` from the MDC. |

## Observability

One Prometheus endpoint (`/actuator/prometheus`) tagged `application=insurai-platform`. Business metrics keep
their original names (`claimpilot_*`, `fraud_*`). Histogram buckets are enabled for `http.server.requests` and
`fraud.pipeline.duration` (without buckets the timers export only count and sum and `histogram_quantile` matches
nothing). Tracing is OpenTelemetry over OTLP and is **off by default** (`PLATFORM_TRACE_SAMPLE=0`) so no
collector is needed; the monitoring overlay turns it on. The Grafana dashboard for the fraud engine is
provisioned; the dashboard and alert rules that belonged to the original project's sibling ledger service were not
carried over, since that service is not part of this repository.

## Decisions carried over from the originals

These still hold and are worth keeping in mind when changing the code.

**Fraud engine**

| Decision | Rationale |
| --- | --- |
| Kafka between API and pipeline | Ingestion returns `202` quickly even when the pipeline is slow, and absorbs spikes. |
| Redis `INCR` + TTL for velocity | O(1) atomic counter; the TTL expires the window with no cleanup job. `SET NX EX` sets the TTL exactly once on first write, avoiding a sliding-expiry race. |
| Idempotency on `transactionId` | Kafka is at-least-once. The consumer checks `fraud_audit_log` first, so a redelivery is a no-op. |
| Weighted scorer with externalised weights | Weights and threshold live in config; the business can tune them without a deploy. |
| Spring Batch for the nightly report | Chunking, retry, skip and restart semantics rather than a hand-rolled cron. |
| Hexagonal layout | The pipeline has no framework dependency, so rules, velocity and scoring are unit-tested in milliseconds without containers. |

**Core**

| Decision | Rationale |
| --- | --- |
| Quote stores its product version and a step-by-step premium breakdown | A quote can always be re-explained later. |
| `Idempotency-Key` on bind | A retried bind returns the stored response and never charges twice. |
| Bitemporal policy versions (`validAt`, `asOf`) | Answers both "what cover applies on this date" and "what did we believe on that date". |
| Payouts keyed by claim | A settlement cannot be paid twice. |
| Party identifiers stored only as HMACs; union-find graph for shared identifiers | Fraud linking without holding raw phone or bank numbers. |
| Transactional outbox + processed-event table | State change and event are atomic; consumers skip duplicates. |
| ArchUnit: domain free of Spring/JDBC, modules cannot reach into each other's adapters | Keeps the hexagonal structure honest as the code grows. |

## Where deeper integration would go

The modules are co-located, not yet fused. The natural seams, none of which require breaking a boundary:

- **Claim payouts into the fraud pipeline.** Core's `claimpilot.claim-events` already carries settlements; a small
  adapter could map a payout to a `TransactionRequest` on `transactions.incoming`.
- **One claim workflow.** The portal's HR-approval flow and core's rule-based triage overlap. Routing portal
  claims through core's triage is the largest functional change left and needs a product decision.
- **A UI for core and fraud.** Their APIs are complete; the React app covers only the portal.
- **Ownership checks in the portal** (notifications, file access) and moving the portal's schema into Flyway.
