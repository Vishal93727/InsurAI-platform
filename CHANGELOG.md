# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/). The portal's earlier release history
(0.x, before the merge) is kept in [docs/history/portal-changelog.md](docs/history/portal-changelog.md).

## [1.0.0] - unreleased

First release of the combined platform: the InsurAI portal and frontend, the ClaimPilot policy and claims core, and
the Fraud Detection Engine in one application. What was combined, mapped old to new, and what is still open:
[docs/MERGE-NOTES.md](docs/MERGE-NOTES.md).

### Added
- One Spring Boot application (`PlatformApplication`) hosting three modules under `com.insurai.platform`:
  `portal`, `core`, `fraudengine`, with a small shared kernel (`security`, `config`).
- **One identity.** Tokens issued at portal login are accepted by every module through a single resource-server
  chain; portal roles map onto core roles (ADMIN → MANAGER, HR → ASSESSOR, AGENT → AGENT).
- Deny-by-default authorisation covering all three modules.
- `ModuleBoundariesTest` (ArchUnit) enforcing that modules do not depend on each other.
- `PlatformSecurityConfigTest` and `CallerResolverTest` pinning the token contract and role mapping.
- One `docker-compose.yml` for the whole stack, an observability overlay, `scripts/up.sh` (generates secrets),
  and ported `demo.sh`, `bench.sh`, `load-test.sh`.
- `scripts/export-portal-schema.sh` to move the portal's schema under Flyway.
- Merged CI: backend `verify`, frontend build, and a full-stack run with the demo, a benchmark and an image scan.

### Changed
- Spring Boot 3.5.16 and PostgreSQL for everything; MySQL support dropped.
- Portal tables are prefixed `portal_`; fraud tables `fraud_`. Flyway migrations are `V1` core, `V2` Spring Batch,
  `V3` fraud engine.
- Kafka serialization and error handling are per module rather than global.
- Exception advice is scoped to each module's controllers.
- Environment variables are unified under `PLATFORM_*`.
- Logs are JSON outside the `local` profile and include trace ids; tracing is off unless enabled.
- Jacoco coverage gate retargeted to the renamed fraud packages.

### Security
- JWT signing secret and admin credentials are configuration, not source. They have no default outside the `local`
  profile. **Tokens and credentials from the original portal stop working; rotate any deployment that used them.**
- Closed endpoints that had neither a URL rule nor an in-method check: HR claim approve/reject, agent registration,
  agent availability and query endpoints, and the agent/HR/employee directory lists.
- The fraud engine's ingestion API now requires an ADMIN token; its dashboard, WebSocket and Swagger UI are closed
  unless explicitly enabled; its admin token has no default outside `local`.
- CORS origins are configuration (no `*.vercel.app` wildcard).
- Removed a second, placeholder JWT secret in `EmployeeService`.

### Removed
- ClaimPilot's mock OAuth identity provider (the platform issues and validates its own tokens).
- The portal's four path-scoped JWT filters, `allow-bean-definition-overriding`, the database-connection startup
  diagnostic, `GET /hello`, the duplicate JJWT 0.9.1 and unused `unirest-java` dependencies, and the frontend's
  `.env.production` that pointed at another deployment.
- The ledger-engine dashboard, alert rules and scrape job (a sibling service that is not part of this repository).

### Known gaps
See [README](README.md#known-limitations) and [MERGE-NOTES](docs/MERGE-NOTES.md#found-while-merging-not-fixed).
The merge has not yet been compiled against its dependencies or run end to end; see
[MERGE-NOTES](docs/MERGE-NOTES.md#verification-what-was-and-was-not-done).
