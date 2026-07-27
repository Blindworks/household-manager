---
name: security-matcher-order-testing
description: How to prove a SecurityConfig requestMatchers order-dependent rule actually matters, and how method-less requestMatchers cover all HTTP methods
metadata:
  type: feedback
---

`SecurityConfig.filterChain`'s `authorizeHttpRequests` evaluates `requestMatchers` in declaration order, first match wins (see `[[usermanagement]]`). When a new ADMIN-only path must be added above a broader, later `hasRole("KIOSK")` catch-all (e.g. `GET /v1/**`), don't trust the new `SecurityRulesTest` case just because it's green — the order could be irrelevant by accident (wrong path pattern, Spring matching quirk, etc.).

**Verification technique that worked (Tractive home-settings task, 2026-07-27):** temporarily delete the new path from the ADMIN matcher list, re-run only the new test, confirm it now FAILS with a concrete assertion mismatch (e.g. expected 403, got 404 because the request fell through to the KIOSK catch-all and only 404'd for lack of a controller in the WebMvc slice). Then restore the line and diff against git to prove the revert is exact. This is fast (single test class, no DB) and gives real evidence instead of "the test passed so the order must matter."

**Method coverage:** `.requestMatchers("/path/**", ...)` (the varargs-of-String overload, no `HttpMethod` argument) matches **all** HTTP methods on that path — GET, PUT, POST, DELETE alike. So a security test that only exercises `GET` on such a rule is still sufficient evidence for `PUT`/`POST` too, *as long as the matcher list uses the method-less overload*. Method-scoped rules use the `HttpMethod`-first overload (e.g. `.requestMatchers(HttpMethod.GET, "/v1/utility-prices/**")`) right next to it in the same file — check which overload is actually used before assuming coverage.

**The "404 statt 403 belegt Durchlass"-trick has a limit (Kalender-Kategorien task, 2026-07-27):** `SecurityRulesTest` deliberately omits most controllers from the `@WebMvcTest` slice and asserts 404 to prove a rule lets a role through. That only works for *negative* evidence. A test asserting an actual **200** (e.g. "KIOSK darf lesen") needs the controller in the `controllers = {...}` list **and** a `@MockitoBean` for its service — otherwise it 404s and the assertion fails for the wrong reason. Adding the controller is the right move, not weakening the assertion to `isNotFound()`.

**Falsification also reveals which rules are untested.** Removing all three new matchers at once (POST/PUT/DELETE) turned exactly one test red — proof that the PUT and DELETE rules had no coverage at all. Removing the whole block instead of one line is the cheap way to find that out.

Related: `[[usermanagement]]` for the overall role model and matcher-order convention.
