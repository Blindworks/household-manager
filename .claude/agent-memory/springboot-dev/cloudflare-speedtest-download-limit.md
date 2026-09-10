---
name: cloudflare-speedtest-download-limit
description: Cloudflare speed.cloudflare.com/__down has an unstated size limit; requests over it return 403 with a 1-byte body, which was silently measured as a real download
metadata:
  type: project
---

Real PROD incident (2026-08-25): `CloudflareSpeedtestClient` requested `bytes=250000000` from
`speed.cloudflare.com/__down` and never checked the HTTP status code. Measured via curl:
- `bytes=25000000/50000000/90000000` → 200 OK, full body
- `bytes=100000000/200000000/250000000` → **403 Forbidden, Content-Length: 1**

The client happily timed the 1-byte 403 response and reported ~0.01 Mbit/s on a 1000 Mbit/s
fiber line, storing it as `success=true`.

**Fix pattern:** cap the single request at 50 MB (safely under the observed <100 MB limit) and
loop multiple sequential requests over the same keep-alive `HttpClient` until the time budget
is exhausted (start-time/deadline set once, on the first byte of the first response). Check
`response.statusCode() >= 400` on **both** download and upload paths and throw `IOException` —
the caller (`NetworkSpeedtestService`) already handles `IOException` correctly (direction null,
errorMessage, success=false), so nothing there needed to change.

**Verification approach for a "thin, deliberately not unit-tested" HTTP client:** write a
temporary throwaway class with a `main()` that calls the real methods against the real network,
run it once, note the printed Mbit/s values in the report, then delete the file before
committing. Don't leave it in the test tree — no `@Test` annotation, not part of the suite, and
its presence is confusing to future readers.

See also [[derived-delete-not-transactional]] for another "the mock test can't see this bug"
pattern in this project.
