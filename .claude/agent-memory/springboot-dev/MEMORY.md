# Spring Boot Developer Memory - Household Manager Project

## Project Overview
- **Project**: Household Manager Backend
- **Base Package**: `com.household.manager`
- **Build Tool**: Maven
- **Java Version**: 21
- **Spring Boot Version**: 3.4.1
- **Database**: MariaDB (localhost:3306, default DB: household_manager)

## Architecture Patterns

### Package Structure
```
com.household.manager/
├── config/          - Application configuration (Web, JPA)
├── controller/      - REST controllers with /v1/ versioning
├── dto/            - Data Transfer Objects, @JsonInclude(NON_NULL)
├── exception/      - Global exception handling with @RestControllerAdvice
├── model/entity/   - JPA entities (avoid @Data, use specific Lombok annotations)
├── repository/     - Spring Data JPA repositories
└── service/        - Business logic with @Service
```

### API Conventions
- **Base Context**: `/api`
- **Versioning**: `/v1/`, `/v2/` in controller mappings
- **CORS**: Configured for localhost:3000 and localhost:4200
- **Error Responses**: Standardized via `ErrorResponse` DTO with validation details

### Configuration
- **Main Config**: `application.properties` (NOT yml - project uses .properties format)
- **Profiles**: `dev`, `prod`, `test`
- **Local Override**: `application-local.properties` (gitignored)

### Database Migration
- **Tool**: Liquibase
- **Master Changelog**: `db/changelog/db.changelog-master.xml`
- **Naming**: `YYYYMMDD-HHMM-description.xml` (e.g., 20260206-0001-initial-schema.xml)
- **Location**: `src/main/resources/db/changelog/changes/`

### Exception Handling
- Custom `ResourceNotFoundException` for 404 cases
- Global handler catches validation, illegal arguments, and generic exceptions
- Always returns `ErrorResponse` with timestamp, status, message, path

### Testing
- Profile: `test` (separate database: household_manager_test)
- Integration tests use `@SpringBootTest` + `@AutoConfigureMockMvc`
- Test config: `application-test.properties` with separate test database

## Implemented Features

### Meter Readings
- Entity: `MeterReading` (ELECTRICITY, GAS, WATER)
- Repository: Query methods for latest readings, consumption calculation
- Service: Validation, consumption calculation between readings
- Controller: CRUD operations, consumption endpoints

### Utility Prices
- Entity: `UtilityPrice` (ELECTRICITY, WATER only - no GAS support)
- Repository: Query methods for current prices, overlap detection
- Service: Validation for overlapping periods, meter type restrictions
- Controller: CRUD operations, current price lookup
- **Important**: Overlap detection uses custom JPQL queries to prevent duplicate validity periods

## Best Practices Applied
1. Constructor injection preferred over field injection
2. Lombok: @Slf4j for logging, avoid @Data on entities
3. DTOs separate API contracts from domain models
4. Validation at API boundaries with @Valid
5. Comprehensive logging with SLF4J
6. Health check endpoints: `/v1/health`, `/management/health`

## Usermanagement (Tasks 1-3, foundation)
- Spring Security dependency added (`spring-boot-starter-security` + `spring-security-test`); from this point on all endpoints default to 401 until a later task adds SecurityConfig
- Tables `app_user`, `service_token`, `audit_log` (Liquibase `20260725-0042` — NOT `-0041`, see below)
- Entities `AppUser`, `ServiceToken`, `AuditLog`, enums `UserRole` (ADMIN/MEMBER/KIOSK), `AuditActorType` (USER/SERVICE/SYSTEM/TELEGRAM) in `model/entity/`; repositories in `repository/` per convention

## Links to Detailed Notes
- [usermanagement-tasks-10-12.md](usermanagement-tasks-10-12.md) - AppUserService/Bootstrap, Nuki KIOSK-lock-only rule, Admin-REST-API; strict-Mockito lenient() fix for plan test template
- [usermanagement-tasks-13-14.md](usermanagement-tasks-13-14.md) - Audit-Verdrahtung (Chokepoints, Telegram-ThreadLocal, FLOW-Aktor); WebMvc-Slice-Fallstricke: DisabledUserSessionFilter braucht AppUserRepository-Stub, GlobalExceptionHandler verschluckt NoResourceFoundException zu 500
- [liquibase-changeset-id-planning.md](liquibase-changeset-id-planning.md) - always re-check the changelog directory for the actual next free date-ID before creating a changeset; plan docs can go stale between writing and execution
- [bounded-discovery-queries.md](bounded-discovery-queries.md) - discovery/distinct queries over append-only history tables must be time-bounded, not full scans
- [database.md](database.md) - Database schema patterns and Liquibase conventions
- [api-design.md](api-design.md) - REST API design standards
- [smart-device-persistence.md](smart-device-persistence.md) - Tapo device discovery-to-DB upsert pattern, metadata merge rules, TDD flow
- [entitystate-facade.md](entitystate-facade.md) - EntityState mirror layer: facade/writer split, REQUIRES_NEW rationale, upsert/event semantics, 15-task rollout status
- [flowengine-stage3a.md](flowengine-stage3a.md) - Flow engine: NodeHandler/TriggerNodeHandler contracts, NodeContext.state() concurrency tradeoff, 13-task rollout complete + post-review hardening (dedicated scheduler, debug-buffer cleanup on undeploy)
- [waste-collection-clock.md](waste-collection-clock.md) - injected Clock bean must pin Europe/Berlin explicitly; backend container has no TZ set, systemDefaultZone() silently becomes UTC
- [response-status-exception-handler.md](response-status-exception-handler.md) - GlobalExceptionHandler's Exception.class catch-all swallows ResponseStatusException into a 500 unless a dedicated @ExceptionHandler(ResponseStatusException.class) exists
- [vision-integration.md](vision-integration.md) - Blink-Gesichtserkennung: Vision*Service-Architektur, wann Hook-Pattern-Orchestrierung bewusst OHNE @Transactional bleibt, verstellbare-Clock-Testmuster statt Zweit-Service-Objekt
- [haushaltskalender.md](haushaltskalender.md) - CalendarEvent/lib-recur 0.17.1 (API-verifiziert); Lesson: delimiter-joined TEXT-Felder hinter public Setter immer defensiv parsen (leere Tokens filtern)
- [tractive-home-resolver-fix.md](tractive-home-resolver-fix.md) - "single source of truth" resolver called with independent Instant.now() per caller silently diverges; fix = one Instant per cycle, store + reuse it (lastPolledAt pattern)
