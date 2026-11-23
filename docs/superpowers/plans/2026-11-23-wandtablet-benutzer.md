# Wandtablet-Benutzer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Merkmal „Wandtablet“ am Benutzerkonto. Wäsche-Dialog und Toni-allein-Screen erscheinen nur, wenn das Merkmal gesetzt **und** die Tablet-Ansicht aktiv ist.

**Architecture:** Neue Spalte `app_user.wall_tablet`. `/auth/me`, Login und Passwortwechsel lesen das Merkmal frisch aus der DB, die Admin-DTOs führen es mit. Im Frontend liefert `AuthService.isWallTablet` das Merkmal. `ViewModeService.showWallTabletOverlays` ist die einzige Regel für die Sichtbarkeit von Dialog und Screen. Außerdem startet ein Wandtablet-Konto ohne gespeicherte Wahl in der Tablet-Ansicht.

**Tech Stack:** Spring Boot 3.4, Liquibase/MariaDB, Lombok; Angular 19 Signals, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-11-23-wandtablet-benutzer-design.md`

## Global Constraints
- Keine Änderung an `SecurityConfig`, an Rollen oder an der Tablet-App.
- Ein Changeset mit genau einem DDL: `app_user.wall_tablet BOOLEAN NOT NULL DEFAULT FALSE`. Es ist nie gegen die echte DB gelaufen, deshalb gehört ein Backup vor den Deploy.
- Service-Tokens haben immer `wallTablet = false`.
- Alle übrigen Tablet-Layoutunterschiede bleiben am Umschalter `isTabletView`.
- Backend-Build mit JDK 21: `JAVA_HOME` zeigt auf jdk-21.0.10. `contextLoads` ist lokal DB-rot, das ist die Baseline.

## Review Focus
- Ein Handy mit eingeschalteter Tablet-Ansicht und einem Nicht-Wandtablet-Konto darf weder Dialog noch Screen zeigen. Den Test dafür bekommen Task 3 (Dashboard) und Task 3 (Shell).
- Ein Wandtablet-Konto, das die Ansicht bewusst auf „Website“ gestellt hat, darf nach dem Neuladen nicht wieder auf „Tablet“ springen. Die gespeicherte Wahl gewinnt (Test in Task 3).
- Nicht angemeldet bzw. Nutzer noch nicht geladen ergibt `isWallTablet = false` und kein Aufblitzen (Test in Task 3).
- Ein Passwortwechsel des Tablet-Kontos darf das Merkmal in der Antwort nicht verlieren (Test in Task 1).
- Wird eine Benutzerzeile gespeichert, ohne dass jemand das Häkchen anfasst, bleibt das Merkmal erhalten. Das Frontend sendet es deshalb immer mit (Test in Task 2).

---

### Task 1: Backend – Spalte, Entity, DTOs, Auth-Antworten

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261123-0114-add-app-user-wall-tablet.xml` (Muster `20261120-0112`, `addColumn`). Den Eintrag in `db.changelog-master.xml` nach Zeile 226 einfügen.
- Modify: `backend/.../model/entity/AppUser.java`: `@Builder.Default @Column(name = "wall_tablet", nullable = false) private boolean wallTablet = false;`
- Modify: `backend/.../security/dto/UserAdminDtos.java`:
  - `UserResponse` bekommt `boolean wallTablet`, gesetzt in `from`.
  - `CreateUserRequest` und `UpdateUserRequest` bekommen `boolean wallTablet`. Fehlt der Wert, gilt `false`.
- Modify: `backend/.../security/AppUserService.java`:
  - `create(username, displayName, password, role, boolean wallTablet)`
  - `update(id, displayName, role, enabled, boolean wallTablet)`
  - neu `boolean isWallTablet(Long id)`: `findById` → Feld. Ein fehlender Nutzer oder `null` ergibt `false`.
- Modify: `backend/.../security/UserAdminController.java`: Werte durchreichen. Das Audit-Detail von `user.create`/`user.update` bekommt den Zusatz ` Wandtablet`, wenn das Merkmal gesetzt ist.
- Modify: `backend/.../security/dto/CurrentUserResponse.java`:
  - Record-Feld `boolean wallTablet` am Ende.
  - Signatur `from(Authentication, boolean wallTablet)`. Bei einem Nicht-`AppUserPrincipal` immer `false`.
