# Tablet-Ansicht „Toni" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine neue Unteransicht des Wandtablets unter `/tablet/toni`, die Futtervorrat, Spaziergänge, Tracker-Status und Position des Hundes in einem 2×2-Raster ohne Scrollen zeigt.

**Architecture:** Reine Anzeige-Seite in `pages/tablet-toni/`, eingefasst in die bestehende `<app-tablet-shell>` (liefert Uhr-/Wetterzeile und Ansichtsleiste). Sie nutzt ausschließlich vorhandene Endpunkte (`PetFoodService`, `TractiveService`) und legt keine neue API an. Zwei Formatierungs-Utils, die heute als Methoden im Dashboard stehen, wandern vorher nach `shared/` — eine Definition, mehrere Oberflächen. Einzige Backend-Änderung: `TractiveWalkService.MAX_DAYS` von 14 auf 30, sonst wäre der 30-Tage-Knopf stumm wirkungslos.

**Tech Stack:** Angular 19 (standalone, separate HTML/SCSS), ECharts über `ngx-echarts` (Balkendiagramm), Leaflet mit OSM-Kacheln, Karma/Jasmine; Backend Spring Boot 3.4 / Java 21, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-08-22-tablet-toni-ansicht-design.md`

---

## Testkommandos auf dieser Maschine

**Frontend** (aus `frontend/`):

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

Eine einzelne Datei:

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/walk-format.util.spec.ts'
```

