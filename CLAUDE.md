# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Household-Manager is a full-stack application for managing household utilities and inventory. The application is split into a separate frontend and backend with the following structure:

- **Frontend**: Angular 21 (standalone mode) with SCSS
- **Backend**: Spring Boot with MySQL/MariaDB database

### Current Features (Phase 1)
- Utility meter readings tracking (Strom/Electricity, Gas, Wasser/Water)
- Consumption monitoring and history

### Planned Features
- **Phase 2**: Product inventory management system with barcode scanning for household items
- Additional household management features (TBD)

## Project Structure

```
household-manager/
├── frontend/          # Angular 21 application
│   └── ...           # Standalone components with separate HTML/TS files
└── backend/          # Spring Boot application
    └── ...           # Java/Kotlin with Lombok and Liquibase
```

## Frontend (Angular 21)

### Technology Stack
- Angular 21 in standalone mode (no NgModules)
- TypeScript with separate HTML template files
- SCSS for styling
- Component-based architecture

### Development Commands

```bash
cd frontend

# Install dependencies
npm install

# Start development server
ng serve

# Build for production
ng build --configuration production

# Run tests
ng test

# Run linting
ng lint
```

### Frontend Conventions
- Use standalone components exclusively
- Keep HTML templates in separate `.html` files (not inline)
- Use SCSS for all styling
- Follow Angular style guide for component structure

## Backend (Spring Boot)

### Technology Stack
- Spring Boot (latest version)
- MySQL/MariaDB database
- Lombok for boilerplate reduction
- Liquibase for database migrations
- Maven or Gradle for dependency management

### Development Commands

```bash
cd backend

# Build the application (Maven)
mvn clean install

# Build the application (Gradle)
./gradlew build

# Run the application
mvn spring-boot:run
# OR
./gradlew bootRun

# Run tests
mvn test
# OR
./gradlew test

# Run specific test
mvn test -Dtest=YourTestClass
# OR
./gradlew test --tests YourTestClass
```

### Database Setup

The application uses Liquibase for database migrations. Migration files are located in `src/main/resources/db/changelog/`.

**Local Database Setup:**
1. Install MySQL/MariaDB locally
2. Create database: `CREATE DATABASE household_manager;`
3. Update `application.properties` with local credentials (**Note**: This project uses `.properties` files, not `.yml`)
4. Liquibase will automatically apply migrations on startup

### Backend Conventions
- Use Lombok annotations (@Data, @Builder, @Slf4j, etc.) to reduce boilerplate
- All database schema changes must be done through Liquibase changesets
- Never modify the database schema manually
- Follow Spring Boot best practices for layered architecture (Controller → Service → Repository)

## Database Schema

### Current Entities (Phase 1)
- **Meter Readings**: Tracks utility consumption (electricity, gas, water)
  - Meter type (Strom, Gas, Wasser)
  - Reading value
  - Reading date
  - Calculation of consumption between readings

### Planned Entities (Phase 2)
- **Products**: Household inventory items
- **Product Stock**: Current quantities
- **Barcode**: Product identification for scanning

## Development Workflow

1. **Frontend Development**: Work in `frontend/` directory with Angular CLI
2. **Backend Development**: Work in `backend/` directory with Spring Boot
3. **API Communication**: Frontend calls backend REST API (typically running on `http://localhost:8080`)
4. **Database Changes**: Create Liquibase changelog files for any schema modifications

## Testing

- **Frontend**: Karma/Jasmine tests with `ng test`
- **Backend**: JUnit tests with Spring Boot Test
- Test coverage for both utility tracking and future product management features

## Key Technical Decisions

- **Standalone Angular**: Modern Angular without NgModules for better tree-shaking and simpler architecture
- **Separate HTML/TS**: Better separation of concerns and easier template editing
- **Liquibase**: Version-controlled database migrations for consistent schema across environments
- **Lombok**: Reduced boilerplate in Java/Kotlin entities and DTOs
- **Barcode Integration**: Future feature for quick product entry into inventory system

## Code Quality Standards

This project follows **Clean Code** principles across both frontend and backend:

### General Clean Code Principles
- **Meaningful Names**: Use intention-revealing names for variables, functions, and classes
- **Single Responsibility**: Each class/function should have one clear purpose
- **Small Functions**: Keep functions focused and concise
- **DRY (Don't Repeat Yourself)**: Extract common logic into reusable components/services/utilities
- **Comments**: Code should be self-documenting; only comment when necessary to explain "why", not "what"

### Backend (Java/Spring Boot)
- Use meaningful names for entities, services, and repositories (e.g., `MeterReadingService`, not `MRS`)
- Keep controllers thin - business logic belongs in service layer
- Use DTOs for API requests/responses to separate API contract from domain models
- Leverage Lombok thoughtfully - don't hide complex logic behind annotations
- Write focused methods that do one thing well
- Use Optional for nullable return types
- Proper exception handling with custom exceptions where appropriate

### Frontend (Angular/TypeScript)
- Components should be focused on presentation, delegate logic to services
- Services should handle business logic and API communication
- Use meaningful component/service names (e.g., `MeterReadingFormComponent`, `MeterReadingService`)
- Keep TypeScript methods small and focused
- Avoid complex logic in templates - move to component methods
- Use TypeScript types and interfaces properly (avoid `any`)
- Reactive programming with RxJS observables for asynchronous operations

### Testing
- Write tests that are readable and maintainable
- Use descriptive test names that explain what is being tested
- Follow AAA pattern (Arrange, Act, Assert)
- Test business logic thoroughly, especially consumption calculations and inventory tracking
