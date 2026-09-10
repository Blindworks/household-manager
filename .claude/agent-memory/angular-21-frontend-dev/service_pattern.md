---
name: service-pattern
description: Standard HttpClient service pattern used across Household-Manager frontend services
metadata:
  type: project
---

Reference implementation: `frontend/src/app/services/weather.service.ts`, also followed in
`frontend/src/app/services/alexa.service.ts`.

Pattern:
- `@Injectable({ providedIn: 'root' })`
- `private readonly http = inject(HttpClient);` (function-based DI, not constructor injection)
- `private readonly baseUrl = '/api/v1/<feature>';` — note backend context-path is `/api`, so
  service baseUrl always starts with `/api/v1/...` even though controller `@RequestMapping` is
  just `/v1/<feature>`. Don't "fix" this by dropping `/api` — it's correct.
- Every public method pipes `catchError(this.handleError)`.
- `private handleError(error: HttpErrorResponse): Observable<never>` logs via
  `console.error('<Feature>-API-Fehler:', error)` and rethrows a new `Error` with a German
  user-facing message (either a fixed string, or `error.error?.message || '<fallback German
  message>'` when the backend may send a structured message).
- Models live in a matching `frontend/src/app/models/<feature>.model.ts` file as plain
  `interface`/`type` exports, no classes.

**Why:** Confirmed correct by explicit task instruction ("baseUrl is `/api/v1/alexa` — confirmed
correct — do not change it") and mirrors the pre-existing WeatherService exactly.

**How to apply:** When adding a new HttpClient service in this project, copy this shape rather than
inventing a new one (e.g. no HttpParams builder, plain template-string query params like
`?rescan=${rescan}` are used and accepted in tests).