**Vorbestehende Baseline: genau 3 FAILED** (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`). Nur *zusätzliche* Fails sind Regressionen. `SmartDeviceListComponent` wirft gelegentlich eine Karma-Flake in `afterAll` — bei Verdacht den Lauf wiederholen.

**Backend** (aus `backend/`): Der Standard-`JAVA_HOME` dieser Maschine zeigt auf JDK 17, das Projekt braucht 21:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractiveWalkServiceTest
```

`HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern hier an einer nicht erreichbaren Test-DB — vorbestehend, ignorieren.

---

## File Structure

**Neu:**

| Datei | Verantwortung |
|---|---|
| `frontend/src/app/shared/pet-food-level.util.ts` | Füllstands-Bewertung des Futtervorrats (Schwellen + Ton + Balkenbreite) |
| `frontend/src/app/shared/pet-food-level.util.spec.ts` | dessen Tests |
| `frontend/src/app/shared/walk-format.util.ts` | Formatierung und Aggregation von Spaziergängen |
| `frontend/src/app/shared/walk-format.util.spec.ts` | dessen Tests |
| `frontend/src/app/shared/leaflet-icons.util.ts` | Leaflet-Standard-Icons auf `assets/leaflet` umbiegen |
| `frontend/src/app/pages/tablet-toni/tablet-toni.component.ts` | Datenbeschaffung und Aufbereitung der vier Kacheln |
| `frontend/src/app/pages/tablet-toni/tablet-toni.component.html` | Markup des 2×2-Rasters |
| `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss` | Flex-Höhenkette, Raster, Kachelstile |
| `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts` | Komponententests inkl. Höhenkette |

**Geändert:**

| Datei | Änderung |
|---|---|
| `frontend/src/app/shared/tablet-views.ts` | Eintrag `/tablet/toni` |
| `frontend/src/app/app.routes.ts` | Route `tablet/toni` |
| `frontend/src/app/pages/dashboard/dashboard.component.ts` | `petFoodTone` und die vier Walk-Methoden delegieren an die neuen Utils |
| `frontend/src/app/pages/pet-food/pet-food.component.ts` | `criticalCans`, `fillTone`, `barWidth` delegieren |
| `frontend/src/app/pages/pets/pets.component.ts` | nutzt `leaflet-icons.util` statt der eigenen Kopie |
| `backend/.../tractive/TractiveWalkService.java` | `MAX_DAYS` 14 → 30 |
| `backend/.../tractive/TractiveWalkServiceTest.java` | Test, dass 30 Tage nicht geklemmt werden |

---

## Task 1: Füllstands-Util für den Futtervorrat

Die Schwelle „7 Dosen" steht heute dreifach im Code. Bevor eine vierte Kopie entsteht, bekommt sie eine Heimat.

**Files:**
- Create: `frontend/src/app/shared/pet-food-level.util.ts`
- Create: `frontend/src/app/shared/pet-food-level.util.spec.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts:1473-1478`
- Modify: `frontend/src/app/pages/pet-food/pet-food.component.ts:33-52`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Datei `frontend/src/app/shared/pet-food-level.util.spec.ts`:

```ts
import {
  PET_FOOD_CRITICAL_CANS,
  PET_FOOD_WARN_PERCENT,
  petFoodBarWidth,
  petFoodTone
} from './pet-food-level.util';

describe('pet-food-level.util', () => {
  it('meldet kritisch unterhalb der Dosenschwelle', () => {
    expect(petFoodTone({ cansRemaining: 6.5, percent: 14 })).toBe('critical');
  });

  it('meldet genau auf der Dosenschwelle nicht mehr kritisch', () => {
    // Die Flow-Bedingung lautet "< 7", nicht "<= 7" - die Grenze muss gleich liegen.
    expect(petFoodTone({ cansRemaining: PET_FOOD_CRITICAL_CANS, percent: 15 })).toBe('warn');
  });

  it('warnt unterhalb des Fuellstands-Schwellwerts', () => {
    expect(petFoodTone({ cansRemaining: 10, percent: PET_FOOD_WARN_PERCENT - 1 })).toBe('warn');
  });

  it('meldet ab dem Fuellstands-Schwellwert normal', () => {
    expect(petFoodTone({ cansRemaining: 12, percent: PET_FOOD_WARN_PERCENT })).toBe('ok');
  });

  it('klemmt die Balkenbreite auf 0 bis 100', () => {
    expect(petFoodBarWidth(-5)).toBe(0);
    expect(petFoodBarWidth(140)).toBe(100);
    expect(petFoodBarWidth(63)).toBe(63);
  });
});
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/pet-food-level.util.spec.ts'
```

Erwartet: Kompilierfehler `Cannot find module './pet-food-level.util'`.

- [ ] **Step 3: Die Implementierung schreiben**

Datei `frontend/src/app/shared/pet-food-level.util.ts`:

```ts
/**
 * Bewertung des Toni-Futtervorrats. Einzige Definition im Frontend - die Seite
 * /pet-food, die Dashboard-Kachel und die Tablet-Ansicht fragen dieselbe Funktion.
 *
 * Der Telegram-Warnflow auf sensor.pet_food_toni_cans traegt dieselbe Schwelle ein
 * zweites Mal. Er lebt in der Flow-Engine und ist von hier aus nicht erreichbar -
 * wer PET_FOOD_CRITICAL_CANS aendert, muss den Flow von Hand nachziehen.
 */
export type PetFoodTone = 'ok' | 'warn' | 'critical';

/** Muss der Flow-Bedingung "sensor.pet_food_toni_cans < 7" entsprechen. */
export const PET_FOOD_CRITICAL_CANS = 7;

/** Darunter wird gewarnt, auch wenn die Dosenzahl noch ueber der Schwelle liegt. */
export const PET_FOOD_WARN_PERCENT = 25;

export function petFoodTone(status: { cansRemaining: number; percent: number }): PetFoodTone {
  if (status.cansRemaining < PET_FOOD_CRITICAL_CANS) {
    return 'critical';
  }
  return status.percent < PET_FOOD_WARN_PERCENT ? 'warn' : 'ok';
}

/** Breite des Fuellstandsbalkens in Prozent, geklemmt auf 0..100. */
export function petFoodBarWidth(percent: number): number {
  return Math.max(0, Math.min(100, percent));
}
```

- [ ] **Step 4: Test laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/pet-food-level.util.spec.ts'
```

Erwartet: `5 SUCCESS`, keine Fails.

- [ ] **Step 5: Dashboard auf das Util umstellen**

In `frontend/src/app/pages/dashboard/dashboard.component.ts` bei den übrigen `shared/`-Importen ergänzen:

```ts
import { PetFoodTone, petFoodTone as petFoodLevelTone } from '../../shared/pet-food-level.util';
```

Die Methode ab Zeile 1473 ersetzen. Der Alias beim Import ist nötig, weil die Methode denselben Namen trägt — das Template ruft `petFoodTone(food)` an zwei Stellen auf (Zeilen 368 und 935) und bleibt unverändert:

```ts
  petFoodTone(status: PetFoodStatus): PetFoodTone {
    return petFoodLevelTone(status);
  }
```

- [ ] **Step 6: Seite /pet-food auf das Util umstellen**

In `frontend/src/app/pages/pet-food/pet-food.component.ts` importieren:

```ts
import {
  PET_FOOD_CRITICAL_CANS,
  PetFoodTone,
  petFoodBarWidth,
  petFoodTone
} from '../../shared/pet-food-level.util';
```

Das Feld `criticalCans` (Zeile 33-34) und die beiden Methoden `fillTone`/`barWidth` (Zeilen 41-52) ersetzen:

```ts
  /** Einzige Definition in shared/pet-food-level.util.ts. */
  readonly criticalCans = PET_FOOD_CRITICAL_CANS;

  fillTone(status: PetFoodStatus): PetFoodTone {
    return petFoodTone(status);
  }

  barWidth(status: PetFoodStatus): number {
    return petFoodBarWidth(status.percent);
  }
```

- [ ] **Step 7: Beide bestehenden Suiten laufen lassen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/pet-food.component.spec.ts'
```

Erwartet: alle grün — der bestehende Test in `pet-food.component.spec.ts:49-53` prüft genau die drei Stufen und muss unverändert durchlaufen.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/shared/pet-food-level.util.ts frontend/src/app/shared/pet-food-level.util.spec.ts frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/pet-food/pet-food.component.ts
git commit -m "refactor(petfood): Fuellstands-Schwellen in ein gemeinsames Util ziehen"
```

---

## Task 2: Formatier-Util für Spaziergänge

**Files:**
- Create: `frontend/src/app/shared/walk-format.util.ts`
- Create: `frontend/src/app/shared/walk-format.util.spec.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts:1566-1599`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Datei `frontend/src/app/shared/walk-format.util.spec.ts`:

```ts
import { TractiveWalk } from '../models/tractive.model';
import {
  groupWalksByDay,
  walkDayLabel,
  walkDayTotals,
  walkDistance,
  walkDuration,
  walkTimeRange
} from './walk-format.util';

/** Baut eine Runde mit lokaler Wandzeit - die Anzeige rechnet ueberall lokal. */
function walk(startLocal: string, endLocal: string,
              durationMinutes: number, distanceMeters: number): TractiveWalk {
  return {
    start: new Date(startLocal).toISOString(),
    end: new Date(endLocal).toISOString(),
    durationMinutes,
    distanceMeters
  };
}

describe('walk-format.util', () => {
  describe('walkDuration', () => {
    it('zeigt unter einer Stunde nur Minuten', () => {
      expect(walkDuration(walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100))).toBe('36 min');
    });

    it('zeigt ab einer Stunde Stunden und Minuten', () => {
      expect(walkDuration(walk('2026-08-22T07:00', '2026-08-22T08:25', 85, 5000)))
        .toBe('1 h 25 min');
    });
  });

  describe('walkDistance', () => {
    it('zeigt unter einem Kilometer ganze Meter', () => {
      expect(walkDistance(walk('2026-08-22T07:00', '2026-08-22T07:10', 10, 842.4))).toBe('842 m');
    });

    it('zeigt ab genau einem Kilometer Kilometer mit Komma', () => {
      expect(walkDistance(walk('2026-08-22T07:00', '2026-08-22T07:20', 20, 1000))).toBe('1,0 km');
      expect(walkDistance(walk('2026-08-22T07:00', '2026-08-22T07:40', 40, 2149))).toBe('2,1 km');
    });
  });

  describe('walkTimeRange', () => {
    it('nennt Start und Ende als Uhrzeit', () => {
      expect(walkTimeRange(walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100)))
        .toBe('07:12–07:48 Uhr');
    });
  });

  describe('walkDayLabel', () => {
    const now = new Date('2026-08-22T18:00');

    it('nennt den heutigen Tag beim Namen', () => {
      expect(walkDayLabel(new Date('2026-08-22T07:12').toISOString(), now)).toBe('Heute');
    });

    it('nennt den Vortag beim Namen', () => {
      expect(walkDayLabel(new Date('2026-08-21T19:30').toISOString(), now)).toBe('Gestern');
    });

    it('nennt aeltere Tage mit Wochentag und Datum', () => {
      expect(walkDayLabel(new Date('2026-08-18T09:00').toISOString(), now)).toContain('18.8.');
    });
  });

  describe('groupWalksByDay', () => {
    it('fasst Runden desselben Kalendertags zusammen', () => {
      const groups = groupWalksByDay([
        walk('2026-08-22T18:00', '2026-08-22T18:30', 30, 1800),
        walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100),
        walk('2026-08-21T19:00', '2026-08-21T19:20', 20, 900)
      ]);

      expect(groups.length).toBe(2);
      expect(groups[0].walks.length).toBe(2);
      expect(groups[1].walks.length).toBe(1);
    });
  });

  describe('walkDayTotals', () => {
    const now = new Date('2026-08-22T18:00');

    it('summiert die Gehdauer je Kalendertag, aelteste zuerst', () => {
      const totals = walkDayTotals([
        walk('2026-08-22T18:00', '2026-08-22T18:30', 30, 1800),
        walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100),
        walk('2026-08-20T19:00', '2026-08-20T19:20', 20, 900)
      ], 3, now);

      expect(totals.map(total => total.minutes)).toEqual([20, 0, 66]);
    });

    it('liefert fuer jeden Tag des Zeitraums einen Balken, auch ohne Runde', () => {
      // Ein fehlender Balken saehe aus wie ein Datenloch; eine Null ist eine Aussage.
      const totals = walkDayTotals([], 7, now);
      expect(totals.length).toBe(7);
      expect(totals.every(total => total.minutes === 0)).toBeTrue();
    });

    it('beschriftet die Balken mit Tag und Monat', () => {
      const totals = walkDayTotals([], 2, now);
      expect(totals.map(total => total.label)).toEqual(['21.8.', '22.8.']);
    });

    it('ignoriert Runden ausserhalb des Zeitraums', () => {
      const totals = walkDayTotals(
        [walk('2026-08-01T07:00', '2026-08-01T07:30', 30, 1500)], 3, now);
      expect(totals.every(total => total.minutes === 0)).toBeTrue();
    });
  });
});
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/walk-format.util.spec.ts'
```

Erwartet: Kompilierfehler `Cannot find module './walk-format.util'`.

- [ ] **Step 3: Die Implementierung schreiben**

Datei `frontend/src/app/shared/walk-format.util.ts`:

```ts
import { TractiveWalk } from '../models/tractive.model';

/**
 * Darstellung von Spaziergaengen. Einzige Definition - der Walks-Dialog des
 * Dashboards und die Tablet-Ansicht fragen dieselben Funktionen, damit dieselbe
 * Runde nicht an einer Stelle "1,4 km" und an der anderen "1400 m" heisst.
 *
 * Bewusst reine Funktionen ohne Angular-Bezug statt einer gemeinsamen
 * Kind-Komponente: die lumina-Styles des Dashboards sind in dessen SCSS
 * gekapselt und erreichen ein Kind nicht - es renderte lautlos ungestylt.
 */

/** Ein Tagesbalken des Spaziergangs-Diagramms. */
export interface WalkDayTotal {
  /** Kurzes Etikett fuer die Achse, z. B. "22.8.". */
  label: string;
  /** Summe der Gehdauer dieses Tages in Minuten; 0 an Tagen ohne Runde. */
  minutes: number;
}

export function walkTimeRange(walk: TractiveWalk): string {
  const format = (iso: string) =>
    new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  return `${format(walk.start)}–${format(walk.end)} Uhr`;
}

export function walkDuration(walk: TractiveWalk): string {
  const hours = Math.floor(walk.durationMinutes / 60);
  const minutes = walk.durationMinutes % 60;
  return hours > 0 ? `${hours} h ${minutes} min` : `${minutes} min`;
}

export function walkDistance(walk: TractiveWalk): string {
  return walk.distanceMeters >= 1000
    ? `${(walk.distanceMeters / 1000).toFixed(1).replace('.', ',')} km`
    : `${Math.round(walk.distanceMeters)} m`;
}

/** Gruppiert nach Kalendertag; die Reihenfolge (neueste zuerst) kommt vom Server. */
export function groupWalksByDay(walks: TractiveWalk[]): { label: string; walks: TractiveWalk[] }[] {
  const groups: { label: string; walks: TractiveWalk[] }[] = [];
  for (const walk of walks) {
    const label = new Date(walk.start).toLocaleDateString('de-DE', {
      weekday: 'long', day: 'numeric', month: 'long'
    });
    const last = groups[groups.length - 1];
    if (last && last.label === label) {
      last.walks.push(walk);
    } else {
      groups.push({ label, walks: [walk] });
    }
  }
  return groups;
}

/** Kurzes Tagesetikett fuer eine einzelne Runde: "Heute", "Gestern" oder "Di, 18.8.". */
export function walkDayLabel(iso: string, now: Date = new Date()): string {
  const start = new Date(iso);
  if (dayKey(start) === dayKey(now)) {
    return 'Heute';
  }
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (dayKey(start) === dayKey(yesterday)) {
    return 'Gestern';
  }
  return start.toLocaleDateString('de-DE', { weekday: 'short', day: 'numeric', month: 'numeric' });
}

/**
 * Ein Balken je Kalendertag des Zeitraums, aeltester zuerst. Tage ohne Runde
 * bekommen eine 0 statt gar keinen Eintrag - eine Luecke im Diagramm saehe aus
 * wie ein Datenloch, eine leere Saeule ist eine Aussage.
 */
export function walkDayTotals(walks: TractiveWalk[], days: number,
                              now: Date = new Date()): WalkDayTotal[] {
  const minutesByDay = new Map<string, number>();
  for (const walk of walks) {
    const key = dayKey(new Date(walk.start));
    minutesByDay.set(key, (minutesByDay.get(key) ?? 0) + walk.durationMinutes);
  }

  const totals: WalkDayTotal[] = [];
  for (let offset = days - 1; offset >= 0; offset--) {
    const day = new Date(now);
    day.setDate(day.getDate() - offset);
    totals.push({
      label: day.toLocaleDateString('de-DE', { day: 'numeric', month: 'numeric' }),
      minutes: minutesByDay.get(dayKey(day)) ?? 0
    });
  }
  return totals;
}

/** Kalendertag in lokaler Zeit - bewusst nicht ueber die ISO-Zeichenkette, die in UTC steht. */
function dayKey(date: Date): string {
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}
```

- [ ] **Step 4: Test laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/walk-format.util.spec.ts'
```

Erwartet: `13 SUCCESS`, keine Fails.

- [ ] **Step 5: Dashboard auf das Util umstellen**

In `frontend/src/app/pages/dashboard/dashboard.component.ts` importieren (Aliase, weil die Methoden dieselben Namen tragen und das Template unverändert bleibt):

```ts
import {
  groupWalksByDay as groupWalks,
  walkDistance as formatWalkDistance,
  walkDuration as formatWalkDuration,
  walkTimeRange as formatWalkTimeRange
} from '../../shared/walk-format.util';
```

Die vier Methoden in den Zeilen 1566-1599 ersetzen durch:

```ts
  /** Gruppiert nach Kalendertag; einzige Definition in shared/walk-format.util.ts. */
  private groupWalksByDay(walks: TractiveWalk[]): { label: string; walks: TractiveWalk[] }[] {
    return groupWalks(walks);
  }

  walkTimeRange(walk: TractiveWalk): string {
    return formatWalkTimeRange(walk);
  }

  walkDuration(walk: TractiveWalk): string {
    return formatWalkDuration(walk);
  }

  walkDistance(walk: TractiveWalk): string {
    return formatWalkDistance(walk);
  }
```

- [ ] **Step 6: Dashboard-Suite laufen lassen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```

Erwartet: alle grün.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/walk-format.util.ts frontend/src/app/shared/walk-format.util.spec.ts frontend/src/app/pages/dashboard/dashboard.component.ts
git commit -m "refactor(tractive): Spaziergangs-Formatierung in ein gemeinsames Util ziehen"
```

---

## Task 3: Backend — 30 Tage Spaziergangs-Historie zulassen

Ohne diese Änderung wäre der 30-Tage-Knopf stumm wirkungslos: `getWalks` klemmt per `Math.clamp(days, 1, MAX_DAYS)` und lieferte weiterhin 14 Tage, ohne Fehler und ohne Log.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveWalkService.java:32`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveWalkServiceTest.java`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `TractiveWalkServiceTest.java` hinter `tageWerdenAufDasMaximumGeklemmt()` (endet Zeile 239) einfügen:

```java
    @Test
    void dreissigTageWerdenNichtMehrGeklemmt() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 30);

        // Ein Cloud-Haeppchen je Tag: 30 angefragte Tage muessen 30 Abrufe ergeben.
        // Vor der Anhebung von MAX_DAYS waren es 14 - ohne Fehler und ohne Hinweis.
        verify(apiClient, times(30)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractiveWalkServiceTest
```

Erwartet: `dreissigTageWerdenNichtMehrGeklemmt` FAILED mit „Wanted 30 times ... but was 14 times".

- [ ] **Step 3: Die Grenze anheben**

In `TractiveWalkService.java` Zeile 32 ersetzen:

```java
    /**
     * Obergrenze des abfragbaren Zeitraums. Die Tablet-Ansicht bietet 30 Tage an.
     *
     * Preis: der erste 30-Tage-Abruf loest bis zu 30 einzelne Cloud-Aufrufe aus
     * (groessere Fenster lehnt die Cloud mit Code 7500 HISTORY ab) und trifft damit
     * realistisch das Rate-Limit - dann liefert der Service das Teilergebnis der
     * schon geladenen Tage. Abgeschlossene Tage bleiben danach dauerhaft im Cache.
     */
    static final int MAX_DAYS = 30;
```

- [ ] **Step 4: Test laufen lassen und grün sehen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractiveWalkServiceTest
```

Erwartet: alle Tests der Klasse grün. `tageWerdenAufDasMaximumGeklemmt` rechnet über die Konstante und bleibt gültig; `pruneOldDays` nutzt dieselbe Konstante und hält den Cache damit automatisch 30 Tage weit.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveWalkService.java backend/src/test/java/com/household/manager/tractive/TractiveWalkServiceTest.java
git commit -m "feat(tractive): Spaziergangs-Historie bis 30 Tage zulassen"
```

---

## Task 4: Seitengerüst, Route und Eintrag in der Ansichtsleiste

**Files:**
- Create: `frontend/src/app/pages/tablet-toni/tablet-toni.component.ts`
- Create: `frontend/src/app/pages/tablet-toni/tablet-toni.component.html`
- Create: `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss`
- Create: `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`
- Modify: `frontend/src/app/shared/tablet-views.ts`
- Modify: `frontend/src/app/app.routes.ts:75-81`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Datei `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TabletToniComponent } from './tablet-toni.component';
import { PetFoodService } from '../../services/pet-food.service';
import { TractiveService } from '../../services/tractive.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { TABLET_VIEWS } from '../../shared/tablet-views';

