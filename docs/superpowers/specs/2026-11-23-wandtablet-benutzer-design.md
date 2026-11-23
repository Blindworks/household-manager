# Wandtablet-Benutzer (Merkmal „Wandtablet“) – Design

## Warum
Das Wandtablet ist heute mit dem Admin-Konto angemeldet. Wäsche-Dialog („aufgehängt?“/„trocken?“) und Toni-allein-Screen hängen am Ansichts-Umschalter „Tablet-Ansicht“ (`ViewModeService`, localStorage je Browser) — wer auf dem Handy die Tablet-Ansicht einschaltet, bekommt beides auch dort. Beides soll **nur auf dem Wandtablet** erscheinen. Das Tablet bekommt dafür ein eigenes Konto.

## Ziele / Nicht-Ziele / Anti-Ziele
- Ziel: eigenes Tablet-Konto; Wäsche-Dialog und Toni-Screen nur bei diesem Konto.
- Ziel: das Tablet kann weiter Vorräte buchen und die Haustür vollständig bedienen (verriegeln, entriegeln, öffnen).
- Nicht-Ziel: Änderung an Rollen oder Sicherheitsregeln; Änderung an der Tablet-App (APK).
- Anti-Ziel: Handy oder anderer Benutzer sieht Dialog/Screen, nur weil die Tablet-Ansicht eingeschaltet ist.

## Ansätze
1. **MEMBER-Konto mit Merkmal „Wandtablet“ (gewählt).** Neue Spalte am Benutzer, ausgeliefert über `/auth/me`; Frontend blendet Dialog/Screen nur bei gesetztem Merkmal ein. Keine Sicherheitsänderung, keine neue APK.
2. KIOSK-Rolle aufbohren (Vorräte, Entriegeln) – verworfen: verwischt die Grenze „KIOSK darf nur verriegeln“, gälte auch für KIOSK-Service-Tokens.
3. Tablet-App per User-Agent erkennen – verworfen: neue APK nötig, im normalen Browser nicht testbar.

## Design
- **DB:** `app_user.wall_tablet BOOLEAN NOT NULL DEFAULT FALSE` (ein Changeset, ein DDL).
- **Backend:** Feld `wallTablet` am `AppUser`; in den Admin-DTOs (Liste, Anlegen, Ändern) mitgeführt; Ändern wie bisher auditiert. `/auth/me` (und Login/Passwortwechsel) liefert `wallTablet` – frisch aus der DB gelesen, damit ein im Admin gesetztes Häkchen ohne Neuanmeldung greift; Service-Tokens immer `false`.
- **Admin → Benutzer:** Häkchen „Wandtablet“ beim Anlegen und Bearbeiten.
- **Frontend:** `AuthService.isWallTablet`. Wäsche-Dialog (Dashboard und Tablet-Shell) und Toni-allein-Screen erscheinen nur bei `isWallTablet` **und** aktiver Tablet-Ansicht. Ein Wandtablet-Konto startet in der Tablet-Ansicht, solange der Browser keine eigene Wahl gespeichert hat; der Umschalter bleibt nutzbar (Weg zu Nutzermenü/Abmelden).
- Alle übrigen Layout-Unterschiede der Tablet-Ansicht (Links, Besitzernamen im Angebote-Dialog) bleiben am Umschalter.

## Rollout
Deploy (Changeset, Backup vorher) → Admin → Benutzer: Konto „Wandtablet“ mit Rolle **MEMBER** und Häkchen anlegen → am Tablet abmelden und mit dem neuen Konto anmelden.

## Dem Builder überlassen
Name des Changesets, Testzuschnitt, genaue Platzierung des Häkchens in der Benutzerliste.
