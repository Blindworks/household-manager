---
name: plan-doc-text-goes-stale
description: fertige Doku-Textbausteine in Implementierungsplaenen sind Momentaufnahmen — nach einem Review kann die beschriebene Regel umgedreht worden sein; immer gegen Code und revidierte Spec pruefen
metadata:
  type: feedback
---

Enthaelt ein Implementierungsplan einen fertigen Markdown-Block zum Uebernehmen in `CLAUDE.md` oder eine andere Doku, **nicht ungeprueft kopieren**. Reihenfolge der Wahrheit: tatsaechlicher Code > revidierte Spec > Plan-Text.

**Why:** Bei der Zigbee-Ausfallerkennung (2026-07-28) beschrieb Task 14 des Plans „Die Flow-Engine feuert nicht mehr bei Uebergaengen von/nach `unavailable`". Ein Code-Review hatte die Regel danach umgedreht (nur noch der Uebergang *nach* `unavailable` wird unterdrueckt, weil beidseitige Unterdrueckung einen Brandalarm verschluckt haette). Die Spec wurde revidiert, der Plan-Text nicht — er wurde beim Schreiben des Plans verfasst, nicht beim Abschluss. Wer ihn kopiert, dokumentiert genau das Gegenteil einer sicherheitsrelevanten Entscheidung.

**How to apply:** Bei jedem Doku-Task zuerst `git log --oneline` der betroffenen Klassen lesen — ein `fix(...)`-Commit *nach* dem Feature-Commit ist das Warnsignal — und danach die Aussagen einzeln gegen die Quelldatei pruefen. Gleiches Muster wie [[liquibase-changeset-id-planning]]: Plaene altern zwischen Schreiben und Ausfuehren.

Zweiter Teil derselben Aufgabe: eine geaenderte Engine-Regel macht **Bestandsdoku** falsch, nicht nur die neue. Nach einer Semantikaenderung im Flow-Engine-Kern immer `docs/flows/flow-import-format.md` (liest der KI-Autorenweg) und den Engine-Entwurf mitziehen — sonst autoren kuenftige Agenten gegen die alte Semantik.