describe('TabletToniComponent', () => {
  let fixture: ComponentFixture<TabletToniComponent>;
  let petFoodSpy: jasmine.SpyObj<PetFoodService>;
  let tractiveSpy: jasmine.SpyObj<TractiveService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  beforeEach(async () => {
    petFoodSpy = jasmine.createSpyObj('PetFoodService', ['getStatus']);
    petFoodSpy.getStatus.and.returnValue(
      of({ cansRemaining: 34, targetCans: 48, percent: 71, daysRemaining: 34 }));

    tractiveSpy = jasmine.createSpyObj('TractiveService', ['getPets', 'getWalks']);
    tractiveSpy.getPets.and.returnValue(of([]));
    tractiveSpy.getWalks.and.returnValue(of([]));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletToniComponent],
      providers: [
        // Der Rahmen (app-tablet-shell) nutzt routerLink fuer die Ansichtsleiste
        // und zieht das Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: PetFoodService, useValue: petFoodSpy },
        { provide: TractiveService, useValue: tractiveSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletToniComponent);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('steht in der Ansichtsleiste des Tablets', () => {
    // Ohne diesen Eintrag waere die Seite auf dem Tablet nicht erreichbar - im
    // Tablet-Modus blendet die App den Header samt Navigation komplett aus.
    expect(TABLET_VIEWS.some(view => view.route === '/tablet/toni')).toBeTrue();
  });

  it('rahmt sich in die Tablet-Shell mit der Ueberschrift Toni', () => {
    const heading = (fixture.nativeElement as HTMLElement).querySelector('.lumina__heading');
    expect(heading?.textContent?.trim()).toBe('Toni');
  });

  it('zeigt vier Kacheln', () => {
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-toni__card');
    expect(cards.length).toBe(4);
  });
});
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: Kompilierfehler `Cannot find module './tablet-toni.component'`.

- [ ] **Step 3: Eintrag in der Ansichtsleiste ergänzen**

In `frontend/src/app/shared/tablet-views.ts` die Liste erweitern:

```ts
export const TABLET_VIEWS: readonly TabletView[] = [
  { route: '/tablet/temperatures', icon: 'thermostat', label: 'Temperaturen' },
  { route: '/tablet/air-quality', icon: 'air', label: 'Luftqualität' },
  { route: '/tablet/toni', icon: 'pets', label: 'Toni' }
];
```

- [ ] **Step 4: Route ergänzen**

In `frontend/src/app/app.routes.ts` hinter dem Block `tablet/air-quality` (endet Zeile 81) einfügen:

```ts
  {
    path: 'tablet/toni',
    loadComponent: () => import('./pages/tablet-toni/tablet-toni.component').then(m => m.TabletToniComponent),
    canActivate: [authGuard],
    title: 'Toni Tablet - Household Manager'
  },
```

- [ ] **Step 5: Komponentengerüst schreiben**

Datei `frontend/src/app/pages/tablet-toni/tablet-toni.component.ts`:

```ts
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { PetFoodService } from '../../services/pet-food.service';
import { TractiveService } from '../../services/tractive.service';
import { PetFoodStatus } from '../../models/pet-food.model';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';

/**
 * Hundeuebersicht fuer das Wandtablet: Futtervorrat, Spaziergaenge,
 * Tracker-Status und Position in einem 2x2-Raster, alles gleichzeitig sichtbar
 * und ohne Scrollen.
 *
 * Rein anzeigend. Buchungen bleiben der Seite /pet-food und dem
 * Dashboard-Dialog vorbehalten - auf dem Tablet laeuft die KIOSK-Rolle, dort
 * wuerde jede Buchung ohnehin mit 403 scheitern.
 */
@Component({
  selector: 'app-tablet-toni',
  standalone: true,
  imports: [CommonModule, TabletShellComponent],
  templateUrl: './tablet-toni.component.html',
  styleUrl: './tablet-toni.component.scss'
})
export class TabletToniComponent implements OnInit, OnDestroy {
  private readonly petFoodService = inject(PetFoodService);
  private readonly tractiveService = inject(TractiveService);

  food: PetFoodStatus | null = null;
  pet: TractivePet | null = null;
  walks: TractiveWalk[] = [];

  ngOnInit(): void {
    this.petFoodService.getStatus().subscribe({ next: food => (this.food = food) });
    this.tractiveService.getPets().subscribe({ next: pets => (this.pet = pets[0] ?? null) });
  }

  ngOnDestroy(): void {
    // Der Selbst-Refresh kommt in Task 5 dazu.
  }
}
```

Datei `frontend/src/app/pages/tablet-toni/tablet-toni.component.html`:

```html
<app-tablet-shell heading="Toni">
  <section class="tablet-toni">
    <div class="tablet-toni__grid">
      <article class="tablet-toni__card tablet-toni__card--food"></article>
      <article class="tablet-toni__card tablet-toni__card--walks"></article>
      <article class="tablet-toni__card tablet-toni__card--status"></article>
      <article class="tablet-toni__card tablet-toni__card--map"></article>
    </div>
  </section>
</app-tablet-shell>
```

Datei `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss`:

```scss
// Inhalt der Tablet-Hundeansicht. Kopfzeile und Ansichtsleiste liefert
// app-tablet-shell; hier bleibt das 2x2-Raster, das sich die Resthoehe teilt.

// Die Hoehe kommt ueber eine durchgehende Flex-Kette von .app-layout (100vh)
// bis in die Kacheln. Fehlt sie an EINER Stelle, faellt alles darunter auf
// Inhaltshoehe zurueck und die Graphen werden winzig - deshalb traegt schon
// das Host-Element der Seite flex: 1 (dasselbe Muster wie im Dashboard).
:host {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.tablet-toni {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  gap: 0.75rem;
  color: #e4e2e4;

  &__grid {
    flex: 1 1 auto;
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    grid-auto-rows: 1fr;
    gap: 1rem;
    min-height: 0;
  }

  &__card {
    display: flex;
    flex-direction: column;
    min-height: 0;
    padding: 0.75rem 0.9rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 1rem;
    background: rgba(255, 255, 255, 0.03);
  }

  &__card-title {
    margin: 0 0 0.35rem;
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.5rem;
    font-weight: 600;
    flex: 0 0 auto;
    // Die globale Regel h1..h6 { color: var(--color-dark) } aus styles.scss
    // schlaegt die vererbte helle Farbe - auf schwarzem Grund waere die
    // Beschriftung sonst kaum lesbar.
    color: #f4f3f5;
    font-size: 1.05rem;
    letter-spacing: -0.01em;
    min-width: 0;
  }

  &__hint {
    flex: 1 1 auto;
    display: grid;
    place-items: center;
    margin: 0;
    color: #94a3b8;
    font-size: 1.05rem;
    text-align: center;
  }
}
```

- [ ] **Step 6: Test laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: `3 SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/tablet-toni frontend/src/app/shared/tablet-views.ts frontend/src/app/app.routes.ts
git commit -m "feat(tablet): Geruest der Toni-Ansicht mit Route und Leisteneintrag"
```

---

## Task 5: Datenbeschaffung, Selbst-Refresh und Fehlerverhalten

Drei unabhängige Quellen: fällt Tractive aus, steht der Futtervorrat trotzdem da. Die Spaziergänge hängen am Tracker, weil sie dessen `trackerId` brauchen.

**Files:**
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.ts`
- Test: `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

In `tablet-toni.component.spec.ts` den Import um `throwError` und die Modelle erweitern:

```ts
import { of, throwError } from 'rxjs';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
```

Direkt unter den Spy-Deklarationen die Testdaten ergänzen:

```ts
  const toni: TractivePet = {
    trackerId: 'dev-9', name: 'Toni', latitude: 48.2, longitude: 16.37,
    batteryPercent: 78, charging: false, zone: 'Zuhause', atHome: true,
    lastSeen: '2026-08-22T14:22:00Z'
  };
  const runde: TractiveWalk = {
    start: '2026-08-22T05:12:00Z', end: '2026-08-22T05:48:00Z',
    durationMinutes: 36, distanceMeters: 2100
  };
```

In `beforeEach` die beiden Tractive-Stubs auf echte Daten umstellen:

```ts
    tractiveSpy.getPets.and.returnValue(of([toni]));
    tractiveSpy.getWalks.and.returnValue(of([runde]));
```

Und diese Testfälle ans Ende der Suite hängen:

```ts
  it('laedt Futtervorrat und Tracker beim Start', () => {
    expect(petFoodSpy.getStatus).toHaveBeenCalledTimes(1);
    expect(tractiveSpy.getPets).toHaveBeenCalledTimes(1);
  });

  it('holt die Spaziergaenge des ersten Tiers mit dem Standardzeitraum', () => {
    expect(tractiveSpy.getWalks).toHaveBeenCalledOnceWith('dev-9', 7);
  });

  it('fragt ohne Tracker gar nicht erst nach Spaziergaengen', () => {
    tractiveSpy.getPets.and.returnValue(of([]));
    tractiveSpy.getWalks.calls.reset();

    const fresh = TestBed.createComponent(TabletToniComponent);
    fresh.detectChanges();

    expect(tractiveSpy.getWalks).not.toHaveBeenCalled();
    fresh.destroy();
  });

  it('laedt bei einem Zeitraumwechsel genau einmal nach', () => {
    tractiveSpy.getWalks.calls.reset();
    fixture.componentInstance.setWalkDays(30);

    expect(tractiveSpy.getWalks).toHaveBeenCalledOnceWith('dev-9', 30);
    expect(fixture.componentInstance.walkDays).toBe(30);
  });

  it('laedt nicht nach, wenn der aktive Zeitraum erneut gewaehlt wird', () => {
    tractiveSpy.getWalks.calls.reset();
    fixture.componentInstance.setWalkDays(7);

    expect(tractiveSpy.getWalks).not.toHaveBeenCalled();
  });

  it('haelt die Quellen auseinander: ein Tractive-Ausfall laesst das Futter stehen', () => {
    // Bewusst der ERSTabruf und nicht reload(): ein stiller Hintergrund-Refresh
    // setzt absichtlich gar keine Fehlermeldung. Hier geht es darum, dass ein
    // Ausfall der einen Quelle die andere nicht mitreisst.
    tractiveSpy.getPets.and.returnValue(throwError(() => new Error('offline')));

    const fresh = TestBed.createComponent(TabletToniComponent);
    fresh.detectChanges();
    const component = fresh.componentInstance;

    expect(component.food).not.toBeNull();
    expect(component.petError).not.toBeNull();
    expect(component.foodError).toBeNull();

    fresh.destroy();
  });

  it('behaelt bei einem fehlgeschlagenen Hintergrund-Refresh die bisherigen Werte', () => {
    // Auf einer Wandanzeige sind alte Zahlen mehr wert als eine Fehlermeldung.
    const component = fixture.componentInstance;
    petFoodSpy.getStatus.and.returnValue(throwError(() => new Error('offline')));

    component.reload();

    expect(component.food?.cansRemaining).toBe(34);
    expect(component.foodError).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf des Futters scheitert', () => {
    petFoodSpy.getStatus.and.returnValue(throwError(() => new Error('offline')));

    const fresh = TestBed.createComponent(TabletToniComponent);
    fresh.detectChanges();

    expect(fresh.componentInstance.foodError).not.toBeNull();
    expect(fresh.componentInstance.food).toBeNull();
    fresh.destroy();
  });
```

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: Kompilierfehler `Property 'setWalkDays' does not exist` bzw. `Property 'foodError' does not exist`.

- [ ] **Step 3: Die Datenbeschaffung implementieren**

`tablet-toni.component.ts` ersetzen (die Klasse; Imports und Decorator aus Task 4 bleiben, `WalkRangeDays` kommt dazu). **Der Typ gehört über den `@Component`-Decorator, nicht dazwischen** — der Decorator muss unmittelbar vor der Klasse stehen:

```ts
/** Auswaehlbare Zeitraeume der Spaziergangs-Kachel. 30 ist die Backend-Obergrenze. */
export type WalkRangeDays = 7 | 14 | 30;

export class TabletToniComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly petFoodService = inject(PetFoodService);
  private readonly tractiveService = inject(TractiveService);
  private refreshTimer: number | null = null;

  readonly walkRanges: readonly WalkRangeDays[] = [7, 14, 30];

  food: PetFoodStatus | null = null;
  foodError: string | null = null;
  pet: TractivePet | null = null;
  petError: string | null = null;
  walks: TractiveWalk[] = [];
  walkError: string | null = null;
  walkDays: WalkRangeDays = 7;

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletToniComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.load(true);
  }

  setWalkDays(days: WalkRangeDays): void {
    if (days === this.walkDays) {
      return;
    }
    this.walkDays = days;
    this.loadWalks(false);
  }

  /**
   * Die drei Quellen laufen unabhaengig: faellt Tractive aus, steht der
   * Futtervorrat trotzdem noch da - und umgekehrt.
   */
  private load(silent: boolean): void {
    this.loadFood(silent);
    this.loadPet(silent);
  }

  private loadFood(silent: boolean): void {
    this.petFoodService.getStatus().subscribe({
      next: food => {
        this.food = food;
        this.foodError = null;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden des Futtervorrats:', error);
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte nicht
        // durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer Wandanzeige
        // mehr wert als gar keine.
        if (!silent) {
          this.foodError = 'Futtervorrat nicht verfügbar.';
        }
      }
    });
  }

  private loadPet(silent: boolean): void {
    this.tractiveService.getPets().subscribe({
      next: pets => {
        // Bewusst das erste Tier: bei genau einem Hund ist das Toni. Mehrere Tiere
        // waeren eine eigene Entscheidung, keine stille Erweiterung des Rasters.
        this.pet = pets[0] ?? null;
        this.petError = null;
        this.loadWalks(silent);
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden des Trackers:', error);
        if (!silent) {
          this.petError = 'Tracker nicht verfügbar.';
        }
      }
    });
  }

  private loadWalks(silent: boolean): void {
    const trackerId = this.pet?.trackerId;
    if (!trackerId) {
      return;
    }
    this.tractiveService.getWalks(trackerId, this.walkDays).subscribe({
      next: walks => {
        this.walks = walks;
        this.walkError = null;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Spaziergänge:', error);
        if (!silent) {
          // Der haeufigste Grund ist das Rate-Limit der Tractive-Cloud.
          this.walkError = 'Spaziergänge nicht verfügbar.';
        }
      }
    });
  }
}
```

- [ ] **Step 4: Tests laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: `11 SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/tablet-toni
git commit -m "feat(tablet): Datenbeschaffung und Selbst-Refresh der Toni-Ansicht"
```

---

## Task 6: Kachel „Futtervorrat"

**Files:**
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.ts`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.html`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss`
- Test: `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Ans Ende der Suite in `tablet-toni.component.spec.ts`:

```ts
  it('zeigt Dosenzahl und Reichweite des Futtervorrats', () => {
    const card = (fixture.nativeElement as HTMLElement).querySelector('.tablet-toni__card--food');
    expect(card?.textContent).toContain('34');
    expect(card?.textContent).toContain('34 Tage');
  });

  it('faerbt den Fuellstand nach derselben Regel wie die Seite /pet-food', () => {
    const component = fixture.componentInstance;
    expect(component.foodTone).toBe('ok');

    component.food = { cansRemaining: 6.5, targetCans: 48, percent: 14, daysRemaining: 6 };
    expect(component.foodTone).toBe('critical');
  });

  it('zeigt statt eines leeren Balkens einen Hinweis, wenn der Vorrat fehlt', () => {
    const component = fixture.componentInstance;
    component.food = null;
    component.foodError = 'Futtervorrat nicht verfügbar.';
    fixture.detectChanges();

    const card = (fixture.nativeElement as HTMLElement).querySelector('.tablet-toni__card--food');
    expect(card?.querySelector('.tablet-toni__hint')?.textContent)
      .toContain('Futtervorrat nicht verfügbar.');
  });
```

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: Kompilierfehler `Property 'foodTone' does not exist`.

- [ ] **Step 3: Die Komponente erweitern**

Import ergänzen:

```ts
import { PetFoodTone, petFoodBarWidth, petFoodTone } from '../../shared/pet-food-level.util';
```

Und diese Getter in die Klasse aufnehmen:

```ts
  /** Ton des Fuellstands; ohne Daten neutral. Regel in shared/pet-food-level.util.ts. */
  get foodTone(): PetFoodTone {
    return this.food ? petFoodTone(this.food) : 'ok';
  }

  get foodBarWidth(): number {
    return this.food ? petFoodBarWidth(this.food.percent) : 0;
  }
```

- [ ] **Step 4: Markup der Kachel schreiben**

In `tablet-toni.component.html` die Karte `--food` ersetzen:

```html
      <article class="tablet-toni__card tablet-toni__card--food" [attr.data-tone]="foodTone">
        <h3 class="tablet-toni__card-title">Futtervorrat</h3>
        <ng-container *ngIf="food as status; else foodMissing">
          <p class="tablet-toni__figure">
            {{ status.cansRemaining }}<span class="tablet-toni__unit">Dosen</span>
          </p>
          <div class="tablet-toni__bar">
            <div class="tablet-toni__bar-fill" [style.width.%]="foodBarWidth"></div>
          </div>
          <p class="tablet-toni__figure-note">
            reicht noch ca. {{ status.daysRemaining }} Tage · Ziel {{ status.targetCans }}
          </p>
        </ng-container>
        <ng-template #foodMissing>
          <p class="tablet-toni__hint">{{ foodError ?? 'Noch keine Daten.' }}</p>
        </ng-template>
      </article>
```

- [ ] **Step 5: Stile ergänzen**

In `tablet-toni.component.scss` innerhalb des Blocks `.tablet-toni` ergänzen:

```scss
  /* Der Zahlenwert ist auf einer Wandanzeige die eigentliche Information. */
  &__figure {
    margin: 0.2rem 0 0;
    display: flex;
    align-items: baseline;
    gap: 0.5rem;
    font-size: 3rem;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    line-height: 1;
    color: #f4f3f5;
  }

  &__unit {
    font-size: 1.1rem;
    font-weight: 500;
    color: rgba(228, 226, 228, 0.65);
  }

  &__figure-note {
    margin: 0.5rem 0 0;
    font-size: 1rem;
    color: rgba(228, 226, 228, 0.7);
  }

  &__bar {
    margin-top: 0.9rem;
    height: 1.1rem;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.07);
    overflow: hidden;
  }

  &__bar-fill {
    height: 100%;
    border-radius: 999px;
    background: #4ade80;
    transition: width 0.3s ease;
  }

  /* Dieselben drei Stufen wie auf der Seite /pet-food. */
  &__card--food[data-tone='warn'] &__bar-fill {
    background: #fbbf24;
  }

  &__card--food[data-tone='critical'] &__bar-fill {
    background: #ffb4ab;
  }

  &__card--food[data-tone='critical'] &__figure {
    color: #ffb4ab;
  }
```

- [ ] **Step 6: Tests laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: `14 SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/tablet-toni
git commit -m "feat(tablet): Futtervorrats-Kachel der Toni-Ansicht"
```

---

## Task 7: Kachel „Tracker-Status"

**Files:**
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.html`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss`
- Test: `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Ans Ende der Suite:

```ts
  it('zeigt Zuhause-Badge, Akku und Zeitpunkt des letzten Berichts', () => {
    const card = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--status');
    expect(card?.querySelector('.tablet-toni__badge')?.textContent?.trim()).toBe('Zu Hause');
    expect(card?.textContent).toContain('78');
    expect(card?.textContent).toContain('Zuletzt gesehen');
  });

  it('zeigt Unterwegs, wenn der Hund nicht zu Hause ist', () => {
    fixture.componentInstance.pet = { ...toni, atHome: false };
    fixture.detectChanges();

    const badge = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--status .tablet-toni__badge');
    expect(badge?.textContent?.trim()).toBe('Unterwegs');
  });

  it('zeigt gar kein Badge, wenn keine Aussage moeglich ist', () => {
    // atHome fehlt im JSON, wenn kein Zuhause hinterlegt ist oder keine Position
    // vorliegt. Ein geratenes "Zu Hause" waere hier schlimmer als gar nichts.
    const { atHome, ...ohneAussage } = toni;
    fixture.componentInstance.pet = ohneAussage as TractivePet;
    fixture.detectChanges();

    const badge = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--status .tablet-toni__badge');
    expect(badge).toBeNull();
  });
```

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: 3 Fails, jeweils `Expected null not to be null` bzw. `Expected undefined to be 'Zu Hause'`.

- [ ] **Step 3: Markup der Kachel schreiben**

In `tablet-toni.component.html` die Karte `--status` ersetzen:

```html
      <article class="tablet-toni__card tablet-toni__card--status">
        <h3 class="tablet-toni__card-title">Tracker</h3>
        <ng-container *ngIf="pet as tracker; else petMissing">
          <!--
            Fehlt atHome, wird KEIN Badge gezeigt statt geraten: das Feld fehlt im
            JSON, wenn keine Aussage moeglich ist (kein Zuhause hinterlegt oder
            keine Position). Dasselbe Verhalten wie auf der Seite /pets.
          -->
          <p class="tablet-toni__badge" *ngIf="tracker.atHome != null"
             [class.tablet-toni__badge--away]="!tracker.atHome">
            {{ tracker.atHome ? 'Zu Hause' : 'Unterwegs' }}
          </p>
          <p class="tablet-toni__figure" *ngIf="tracker.batteryPercent != null">
            {{ tracker.batteryPercent }}<span class="tablet-toni__unit">% Akku</span>
            <span class="material-symbols-outlined tablet-toni__charging"
                  *ngIf="tracker.charging">bolt</span>
          </p>
          <p class="tablet-toni__figure-note">Zone: {{ tracker.zone }}</p>
          <!--
            Bei einem Cloud-Ausfall liefert /v1/tractive/pets bewusst weiter die
            letzte bekannte Position. Dieser Zeitstempel ist die einzige Stelle,
            an der ihr Alter sichtbar wird.
          -->
          <p class="tablet-toni__figure-note" *ngIf="tracker.lastSeen">
            Zuletzt gesehen: {{ tracker.lastSeen | date:'short' }}
          </p>
        </ng-container>
        <ng-template #petMissing>
          <p class="tablet-toni__hint">{{ petError ?? 'Kein Tracker gefunden.' }}</p>
        </ng-template>
      </article>
```

- [ ] **Step 4: Stile ergänzen**

In `tablet-toni.component.scss` innerhalb von `.tablet-toni`:

```scss
  &__badge {
    align-self: flex-start;
    margin: 0 0 0.6rem;
    padding: 0.3rem 0.9rem;
    border-radius: 999px;
    font-size: 1rem;
    font-weight: 600;
    background: rgba(74, 222, 128, 0.16);
    color: #4ade80;

    &--away {
      background: rgba(251, 191, 36, 0.16);
      color: #fbbf24;
    }
  }

  &__charging {
    font-size: 1.6rem;
    color: #4ade80;
    align-self: center;
  }
```

- [ ] **Step 5: Tests laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: `17 SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/tablet-toni
git commit -m "feat(tablet): Tracker-Status-Kachel der Toni-Ansicht"
```

---

## Task 8: Kachel „Spaziergänge" mit Balkendiagramm und Zeitraumknöpfen

**Files:**
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.ts`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.html`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss`
- Test: `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Ans Ende der Suite:

```ts
  it('baut je Tag des Zeitraums einen Balken', () => {
    const options = fixture.componentInstance.walkChartOptions as {
      xAxis: { data: string[] };
      series: { data: number[] }[];
    };

    expect(options.xAxis.data.length).toBe(7);
    expect(options.series[0].data.length).toBe(7);
  });

  it('baut die Balken nach einem Zeitraumwechsel neu', () => {
    fixture.componentInstance.setWalkDays(30);

    const options = fixture.componentInstance.walkChartOptions as { xAxis: { data: string[] } };
    expect(options.xAxis.data.length).toBe(30);
  });

  it('zeigt die neue Achsenlaenge sofort, noch vor der Antwort des Servers', () => {
    // Genau dafuer ruft setWalkDays selbst rebuildWalkView auf. Mit einem Subject,
    // das erst spaeter emittiert, laesst sich das ueberhaupt pruefen - ein of(...)
    // kaeme synchron zurueck und verdeckte den Aufruf: der Test darueber bliebe
    // auch dann gruen, wenn setWalkDays gar nicht selbst neu baut.
    const pending = new Subject<TractiveWalk[]>();
    tractiveSpy.getWalks.and.returnValue(pending.asObservable());

    fixture.componentInstance.setWalkDays(14);

    const options = fixture.componentInstance.walkChartOptions as { xAxis: { data: string[] } };
    expect(options.xAxis.data.length).toBe(14);

    pending.complete();
  });

  it('zeigt die letzten drei Runden im Klartext', () => {
    const component = fixture.componentInstance;
    expect(component.recentWalks.length).toBe(1);
    expect(component.recentWalks[0].duration).toBe('36 min');
    expect(component.recentWalks[0].distance).toBe('2,1 km');
    expect(component.recentWalks[0].timeRange).toContain('Uhr');
  });

  it('kuerzt die Klartextliste auf drei Runden', () => {
    const component = fixture.componentInstance;
    component.walks = [runde, runde, runde, runde, runde];
    component.rebuildWalkView();

    expect(component.recentWalks.length).toBe(3);
  });

  it('zeigt einen Hinweis, wenn im Zeitraum keine Runde liegt', () => {
    const component = fixture.componentInstance;
    component.walks = [];
    component.rebuildWalkView();
    fixture.detectChanges();

    const card = (fixture.nativeElement as HTMLElement)
      .querySelector('.tablet-toni__card--walks');
    expect(card?.textContent).toContain('Keine Runde');
  });
```

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: Kompilierfehler `Property 'walkChartOptions' does not exist`.

- [ ] **Step 3: Die Komponente erweitern**

Imports ergänzen:

```ts
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import {
  walkDayLabel,
  walkDayTotals,
  walkDistance,
  walkDuration,
  walkTimeRange
} from '../../shared/walk-format.util';

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);
```

Den Decorator um die Direktive und den ECharts-Provider erweitern:

```ts
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
```

Vor der Klasse eine Zeile für die Klartext-Liste ergänzen:

```ts
/** Eine Runde, fertig formatiert fuer die Klartext-Liste unter dem Diagramm. */
interface RecentWalk {
  day: string;
  timeRange: string;
  duration: string;
  distance: string;
}

const AXIS_COLOR = '#94a3b8';
const BAR_COLOR = '#aac7ff';
```

In der Klasse ergänzen:

```ts
  walkChartOptions: Record<string, unknown> = {};
  recentWalks: RecentWalk[] = [];

  /** Baut Diagramm und Klartext-Liste aus den gehaltenen Runden neu. */
  rebuildWalkView(): void {
    const now = new Date();
    const totals = walkDayTotals(this.walks, this.walkDays, now);

    this.walkChartOptions = {
      grid: { left: 48, right: 12, top: 10, bottom: 24, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: totals.map(total => total.label),
        axisLabel: { color: AXIS_COLOR, fontSize: 12 }
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: AXIS_COLOR, fontSize: 12, formatter: '{value} min' },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      },
      series: [{
        type: 'bar',
        data: totals.map(total => total.minutes),
        itemStyle: { color: BAR_COLOR, borderRadius: [4, 4, 0, 0] }
      }]
    };

    // Der Server liefert die neuesten Runden zuerst.
    this.recentWalks = this.walks.slice(0, 3).map(walk => ({
      day: walkDayLabel(walk.start, now),
      timeRange: walkTimeRange(walk),
      duration: walkDuration(walk),
      distance: walkDistance(walk)
    }));
  }
