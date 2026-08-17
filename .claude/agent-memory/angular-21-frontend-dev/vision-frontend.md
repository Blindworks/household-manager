# Vision / Gesichtserkennung Feature (Task 12+13, worktree feature/blink-gesichtserkennung)

- `frontend/src/app/models/vision.model.ts`, `services/vision.service.ts` (+spec), `pages/vision/vision.component.*` (+spec) — mirrors `pages/announcements` structure (plain component properties, not signals, since AnnouncementsComponent predates the signal convention used elsewhere).
- `VisionService.handleError` passes through `error.error?.message` (like `AlexaService`), not a generic string — backend's `VisionException` returns 502 with a `message` body field that's actually useful to the user (e.g. "kein Gesicht erkannt").
- HttpTestingController gotcha: when a URL is built via manual string concatenation (`` `${base}/x?limit=${n}` ``, no `HttpParams` object), `HttpRequest.url` includes the full query string — `expectOne(r => r.url === '/path')` (without query) will NOT match. Use `r.url.startsWith('/path')` or match the full string with query. Same applies to `AlexaService.getDevices` (`?rescan=`) if ever spec'd this way.
- Route added as `/vision`, nav entry added inside the `/admin` group's `children` array in `header.component.ts` (alongside `/flows`, `/announcements`).
