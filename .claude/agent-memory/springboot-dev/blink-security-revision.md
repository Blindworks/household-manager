---
name: blink-security-revision
description: Task 5 (2026-08-27) — KIOSK darf Blink-Kameras jetzt schalten; Revision einer Sicherheitsregel statt gewoehnlicher Feature-Task
metadata:
  type: project
---

Am 2026-08-27 wurde die Sperre "KIOSK darf Blink-Kameras nicht scharf/unscharf schalten" (Muster Nuki) bewusst aufgehoben (Commit `29604c9`, `feature/blink-bewegung-tablet-schalten`). KIOSK-POST-Whitelist in `SecurityConfig.java` um `/v1/blink/cameras/*/arm`, `/v1/blink/cameras/*/disarm`, `/v1/blink/system/*/arm`, `/v1/blink/system/*/disarm` erweitert. Schutz gegen Versehen ist jetzt ausschliesslich der Bestaetigungsdialog in der Tablet-Ansicht (UI-Schutz), kein Server-Schutz mehr.

**Why:** Nutzerentscheidung, nicht technische Notwendigkeit — dokumentiert im Testkommentar (`kioskDarfBlinkKamerasSchalten`) mit Datum und Grund, damit spaeter nachvollziehbar bleibt, dass es Absicht war.

**Wichtige Verifikation vor der Aenderung:** `RoleHierarchyImpl.fromHierarchy` in `SecurityConfig` (ROLE_ADMIN > ROLE_MEMBER > ROLE_KIOSK) bestaetigt: MEMBER erbt KIOSK-Rechte. Ein Plan behauptete das ("`memberDarfBlinkKamerasSchalten` entfaellt, MEMBER erbt KIOSK") — nachgeprueft statt blind uebernommen, war hier korrekt.

**Mutationsprobe-Erkenntnis:** Bei einem Test, der mehrere Pfade in einer Methode prueft (vier `mockMvc.perform`-Aufrufe hintereinander), reicht es, EINEN Pfad einzeln aus der Whitelist zu nehmen — der Test bricht bereits bei der ersten fehlschlagenden Assertion (AssertionError stoppt die Methode), das beweist aber trotzdem, dass jeder einzelne Pfad ueberhaupt geprueft wird. Wichtig: sicherstellen, dass der Fehlerort (Zeilennummer im Stacktrace) zur mutierten Zeile passt.

**Real gefunden:** ein Klassenkommentar in `BlinkController.java` ("Rollen: ... Scharf/Unscharf MEMBER") wurde durch diese Revision falsch, lag aber ausserhalb des erlaubten Aenderungsbereichs (nur SecurityConfig.java + SecurityRulesTest.java) — als separaten Task geflaggt statt spontan mitgeaendert. Lehre: bei Scope-Beschraenkungen einen gefundenen Kollateralschaden flaggen, nicht stillschweigend ignorieren UND nicht eigenmaechtig ausserhalb des Scopes reparieren.

Verwandt: [[security-matcher-order-testing]]