- Modify: `backend/.../security/AuthController.java`:
  - private `CurrentUserResponse respond(Authentication auth)`: `id` aus dem Principal → `appUserService.isWallTablet(id)`, sonst `false`.
  - login, me und changePassword nutzen alle `respond`.
- Test: `CurrentUserResponseTest`, `AppUserServiceTest`, `UserAdminControllerTest`, `AuthControllerPasswordChangeTest` (bestehende Aufrufe auf die neuen Signaturen anpassen).

**Interfaces:**
- Produces (JSON):
  - `GET/POST /v1/auth/me|login|password` → `{…, "wallTablet": boolean}`
  - `GET /v1/admin/users` → je Zeile `wallTablet`
  - `POST`/`PUT /v1/admin/users[/{id}]` nehmen `wallTablet` an.

- [ ] **Step 1: Failing Tests schreiben**
  - `CurrentUserResponseTest.serviceTokenIstNieWandtablet`: `from(tokenAuth, true).wallTablet()` ist `false`.
  - `CurrentUserResponseTest.nutzerUebernimmtWandtablet`: `from(userAuth, true).wallTablet()` ist `true`.
  - `AppUserServiceTest.updateSetztWandtablet`: `update(id, "Tablet", MEMBER, true, true)` → `user.isWallTablet()` ist `true`.
  - `AppUserServiceTest.isWallTabletUnbekannterNutzerFalse`: Das Repository liefert leer, das Ergebnis ist `false`.
  - `UserAdminControllerTest.updateReichtWandtabletDurch`: PUT mit `"wallTablet":true` → `verify(service).update(1L, …, true, true)`, die Antwort enthält `$.wallTablet == true`.
  - `AuthControllerPasswordChangeTest.antwortTraegtWandtablet`: `isWallTablet(id)` liefert `true`, die Antwort enthält `$.wallTablet == true`.
- [ ] **Step 2: Tests laufen lassen.** Sie scheitern am Kompilieren bzw. an der Assertion.
  - Run: `cd backend && mvn -q test -Dtest='CurrentUserResponseTest,AppUserServiceTest,UserAdminControllerTest,AuthControllerPasswordChangeTest'`
- [ ] **Step 3: Implementieren wie unter Files.**
- [ ] **Step 4: Tests grün, dazu `mvn -q test -Dtest='*Security*,*Auth*,*User*'` grün.**
- [ ] **Step 5: Commit.** `feat(auth): Merkmal Wandtablet am Benutzerkonto`

### Task 2: Frontend – Modell und Admin-Häkchen

**Files:**
- Modify: `frontend/src/app/models/auth.model.ts`:
  - `CurrentUser.wallTablet: boolean`
  - `AppUser.wallTablet: boolean`
  - `CreateUserRequest.wallTablet: boolean`
  - `UpdateUserRequest.wallTablet: boolean` (die Typnamen nach Bestand)
- Modify: `frontend/src/app/pages/admin-users/admin-users.component.ts/.html`:
  - Checkbox „Wandtablet“ im Anlegen-Formular (`form.wallTablet`, Default `false`, auch beim Zurücksetzen nach dem Anlegen).
  - In der Benutzerliste eine Spalte „Wandtablet“ (`[name]="'wt-' + user.id"`).
  - Das Update sendet `wallTablet: user.wallTablet` immer mit.
- Test: `admin-users.component.spec.ts`. Existiert sie nicht, neu anlegen.

- [ ] **Step 1: Failing Test schreiben**
  - `sendet wallTablet beim Speichern einer Zeile mit`: Die Zeile hat `wallTablet: true`. `save(user)` → `updateUser` wird mit `jasmine.objectContaining({ wallTablet: true })` aufgerufen.
  - `legt Benutzer mit Wandtablet an`: `form.wallTablet = true`, dann `create()` → `createUser` mit `objectContaining({ wallTablet: true })`.
