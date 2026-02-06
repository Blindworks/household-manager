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
- **Main Config**: `application.yml`
- **Profiles**: `dev`, `prod`, `test`
- **Local Override**: `application-local.yml` (gitignored)

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
- Test config: `application-test.yml` with `drop-first: true` for clean state

## Best Practices Applied
1. Constructor injection preferred over field injection
2. Lombok: @Slf4j for logging, avoid @Data on entities
3. DTOs separate API contracts from domain models
4. Validation at API boundaries with @Valid
5. Comprehensive logging with SLF4J
6. Health check endpoints: `/v1/health`, `/management/health`

## Links to Detailed Notes
- [database.md](database.md) - Database schema patterns and Liquibase conventions
- [api-design.md](api-design.md) - REST API design standards