```

Im `next`-Zweig von `loadWalks` hinter `this.walkError = null;` ergänzen:

```ts
        this.rebuildWalkView();
```

Und in `setWalkDays` vor dem Nachladen, damit die Achse sofort die neue Länge hat:

```ts
    this.walkDays = days;
    this.rebuildWalkView();
    this.loadWalks(false);
```

Damit die Kachel auch ohne Tracker ein leeres Diagramm statt `{}` zeigt, in `ngOnInit` vor `this.load(false)` ergänzen:

```ts
    this.rebuildWalkView();
```

- [ ] **Step 4: Markup der Kachel schreiben**

In `tablet-toni.component.html` die Karte `--walks` ersetzen:

```html
      <article class="tablet-toni__card tablet-toni__card--walks">
        <h3 class="tablet-toni__card-title">
          <span>Spaziergänge</span>
          <span class="tablet-toni__ranges" role="group" aria-label="Zeitraum">
            <button
              type="button"
              class="tablet-toni__range"
              *ngFor="let days of walkRanges"
              [class.tablet-toni__range--active]="days === walkDays"
              (click)="setWalkDays(days)">
              {{ days }} Tage
            </button>
          </span>
        </h3>
        <div
          echarts
          class="tablet-toni__chart"
          [options]="walkChartOptions"
          [autoResize]="true"></div>
        <ul class="tablet-toni__walks" *ngIf="recentWalks.length > 0; else noWalks">
          <li class="tablet-toni__walk" *ngFor="let walk of recentWalks">
            <span class="tablet-toni__walk-day">{{ walk.day }}</span>
            <span>{{ walk.timeRange }}</span>
            <span class="tablet-toni__walk-figures">{{ walk.duration }} · {{ walk.distance }}</span>
          </li>
        </ul>
        <ng-template #noWalks>
          <p class="tablet-toni__walks-hint">
            {{ walkError ?? 'Keine Runde in diesem Zeitraum.' }}
          </p>
        </ng-template>
      </article>
