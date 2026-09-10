---
name: derived-delete-not-transactional
description: Abgeleitete Spring-Data-deleteBy...-Query-Methoden sind NICHT automatisch transaktional; Retention-Jobs mit try/catch je Pfad brauchen Bulk-Delete am Repository, kein @Transactional am Job
metadata:
  type: feedback
---

Abgeleitete Repository-Methoden nach dem Muster `deleteBy...(...)` (Query-Derivation aus dem
Methodennamen, KEIN `@Query`/`@Modifying`) sind in Spring Data JPA NICHT automatisch
transaktional. Nur die geerbten CRUD-Methoden von `SimpleJpaRepository` tragen die
`@Transactional`-Klassenannotation. Die Derived-Delete-Execution laedt Zeilen und ruft
`em.remove()` pro Zeile auf — ohne aktive Transaktion wirft das `TransactionRequiredException`,
und ein umschließendes try/catch (z. B. in einem Retention-Job, der pro Löschpfad einzeln fängt,
damit ein Fehler im einen Pfad den anderen nicht mitreißt) verschluckt das lautlos. Ergebnis:
Retention in PROD dauerhaft wirkungslos, Mockito-Unit-Tests sind dafür strukturell blind (sie
mocken das Repository und sehen nie eine echte Transaktion).

**Fix:** Bulk-DML statt Query-Derivation, exakt nach dem Muster
`WasteCollectionEventRepository.deleteFromDateOnwards`:

```java
@Transactional
@Modifying
@Query("delete from Entity e where e.field < :cutoff")
int deleteByFieldBefore(@Param("cutoff") LocalDateTime cutoff);
```

Rückgabetyp ist `int` (nicht `long`) bei `@Modifying`. Damit trägt jede Repository-Methode ihre
eigene, in sich abgeschlossene Transaktion — die aufrufende Job-Methode braucht selbst KEIN
`@Transactional` (sonst entsteht das Rollback-only-Problem: eine gefangene Exception im ersten
Löschpfad markiert eine gemeinsame äußere Transaktion, und der zweite, unabhängige Löschpfad
kippt mit).

**Woher ich das weiß:** Bei [[network-history-retention]] (Task 5,
`2026-08-24-netzwerk-monitoring.md`) erst mit reinem `deleteBySampledAtBefore`/
`deleteByTestedAtBefore` (Query-Derivation) + `@Transactional` NUR auf den privaten
Repository-Interface-Methoden implementiert und mit grünen Mockito-Tests fälschlich für korrekt
gehalten — im Spec-Review als tote Retention entlarvt, weil die Tests das Transaktionsproblem
strukturell nicht sehen können. Nach Korrektur mit Bulk-Delete weiterhin grün, jetzt aber auch
tatsächlich korrekt.

**How to apply:** Bei jedem neuen `deleteBy...`/`removeBy...` in einem Repository-Interface
zuerst prüfen, ob es eine reine Query-Derivation ist. Wenn ja: entweder mit `@Modifying`+`@Query`
zum Bulk-Delete machen (bevorzugt bei Retention-/Aufräum-Jobs), oder explizit sicherstellen, dass
der Aufrufer selbst `@Transactional` trägt UND kein try/catch pro Pfad um mehrere solcher Deletes
herum die Fehlerisolierung verspricht, die es unter `@Transactional` nicht geben kann. Ein
Mockito-Unit-Test allein beweist bei diesem Bug nichts — er kann die fehlende Transaktion nicht
sehen.
