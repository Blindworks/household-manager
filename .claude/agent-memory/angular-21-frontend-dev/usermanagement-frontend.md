---
name: usermanagement-frontend
description: Frontend Task 17+18 of the user-management plan (header role filter, admin pages for users/tokens/audit-log) — file locations and a verified test-fixture gotcha
metadata:
  type: project
---

Implemented in worktree `feature/user-management` (`.claude/worktrees/user-management`), commits `a7db0a8` (Task 17: Header), `2220d82` (Task 18: Admin-Services + Admin-Seiten), `1db4a48` (quality-review fixes: mobile-menu-on-logout, real design tokens, `role="alert"`).

## Quality-review lessons (applied in `1db4a48`)
- `logout()` must call `this.closeMobileMenu()` **before** `auth.logout().subscribe(...)` — otherwise the mobile nav overlay stays open over the post-logout `/login` page.
- Don't invent CSS custom properties with a fallback (`var(--color-border, #ccc)`) — check `frontend/src/styles.scss` first. The real tokens here: `--color-light-gray` (borders, not `--color-border` which doesn't exist), `--color-error` (#ef4444) and `--color-success` (#3fae2a) for status banners, `--color-dark` for text that needs to stay readable inside a tinted banner (e.g. a `<code>` block showing a one-time token).
- Status/error banners follow the login page's pattern exactly (`login.component.scss` `&__error`, `login.component.html` `role="alert"`): `background: rgba(239, 68, 68, 0.1); border: 1px solid rgba(239, 68, 68, 0.25); color: var(--color-error);` for errors, the analogous `rgba(63, 174, 42, ...)` / `--color-success` combo for success banners. Any new error/status div in this codebase should crib from `login.component.scss`/`.html` rather than inventing hex colors — this is the second time a plan-authored snippet used made-up hex/var fallbacks that a review caught.

## Auth building blocks (from WP6, already existed)
- `AuthService` (`frontend/src/app/services/auth.service.ts`): signals `currentUser`, `isAdmin`, `isMember`; `ensureLoaded()`, `logout()`.
- Guards `authGuard`/`adminGuard` in `frontend/src/app/guards/auth.guard.ts`.
- Models in `frontend/src/app/models/auth.model.ts`: `AppUser`, `ServiceTokenInfo`, `CreatedServiceToken` (`{ info, token }`), `AuditEntry`, `UserRole`.

## Header role filtering (Task 17)
`frontend/src/app/components/header/header.component.ts` — `NavLink` gained `minRole?: 'MEMBER' | 'ADMIN'`. A `visibleNavLinks` computed signal filters `navLinks`: children inherit the parent's `minRole` when they don't set their own (`child.minRole ?? link.minRole`), and a parent group disappears entirely once it has zero visible children. `auth = inject(AuthService)` is public (readonly) because the template reads `auth.currentUser()` directly for the display-name + Abmelden block (rendered in both the desktop nav and the mobile menu — don't forget both places).

**Test-fixture insight (confirmed, not just per the plan doc):** `header.component.spec.ts` was part of this project's known 4-fail baseline (`NullInjectorError: No provider for ActivatedRoute!`) because `HeaderComponent` uses `RouterLink`/`RouterLinkActive` but the spec had no router providers, and after this task it also needs `AuthService` → `HttpClient`. Adding `providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]` to the spec's `TestBed.configureTestingModule` fixed BOTH problems — `HeaderComponent should create` now passes, dropping the project's baseline fail count from 4 to 3 (AppComponent ×2 + HeroComponent remain, same root cause, not yet fixed). Worth trying the same fix on those if ever touched.

## Admin pages (Task 18)
Three template-driven (`FormsModule` + `[(ngModel)]`) pages mirroring the `finance-accounts` component pattern (plain properties, no signals, `errorMessage: string | null`, `.subscribe({ next, error })`):
- `pages/admin-users/` — CRUD table + create form; `changePassword()` uses a browser `prompt()` (min 8 chars) then `PUT .../password`.
- `pages/admin-service-tokens/` — create form + table; created token plaintext shown exactly once in a green `__created` banner ("wird nie wieder angezeigt"); `revoke()` guarded by `confirm()`.
- `pages/admin-audit-log/` — read-only table with an actor-filter form (`getEntries(200, actorFilter || undefined)`).

Services: `user-admin.service.ts`, `service-token-admin.service.ts`, `audit-log.service.ts` under `frontend/src/app/services/`, all `HttpClient` + `catchError` → `throwError(() => new Error(message))`, same shape as `FinanceService`.

Routes (`frontend/src/app/app.routes.ts`): `admin/users`, `admin/service-tokens`, `admin/audit-log` — all `canActivate: [adminGuard]`, inserted directly above the pre-existing plain `admin` route entry (followed the plan's explicit ordering instruction rather than reasoning about router-matching precedence).

No new backend integration in this pass — Admin controller endpoints already existed (`GET/POST /api/v1/admin/users`, etc.), consumed as-is.