```

- [ ] **Step 5: Stile ergänzen**

In `tablet-toni.component.scss` innerhalb von `.tablet-toni`:

```scss
  &__ranges {
    display: inline-flex;
    gap: 0.2rem;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 10px;
    padding: 0.2rem;
    flex: 0 0 auto;
  }

  &__range {
    border: none;
    background: transparent;
    padding: 0.35rem 0.7rem;
    border-radius: 8px;
    cursor: pointer;
    font: inherit;
    font-size: 0.8rem;
    font-weight: 500;
    color: rgba(228, 226, 228, 0.75);

    &--active {
      background: rgba(170, 199, 255, 0.16);
      color: #aac7ff;
    }
  }

  /* Das Diagramm nimmt die Resthoehe, die Klartext-Liste bleibt darunter stehen. */
  &__chart {
    flex: 1 1 auto;
    width: 100%;
    min-height: 0;
  }

  &__walks {
    flex: 0 0 auto;
    list-style: none;
    margin: 0.4rem 0 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
  }

  &__walk {
    display: flex;
    align-items: baseline;
    gap: 0.6rem;
    font-size: 0.95rem;
    color: rgba(228, 226, 228, 0.8);
    font-variant-numeric: tabular-nums;
  }

  &__walk-day {
    min-width: 4.5rem;
    font-weight: 600;
    color: #f4f3f5;
  }

  &__walk-figures {
    margin-left: auto;
    color: #f4f3f5;
  }

  &__walks-hint {
    flex: 0 0 auto;
    margin: 0.4rem 0 0;
    font-size: 0.95rem;
    color: #94a3b8;
  }
