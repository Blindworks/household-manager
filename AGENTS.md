# Repository Guidelines

## Project Structure & Module Organization
- `backend/` Spring Boot 3.x API (Java 21). Source in `backend/src/main/java/com/household/manager/`, configs in `backend/src/main/resources/`, Liquibase migrations in `backend/src/main/resources/db/changelog/`.
- `frontend/` Angular 19 app (Standalone). Source in `frontend/src/app/` with `components/`, `pages/`, `services/`, `models/`. Static assets in `frontend/src/assets/`.
- `scripts/` helper scripts (e.g., test data).

## Build, Test, and Development Commands
Backend (from `backend/`):
- `mvn clean install` builds the backend.
- `mvn spring-boot:run` runs the API at `http://localhost:8080`.
- `mvn test` runs unit/integration tests.

Frontend (from `frontend/`):
- `npm install` installs dependencies.
- `npm run start` (or `ng serve`) runs the dev server at `http://localhost:4200`.
- `npm run build` builds the Angular app.
- `npm run test` runs Karma/Jasmine tests.
- `ng lint` runs linting (Angular CLI).

## Coding Style & Naming Conventions
- Java: 4-space indentation, `PascalCase` classes, `camelCase` methods/fields, packages lowercase.
- Angular/TS: 2-space indentation, `kebab-case` file names (`dashboard.component.ts`), `PascalCase` components, `camelCase` variables.
- Prefer small, focused services and DTOs; keep controllers thin.

## Testing Guidelines
- Backend: JUnit via `spring-boot-starter-test`. Use `*Test` naming.
- Frontend: Jasmine/Karma; specs in `*.spec.ts`.
- See `TESTING.md` for manual API test flows (curl examples).

## Commit & Pull Request Guidelines
- Commit messages in history are short, sentence-style summaries (e.g., “Input of prices added”). Keep them concise and imperative.
- PRs should include: what changed, how to test, and screenshots for UI changes. Link related issues when applicable.

## Configuration & Environment
- Requires Java 21, Maven 3.9+, Node.js/npm, and MariaDB/MySQL.
- DB config lives in `backend/src/main/resources/application.properties`. Backend is CORS-enabled for `http://localhost:4200`.
