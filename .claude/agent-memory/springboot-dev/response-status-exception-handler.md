# ResponseStatusException needs its own @ExceptionHandler in this project

`GlobalExceptionHandler` (`backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java`)
has a catch-all `@ExceptionHandler(Exception.class)` → `handleGlobalException`, which returns a
generic 500.

If a controller throws `org.springframework.web.server.ResponseStatusException` (e.g. for
lightweight inline validation, as `WasteCollectionController.updateSettings` does), that
catch-all intercepts it BEFORE Spring's built-in `ResponseStatusExceptionResolver` ever runs.
Reason: `ExceptionHandlerExceptionResolver` (which invokes `@RestControllerAdvice` methods) is
tried earlier in the `HandlerExceptionResolverComposite` chain than
`ResponseStatusExceptionResolver`, and `Exception.class` matches everything — so the deliberate
400 + reason message silently becomes "Internal Server Error" / 500.

**Fix applied**: added `@ExceptionHandler(ResponseStatusException.class)` to
`GlobalExceptionHandler` (sits alongside the other specific handlers, before the
`Exception.class` catch-all in the file — order in the file doesn't matter for dispatch, Spring
picks the most specific applicable handler, but keep it there for readability). It builds an
`ErrorResponse` from `ex.getStatusCode()` / `ex.getReason()` so the real status and message reach
the client.

**Takeaway**: any future controller that throws `ResponseStatusException` directly (instead of a
custom exception type with its own handler) relies on this handler existing. If it's ever
removed, that pattern silently breaks (400 becomes 500) — no test in the unit-test layer would
catch it, because controller unit tests call the method directly and never go through
`GlobalExceptionHandler`. Only a `@SpringBootTest`/`@WebMvcTest`-level test would.

Related: [[waste-collection-clock]]