```

- [ ] **Step 6: Tests laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: `23 SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/tablet-toni
git commit -m "feat(tablet): Spaziergangs-Kachel mit Tagesbalken und Zeitraumwahl"
```

---

## Task 9: Kachel „Karte"

Die Leaflet-Icon-Korrektur steht heute als lokale Funktion in `pets.component.ts`. Statt sie zu kopieren, bekommt sie eine gemeinsame Datei.

**Files:**
- Create: `frontend/src/app/shared/leaflet-icons.util.ts`
- Modify: `frontend/src/app/pages/pets/pets.component.ts:1-23`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.ts`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.html`
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss`
- Test: `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Ans Ende der Suite:

```ts
  it('baut die Karte auf, sobald eine Position vorliegt', () => {
    const card = (fixture.nativeElement as HTMLElement).querySelector('.tablet-toni__card--map');
    // Leaflet haengt seine eigene Klasse an den Container, sobald die Karte steht.
    expect(card?.querySelector('.leaflet-container')).not.toBeNull();
  });

  it('zeigt einen Hinweis statt einer leeren Flaeche, wenn keine Position vorliegt', () => {
    const { latitude, longitude, ...ohnePosition } = toni;
    tractiveSpy.getPets.and.returnValue(of([ohnePosition as TractivePet]));

    const fresh = TestBed.createComponent(TabletToniComponent);
    fresh.detectChanges();

    const card = (fresh.nativeElement as HTMLElement).querySelector('.tablet-toni__card--map');
    expect(card?.querySelector('.tablet-toni__hint')?.textContent).toContain('Keine Position');
    fresh.destroy();
  });
