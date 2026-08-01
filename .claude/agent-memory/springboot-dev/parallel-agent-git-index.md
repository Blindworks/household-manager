---
name: parallel-agent-git-index
description: Bei Parallelarbeit mehrerer Agenten im selben Repo committet git den GESAMTEN Index - eigene Dateien nur per Pfad-Commit committen
metadata:
  type: feedback
---

Wenn mehrere Agenten gleichzeitig im selben Arbeitsverzeichnis arbeiten, **nie**
`git add <meine dateien> && git commit -m ...` verwenden. Stattdessen:

```
git commit -m "..." -- <pfad1> <pfad2>
```

**Why:** `git commit` schreibt den kompletten Index, nicht nur das, was ich selbst
hinzugefuegt habe. Real passiert am 2026-07-28 (Zigbee-Ausfallerkennung, Task 10):
zwischen `git status` und `git commit` hat ein paralleler Agent seine beiden Dateien
gestaged — mein Commit enthielt dann vier statt zwei Dateien, darunter fremde
Work-in-Progress-Aenderungen an `EntityStateTriggerHandler`.

**How to apply:** In jeder Session mit dem Hinweis "andere Agenten arbeiten parallel"
direkt den Pfad-Commit nehmen. Ist es schon passiert, ist die Reparatur
`git reset --soft HEAD~1` und danach der Pfad-Commit — der Soft-Reset legt die fremden
Dateien wieder gestaged in den Index zurueck, genau so, wie der andere Agent sie
hinterlassen hat. Danach mit `git show --stat` gegenpruefen, nicht nur der
Commit-Ausgabe vertrauen (sie nennt die Dateizahl, aber leicht zu ueberlesen).
