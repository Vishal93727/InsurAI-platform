# Contributing

## Workflow

1. Branch from `main`: `feature/<short-desc>` or `fix/<short-desc>`.
2. Write the failing test first where you can, then the minimum code to pass it.
3. Run the gate before pushing:
   ```bash
   cd backend
   mvn test        # unit + architecture tests, no Docker
   mvn verify      # + integration tests (Docker required)
   cd ../frontend && npm run build
   ```
4. Open a PR. CI must be green.

## Keep the modules apart

`portal`, `core` and `fraudengine` are separate bounded contexts and must not import each other;
`ModuleBoundariesTest` fails the build if they do. Shared code goes in `security` or `config` (the kernel, which
must not import the modules). If two modules need to cooperate, use the database, a Kafka event, or an interface
in the kernel.

Each module keeps its own internal style (portal: layered; core and fraudengine: hexagonal). Core's domain and
application layers must stay free of Spring and JDBC (`core`'s `ArchitectureTest`); the fraud pipeline under
`fraudengine/domain` has no framework dependencies either.

## Conventions

- **Commits:** Conventional Commits, `feat(scope): ...`, `fix(scope): ...`, `test(...)`, `chore(...)`.
- **New HTTP routes** are closed until you add them to `PlatformSecurityConfig`; add a test or an entry to the
  access table in `docs/ARCHITECTURE.md` with it.
- **Secrets** never get a default outside `application-local.yml`, and never go in source.
- **Database:** core and fraud tables change through Flyway migrations (next version is `V4`); never edit a
  migration that has been applied. Name new tables with their module prefix (`portal_`, `fraud_`).
- **Kafka:** a module that needs different serialization declares its own named, qualified beans (see
  `fraudengine/config/KafkaConfig`); do not put serializers or ack modes in global `spring.kafka.*`.
- **Exception advice** must be scoped to the module's packages (`@RestControllerAdvice(basePackages = ...)`).
- **Redis keys:** `fraud:{entity}:{id}`. **Errors:** RFC 9457 problem JSON with a stable `code`.
- **Logging:** SLF4J; propagate `transactionId` via MDC.
- **Renaming packages** also means updating the fully-qualified class patterns in the Jacoco gate in `pom.xml`,
  or the gate silently matches nothing.

## Tests

- Unit: JUnit 5 and Mockito (or plain objects), no infrastructure.
- Integration (`*IT`): Testcontainers (Postgres, Redis, Kafka, Mailpit, S3). Because every module loads in one
  context, an integration test needs all of them reachable; `core/support/ItBase` shows how.
- Fraud pipeline coverage gate: 85% line coverage on `fraudengine.domain.pipeline` and the two orchestration
  services.
