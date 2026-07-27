---
name: calendar-persons-categories
description: feature/calendar-persons-categories branch - category-as-table, person assignment, reminder-event person attributes; plan-text-vs-code mismatch lesson
metadata:
  type: project
---

Feature branch `feature/calendar-persons-categories` extends the existing calendar
([[haushaltskalender]]) with a proper category table, category-key stability, person
assignment to events, and person attributes on the reminder event. Task order: 1 category
table, 2 key generation, 3 category API with delete-protection, 4 person assignment,
5 persons in reminder event (done), 6 user list + own id (done), 7+ frontend.

## Task 6: `/v1/users` + own id in `/v1/auth/me` (done)
- `HouseholdUserController` (`backend/src/main/java/com/household/manager/security/`) exposes
  `GET /v1/users` -> `[{id, displayName, enabled}]` only — no username, no role. Deliberately
  thin vs. ADMIN-only `/v1/admin/users`, because the KIOSK wall tablet reads it too (person
  picker in the calendar dialog needs the list, but must never learn residents' usernames).
  Reuses the existing `AppUserService.list()` (already there from user management), no new
  service method.
- **Response record placement:** the project keeps response records as their own file under a
  `dto` package, never nested inside the `@RestController` class — see `security/dto/UserAdminDtos.java`
  and, closest to this case, `dto/CalendarPersonView.java` (same `id`+`displayName` shape from
  `AppUser`). First draft nested `HouseholdUserResponse` inside `HouseholdUserController`; quality
  review grepped all `public record` occurrences and found it was the *only* nested one in the
  whole backend. Moved to `security/dto/HouseholdUserResponse.java` with the `of(AppUser)` factory
  method following it (mirroring `CalendarPersonView`, both public since cross-package now).
- **No new SecurityConfig rule needed** — confirmed by reading `SecurityConfig.filterChain`:
  the ADMIN block matches `/v1/admin/**`, not `/v1/users`, so the generic
  `GET /v1/**` -> `hasRole("KIOSK")` rule (which sits after it) catches `/v1/users`. Added a
  regression test (`SecurityRulesTest.kioskDarfDieNutzerlisteLesen`) specifically so a future
  reordering of that generic rule doesn't silently break the tablet's access — mutation-verified
  by temporarily adding `/v1/users` to the ADMIN matcher list and confirming exactly that one
  test goes red.
- `AppUserPrincipal` gained a `Long id` field (from `AppUser.getId()`); `CurrentUserResponse`
  gained `Long id` as its first component (position matters — it's a record, no other direct
  positional constructors existed outside the class itself, verified via grep before touching
  it). `id` is `null` for service-token principals (`authentication.getPrincipal()` is a bare
  `String`, not `AppUserPrincipal`) — same fallback pattern already used for `displayName`.
- The plan's literal code snippet for `CurrentUserResponse` was correct as given this time
  (unlike Task 5) — verified the `AppUser` builder already supports `.id(...)` and
  `AppUserService.list()` already existed before writing the controller.

## Task 5: persons in `event.calendar_reminder` (done)
- `CalendarReminderScheduler.fire(...)` (`backend/src/main/java/com/household/manager/calendar/CalendarReminderScheduler.java`)
  now adds two attributes from `occ.getPersons()` (`List<CalendarPersonView>`, may be `null`
  when the builder never set it): `personIds` (stable `AppUser` ids, for flow filtering) and
  `persons` (display names, for announcements only). Both are `List.of()` — never `null` —
  when no persons are assigned, so downstream flow authors don't need a special case.
- Deliberately both attributes, not just display names: a flow filtering on the display name
  would silently break on the next rename — the same trap that already bit the Vision-person
  feature (see the main `vision-integration` memory).
- Null-safety fallback (`occ.getPersons() != null ? occ.getPersons() : List.of()`) is
  load-bearing far beyond the one dedicated "household case" test — mutation-testing it away
  (delete the null-check) turned 8 of the 10 `CalendarReminderSchedulerTest` tests red, not
  just one, because the existing `occurrence()` test helper never sets `persons` at all. Worth
  knowing before assuming "only the new test covers this branch."

## Lesson: implementation-plan test snippets can reference the wrong method/API
The plan's literal Step-1 test code stubbed `calendarService.getUpcoming(anyInt())`, but
`CalendarReminderScheduler` only ever calls `calendarService.getOccurrences(LocalDate, LocalDate)`
— `getUpcoming(int)` is a different, higher-level method that itself calls `getOccurrences`
internally. Copying the snippet verbatim would have produced a test that always fails (the
stub never intercepts the real call, Mockito's default-empty-collection answer means
`getOccurrences` returns `List.of()`, so `reportEvent` is never invoked) — not a
red-then-green TDD cycle, just permanently red. The plan also used `.getAttributes()`
(bean-style) where `EntityStateUpdate` is actually a record with `.attributes()`. Fixed by
adapting the stub/accessor to match the real code and the rest of the test file's existing
pattern (`when(calendarService.getOccurrences(any(), any()))...`), keeping the test's
assertions/intent unchanged. **Rule for future tasks in this plan (6-11):** treat a plan's
inline test code as intent, not gospel — verify the mocked method actually exists on the
collaborator and is the one the production code path calls, and check accessor style
(record component vs. Lombok getter) against the real class before pasting.