```

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: 2 Fails, `Expected null not to be null`.

- [ ] **Step 3: Icon-Korrektur in ein gemeinsames Util ziehen**

Datei `frontend/src/app/shared/leaflet-icons.util.ts`:

```ts
import * as L from 'leaflet';

/**
 * Leaflet ermittelt die Standard-Marker-Icons ueber eine relative URL zum
 * Bundle; im Angular-Build zeigt die ins Leere. Die Dateien werden deshalb
 * lokal ausgeliefert (angular.json-Assets-Glob) und hier fest verdrahtet -
 * bewusst NICHT von einem CDN: das Dashboard muss ohne Internet funktionieren.
 *
 * Mehrfaches Aufrufen ist unschaedlich; die Funktion setzt nur Optionen.
 */
export function useLocalLeafletIcons(): void {
  const iconPrototype = L.Icon.Default.prototype as L.Icon.Default & { _getIconUrl?: unknown };
  delete iconPrototype._getIconUrl;
  L.Icon.Default.mergeOptions({
    iconRetinaUrl: 'assets/leaflet/marker-icon-2x.png',
    iconUrl: 'assets/leaflet/marker-icon.png',
    shadowUrl: 'assets/leaflet/marker-shadow.png'
  });
}
```

In `frontend/src/app/pages/pets/pets.component.ts` die lokale Funktion `fixLeafletDefaultIcon` samt Kommentar und Aufruf (Zeilen 8-23) durch den Import und einen Aufruf ersetzen:

```ts
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();
```

- [ ] **Step 4: Die Komponente erweitern**

Imports und Klassendeklaration anpassen:

```ts
import { AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
import * as L from 'leaflet';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();
```

Die Klasse implementiert zusätzlich `AfterViewInit`:

```ts
export class TabletToniComponent implements OnInit, AfterViewInit, OnDestroy {
```

In der Klasse ergänzen:

```ts
  /**
   * Der Kartencontainer steht IMMER im DOM, auch ohne Position - sonst haenge
   * der Aufbau der Karte an der Reihenfolge von Datenankunft und
   * Change-Detection, und der erste Abruf liefe ins Leere.
   */
  @ViewChild('mapContainer') private mapContainer?: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private marker?: L.Marker;
  private viewReady = false;

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.renderMap();
  }
```

`ngOnDestroy` um das Aufräumen der Karte erweitern:

```ts
  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
    this.map?.remove();
    this.map = undefined;
    this.marker = undefined;
  }
```

Den Getter für den Hinweis und das Zeichnen ergänzen:

```ts
  /** true, sobald eine verwertbare Position vorliegt. */
  get hasPosition(): boolean {
    return this.pet?.latitude != null && this.pet?.longitude != null;
  }

  /**
   * Baut die Karte beim ersten Datensatz auf und verschiebt danach nur den
   * Marker - ein Neuaufbau bei jedem Refresh wuerde die Kacheln neu laden.
   */
  private renderMap(): void {
    if (!this.viewReady || !this.hasPosition) {
      return;
    }
    const container = this.mapContainer?.nativeElement;
    if (!container) {
      return;
    }
    const position: L.LatLngExpression = [this.pet!.latitude!, this.pet!.longitude!];
    if (!this.map) {
      this.map = L.map(container).setView(position, 16);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
      }).addTo(this.map);
    }
    if (this.marker) {
      this.marker.setLatLng(position);
    } else {
      this.marker = L.marker(position).addTo(this.map).bindPopup(this.pet!.name);
    }
    this.map.setView(position);
  }
```

Im `next`-Zweig von `loadPet` hinter `this.loadWalks(silent);` ergänzen:

```ts
        this.renderMap();
