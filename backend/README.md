# Household Manager Backend

Modern Spring Boot backend application for household management, including meter readings tracking and household data organization.

## Technology Stack

- **Java 21** - Latest LTS version
- **Spring Boot 3.4.1** - Latest stable version
- **Spring Data JPA** - Database access and ORM
- **MariaDB** - Primary database
- **Liquibase** - Database version control
- **Lombok** - Reduce boilerplate code
- **Maven** - Build and dependency management

## Prerequisites

- Java 21 or higher
- Maven 3.8+
- MariaDB 10.6+ or MySQL 8.0+
- IDE with Lombok plugin (IntelliJ IDEA, Eclipse, VS Code)

## Getting Started

### 1. Database Setup

Create the database (or let Spring Boot create it automatically):

```sql
CREATE DATABASE household_manager;
```

Default connection settings (can be overridden in `application-local.yml`):
- Host: localhost
- Port: 3306
- Database: household_manager
- Username: root
- Password: root

### 2. Build the Application

```bash
mvn clean install
```

### 3. Run the Application

```bash
mvn spring-boot:run
```

Or run the main class `HouseholdManagerApplication` from your IDE.

### 4. Verify Installation

Once the application starts, verify it's running:

- Health Check: http://localhost:8080/api/v1/health
- Application Status: http://localhost:8080/api/v1/health/status
- Actuator Health: http://localhost:8080/api/management/health

Expected response from health endpoint:
```json
{
  "status": "UP",
  "message": "Household Manager Backend is running",
  "timestamp": "2026-02-06T...",
  "version": "development"
}
```

## Project Structure

```
backend/
├── src/
│   ├── main/
│   │   ├── java/com/household/manager/
│   │   │   ├── config/           # Application configuration
│   │   │   ├── controller/       # REST controllers
│   │   │   ├── dto/              # Data Transfer Objects
│   │   │   ├── exception/        # Exception handling
│   │   │   ├── model/entity/     # JPA entities (to be added)
│   │   │   ├── repository/       # Data repositories (to be added)
│   │   │   ├── service/          # Business logic (to be added)
│   │   │   └── HouseholdManagerApplication.java
│   │   └── resources/
│   │       ├── db/changelog/     # Liquibase migrations
│   │       ├── application.yml   # Main configuration
│   │       ├── application-dev.yml
│   │       └── application-prod.yml
│   └── test/                     # Test files
└── pom.xml                       # Maven configuration
```

## Configuration

### Application Profiles

- **default**: Standard configuration (application.yml)
- **dev**: Development environment (application-dev.yml)
- **prod**: Production environment (application-prod.yml)

Activate a profile:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Local Configuration Override

Create `application-local.yml` in `src/main/resources/` to override settings locally:

```yaml
spring:
  datasource:
    url: jdbc:mariadb://localhost:3306/household_manager
    username: your_username
    password: your_password
```

This file is ignored by git.

## API Documentation

### Base URL
- Local: `http://localhost:8080/api`
- Context Path: `/api`
- API Version: `/v1`

### Available Endpoints

#### Health Check
- **GET** `/v1/health` - Simple health check
- **GET** `/v1/health/status` - Detailed status information
- **GET** `/management/health` - Spring Actuator health endpoint

## Database Migrations

Liquibase manages database schema versions automatically. Migrations are located in:
```
src/main/resources/db/changelog/
```

### Master Changelog
- `db.changelog-master.xml` - Main changelog file that includes all changesets

### Changesets
- `changes/20260206-0001-initial-schema.xml` - Initial database setup

To run migrations manually:
```bash
mvn liquibase:update
```

To rollback last changeset:
```bash
mvn liquibase:rollback -Dliquibase.rollbackCount=1
```

## Development

### Code Style
- Follow Clean Code principles
- Use Lombok annotations to reduce boilerplate
- Constructor injection preferred over field injection
- DTOs separate from entities
- Comprehensive exception handling

### Adding New Features

1. Create entity in `model/entity/`
2. Create repository in `repository/`
3. Create DTOs in `dto/`
4. Implement service in `service/`
5. Create REST controller in `controller/`
6. Add Liquibase changelog in `db/changelog/changes/`

### Building for Production

```bash
mvn clean package -DskipTests
java -jar target/household-manager-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## Error Handling

The application uses a global exception handler (`GlobalExceptionHandler`) that provides consistent error responses:

```json
{
  "timestamp": "2026-02-06T10:30:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/endpoint",
  "validationErrors": {
    "fieldName": "error message"
  }
}
```

## Logging

Logging levels can be configured per package in `application.yml`:

```yaml
logging:
  level:
    com.household: DEBUG
    org.springframework.web: INFO
```

## Next Steps

- Implement meter readings entities and API
- Add authentication and authorization
- Implement user management
- Add API documentation with Swagger/OpenAPI
- Implement caching strategy
- Add comprehensive unit and integration tests

## Support

For issues and questions, please refer to the project documentation or contact the development team.