- [ ] **Step 2: Test rot.** Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=src/app/pages/admin-users/**/*.spec.ts`
- [ ] **Step 3: Implementieren.** Die Methodennamen `save`/`create` nach Bestand übernehmen.
- [ ] **Step 4: Test grün.**
- [ ] **Step 5: Commit.** `feat(admin-users): Häkchen Wandtablet`

### Task 3: Frontend – Sichtbarkeit an Wandtablet koppeln

**Files:**
- Modify: `frontend/src/app/services/auth.service.ts`: `readonly isWallTablet = computed(() => this.user()?.wallTablet === true);`
- Modify: `frontend/src/app/services/view-mode.service.ts`:
  - `inject(AuthService)` ist zyklusfrei, weil `AuthService` nur `HttpClient` injiziert.
  - `readonly showWallTabletOverlays = computed(() => this.auth.isWallTablet() && this.isTabletView())`.
  - Im Konstruktor ein `effect`: Wird `auth.isWallTablet()` `true` und ist **kein** Wert unter `household-manager-view-mode` gespeichert, dann `isTabletView.set(true)`. Dabei nicht persistieren, damit eine spätere eigene Wahl gewinnt. `hasStoredChoice()` liest localStorage in try/catch, ein Fehler gilt als „keine Wahl“.
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html:1238`: `@if (viewMode.isTabletView())` um Wäsche-Prompt und Dog-Screen wird zu `@if (viewMode.showWallTabletOverlays())`.
- Modify: `frontend/src/app/components/tablet-shell/tablet-shell.component.html:54`: dasselbe für den Wäsche-Prompt.
- Test:
  - neu `frontend/src/app/services/view-mode.service.spec.ts`
  - `dashboard.component.spec.ts` (Dog-Screen-Block ab ca. Zeile 3550)
  - `tablet-shell.component.spec.ts` (ab Zeile 131)
  - In den Tests das Merkmal über `HttpTestingController` auf `/api/v1/auth/me` setzen, oder `AuthService` per `TestBed.inject` holen und `spyOnProperty`/einen Stub `isWallTablet: signal(true)` geben. Das bestehende Muster der Datei hat Vorrang.

- [ ] **Step 1: Failing Tests schreiben**
  - `view-mode.service.spec`:
    - `showWallTabletOverlays nur bei Wandtablet und Tablet-Ansicht`: Die vier Kombinationen ergeben nur bei (true, true) `true`.
    - `Wandtablet ohne gespeicherte Wahl startet in Tablet-Ansicht`: localStorage leer, das Merkmal wird `true`, nach `TestBed.flushEffects()` ist `isTabletView()` `true` und localStorage weiter leer.
    - `gespeicherte Website-Wahl gewinnt`: localStorage `'website'`, das Merkmal wird `true` → `isTabletView()` bleibt `false`.
    - `ohne geladenen Nutzer kein Overlay`: Tablet-Ansicht an, Nutzer nicht geladen → `false`.
  - Dashboard: Die bestehenden Dog-Screen-Tests setzen das Merkmal `true`. Neu ist `zeigt den Screen nicht ohne Wandtablet-Konto, auch in der Tablet-Ansicht` mit `screen(fixture)` gleich `null`.
  - Tablet-Shell: `render(true)` mit Merkmal `true` zeigt den Prompt. Neu: Tablet-Ansicht an und Merkmal `false` ergibt `app-laundry-prompt` gleich `null`.
- [ ] **Step 2: Tests rot.** Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/{services/view-mode.service,pages/dashboard/dashboard.component,components/tablet-shell/tablet-shell.component}.spec.ts'`
- [ ] **Step 3: Implementieren wie unter Files.**
- [ ] **Step 4: Gezielte Tests grün, dann die ganze Suite.** Erwartet ist die Baseline mit 3 bekannten Fehlschlägen (App/Hero) und eventuell dem SmartDeviceList-Flake, sonst nichts.
- [ ] **Step 5: Commit.** `feat(tablet): Wäsche-Dialog und Toni-Screen nur für Wandtablet-Konto`

### Task 4: Doku

- [ ] **Step 1:** CLAUDE.md, Abschnitt „Benutzerverwaltung & API-Sicherheit“, bekommt einen Punkt „Wandtablet-Merkmal“ mit diesen Inhalten:
  - Spalte und Changeset (nie gegen die echte DB gelaufen).
  - `showWallTabletOverlays` als einzige Regel.
  - Die Start-Ansicht ohne gespeicherte Wahl.
  - Das Merkmal steuert keine Rechte, das Konto ist MEMBER.
  - Rollout: Backup → Deploy → Konto „Wandtablet“ mit MEMBER und Häkchen anlegen → am Tablet neu anmelden.
- [ ] **Step 2:** Die Abschnitte „Dog-Mode-Screen“ und „Wäschetrocknung“ ändern: „nur in der Tablet-Ansicht“ wird zu „nur beim Wandtablet-Konto in der Tablet-Ansicht“.
- [ ] **Step 3: Commit.** `docs: Wandtablet-Merkmal in CLAUDE.md`