```

- [ ] **Step 5: Markup der Kachel schreiben**

In `tablet-toni.component.html` die Karte `--map` ersetzen:

```html
      <article class="tablet-toni__card tablet-toni__card--map">
        <h3 class="tablet-toni__card-title">Standort</h3>
        <!--
          Der Container bleibt immer im DOM; nur so ist er beim ersten
          Positionsdatensatz sicher da. Ohne Position liegt der Hinweis darueber.
        -->
        <div class="tablet-toni__map-frame">
          <div class="tablet-toni__map" #mapContainer></div>
          <p class="tablet-toni__hint tablet-toni__hint--overlay" *ngIf="!hasPosition">
            {{ petError ?? 'Keine Position verfügbar.' }}
          </p>
        </div>
      </article>
```

- [ ] **Step 6: Stile ergänzen**

In `tablet-toni.component.scss` innerhalb von `.tablet-toni`:

```scss
  &__map-frame {
    position: relative;
    flex: 1 1 auto;
    min-height: 0;
    border-radius: 0.75rem;
    overflow: hidden;
  }

  &__map {
    width: 100%;
    height: 100%;
  }

  /*
    Deckt die Karte ab, solange keine Position vorliegt. Leaflet zeichnet in den
    Container, sobald es ihn bekommt - der Hinweis liegt deshalb darueber statt
    an seiner Stelle.
  */
  &__hint--overlay {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
  }
```

- [ ] **Step 7: Tests laufen lassen und grün sehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: `25 SUCCESS`.

- [ ] **Step 8: Auch die /pets-Suite prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: exakt die 3 Baseline-Fails, sonst grün.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/shared/leaflet-icons.util.ts frontend/src/app/pages/pets/pets.component.ts frontend/src/app/pages/tablet-toni
git commit -m "feat(tablet): Karten-Kachel der Toni-Ansicht"
```

---

## Task 10: Höhenkette absichern

Die Kacheln müssen mit dem Bildschirm wachsen. Fehlt `flex: 1` oder `min-height: 0` an einer einzigen Stelle der Kette, schrumpft das Diagramm auf fast null — genau so ist es bei `/tablet/temperatures` passiert.

**Files:**
- Test: `frontend/src/app/pages/tablet-toni/tablet-toni.component.spec.ts`
- Modify (falls der Test es aufdeckt): `frontend/src/app/pages/tablet-toni/tablet-toni.component.scss`

- [ ] **Step 1: Den Test schreiben**

Ans Ende der Suite:

```ts
  it('gibt den Kacheln die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Der Rahmen spielt die Flex-Spalte der App-Shell nach: nur wenn die Kette vom
    // Host bis in die Kacheln durchgehend ist, waechst der Inhalt mit dem Schirm.
    //
    // Bewusst ein EIGENER Container statt des Elternknotens: das Fixture haengt
    // direkt im <body>, und dort stehen auch Karmas eigene Elemente und die
    // Wurzelknoten schon gelaufener Suiten. Macht man den body zur Flex-Spalte,
    // teilen sich all diese Geschwister die 600 px, und der Graph wird je nach
    // Reihenfolge der Suiten winzig - der Test schlaegt dann sporadisch fehl.
    const host = fixture.nativeElement as HTMLElement;
    const frame = document.createElement('div');
    frame.style.display = 'flex';
    frame.style.flexDirection = 'column';
    document.body.appendChild(frame);
    frame.appendChild(host);

    const chartHeight = (): number =>
      host.querySelector('.tablet-toni__chart')!.getBoundingClientRect().height;

    frame.style.height = '600px';
    fixture.detectChanges();
    const small = chartHeight();

    frame.style.height = '900px';
    fixture.detectChanges();
    const large = chartHeight();

    expect(small).toBeGreaterThan(80);
    // 300 px mehr Bildschirm verteilen sich auf die zwei Rasterzeilen.
    expect(large).toBeGreaterThan(small + 100);

    // Den Host zurueck in den body haengen, damit fixture.destroy() unveraendert laeuft.
    document.body.appendChild(host);
    frame.remove();
  });
```

- [ ] **Step 2: Test laufen lassen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'
```

Erwartet: grün, wenn die Kette aus Task 4 vollständig ist. Schlägt er fehl (typisch: `small` liegt unter 80), fehlt ein Glied — prüfen in dieser Reihenfolge: `:host`, `.tablet-toni`, `.tablet-toni__grid`, `.tablet-toni__card`, `.tablet-toni__chart`. Jedes braucht `flex: 1` bzw. `flex: 1 1 auto` **und** `min-height: 0`.

- [ ] **Step 3: Gesamten Frontend-Lauf machen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: exakt die 3 Baseline-Fails (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`). Jeder weitere Fail ist eine Regression.

- [ ] **Step 4: Produktions-Build prüfen**

```bash
cd frontend && npm run build -- --configuration production
```

Erwartet: erfolgreicher Build. Achtung auf ein Größenbudget-ERROR für `dashboard.component.scss` — das ist vorbestehend und keine Regression dieser Arbeit; ein Budget-Fehler für `tablet-toni.component.scss` wäre dagegen einer.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/tablet-toni
git commit -m "test(tablet): Hoehenkette der Toni-Ansicht festhalten"
```

---

## Task 11: Dokumentation nachziehen

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Tablet-Ansichten (Unterseiten des Wandtablets)")

- [ ] **Step 1: Abschnitt ergänzen**

Am Ende des Abschnitts „Tablet-Ansichten" einfügen:

```markdown
- Dritte Ansicht: `/tablet/toni` (`pages/tablet-toni/`). 2×2-Raster: Futtervorrat, Spaziergänge, Tracker-Status, Karte. **Rein anzeigend** — auf dem KIOSK-Wandtablet wären Futter-Buchungen ohnehin MEMBER und lieferten 403; alle drei gelesenen Endpunkte sind über die generische `GET /v1/**`-Regel KIOSK-lesbar
- Die drei Quellen (`PetFoodService.getStatus`, `TractiveService.getPets`, `TractiveService.getWalks`) laufen **unabhängig**: fällt Tractive aus, steht der Futtervorrat trotzdem da. Die Spaziergänge hängen an der `trackerId` aus `getPets` und werden ohne Tracker gar nicht erst angefragt
- Die Ansicht zeigt das **erste** Tier aus `getPets()`. Bei mehreren Tieren wäre die 2×2-Aufteilung hinfällig — das wäre eine eigene Entscheidung
- Zwei Utils wurden dafür aus `dashboard.component.ts` herausgelöst und sind jetzt die einzige Definition: `shared/pet-food-level.util.ts` (Schwelle 7 Dosen, Warnung unter 25 %) und `shared/walk-format.util.ts` (Dauer, Distanz, Zeitspanne, Tagesgruppierung, Tagesbalken). **Die Schwelle 7 steht damit nur noch zweimal:** hier und im Telegram-Flow auf `sensor.pet_food_toni_cans`, der in der Flow-Engine lebt und von Hand nachgezogen werden muss
- Der Zeitraum der Spaziergänge ist 7 / 14 / 30 Tage. Dafür wurde `TractiveWalkService.MAX_DAYS` von 14 auf 30 angehoben — ohne das hätte `Math.clamp` den 30-Tage-Knopf **stumm** auf 14 geklemmt. **Preis:** der erste 30-Tage-Abruf löst bis zu 30 einzelne Cloud-Häppchen aus und trifft realistisch das Rate-Limit; dann zeigt die Kachel die Tage, die durchkamen. Je nach Abo reicht die Tractive-Historie ohnehin nicht 30 Tage zurück — dann bleiben die hinteren Säulen dauerhaft leer, ohne Fehler
- Tage ohne Runde sind eine **leere Säule**, kein fehlender Eintrag — eine Lücke sähe aus wie ein Datenloch
- Der Kartencontainer steht **immer** im DOM (Hinweis bei fehlender Position liegt als Overlay darüber); läge er hinter einem `*ngIf`, hinge der Kartenaufbau an der Reihenfolge von Datenankunft und Change-Detection. Die OSM-Kacheln kommen aus dem Internet: ohne Verbindung bleibt diese eine Kachel grau, die anderen drei laufen weiter. Die Marker-Icons kommen lokal aus `assets/leaflet` (`shared/leaflet-icons.util.ts`, geteilt mit `/pets`)
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): Tablet-Ansicht Toni festhalten"
```

---

## Abschluss

Nach Task 11 ist die Arbeit vollständig. Für die Integration nach `main` die Skill `superpowers:finishing-a-development-branch` verwenden.

**Was danach noch offen bleibt und bewusst nicht Teil dieses Plans ist:**

- Die Ansicht wurde nie gegen einen echten Tractive-Account verifiziert (es gibt keine Zugangsdaten auf dieser Maschine). Ob die 30-Tage-Historie real etwas liefert und wie oft das Rate-Limit greift, zeigt sich erst im Betrieb.
- Die Schwelle „7 Dosen" bleibt zwischen Frontend und Telegram-Flow zwangsläufig doppelt.
