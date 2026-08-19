# Ausschalt-Bestätigung für Geräte-Schalter — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Geschützte Schalter dürfen im UI nur nach Bestätigung ausgeschaltet werden; Einschalten bleibt überall direkt.

**Architecture:** Das bestehende `confirm_required`-Flag auf `entity_states` wird umgedeutet auf „nur beim Ausschalten bestätigen". `GET /api/devices` reichert jedes Gerät mit diesem Flag aus der zugehörigen Switch-Entität an (gleiche Id-Konstruktion wie `SmartDeviceEntityMapper`), sodass die Geräteseite ohne zweiten Request und ohne Mapping-Wissen auskommt. Dashboard und Geräteseite bekommen die Nur-Aus-Semantik; API, Flows und Telegram bleiben unverändert.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / JUnit 5 + Mockito; Angular 19 standalone / Karma + Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-19-ausschalt-bestaetigung-design.md`

**Kommandos dieser Maschine:**
- Backend: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` vor jedem `mvn`, aus `backend/` ausführen.
- Frontend: aus `frontend/` ausführen, headless: `npx ng test --watch=false --browsers=ChromeHeadless`.
- Bekannte Vorab-Fails (ignorieren, nicht durch diese Arbeit verursacht): Backend `HouseholdManagerApplicationTests` + `HealthControllerTest` (lokale Test-DB fehlt, 3 Errors); Frontend 3 vorbestehende Fails (App/Hero).

---

## Dateiübersicht

| Datei | Verantwortung | Aktion |
|---|---|---|
| `backend/src/main/java/com/household/manager/dto/SmartDeviceResponse.java` | Feld `confirmRequired` | Ändern |
| `backend/src/main/java/com/household/manager/service/SmartDeviceService.java` | Anreicherung in `toResponse` | Ändern |
| `backend/src/test/java/com/household/manager/service/SmartDeviceConfirmRequiredTest.java` | Test der Anreicherung | Neu |
| `frontend/src/app/models/smart-device.model.ts` | Feld `confirmRequired` | Ändern |
| `frontend/src/app/components/smart-device-list/smart-device-list.component.ts` | Dialog-Zustand + Nur-Aus-Guard | Ändern |
| `frontend/src/app/components/smart-device-list/smart-device-list.component.html` | Dialog-Markup | Ändern |
| `frontend/src/app/components/smart-device-list/smart-device-list.component.scss` | Dialog-Styles | Ändern |
| `frontend/src/app/components/smart-device-list/smart-device-list.component.spec.ts` | Tests der Geräteseite | Ändern |
| `frontend/src/app/pages/dashboard/dashboard.component.ts` | Nur-Aus-Guard | Ändern |
| `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` | Specs auf neue Semantik | Ändern |
| `frontend/src/app/pages/entities/entities.component.html` | Beschriftung | Ändern |

---

## Task 1: Backend liefert `confirmRequired` in der Geräteliste

**Files:**
- Test: `backend/src/test/java/com/household/manager/service/SmartDeviceConfirmRequiredTest.java` (neu)
- Modify: `backend/src/main/java/com/household/manager/dto/SmartDeviceResponse.java`
- Modify: `backend/src/main/java/com/household/manager/service/SmartDeviceService.java`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Neue Datei `backend/src/test/java/com/household/manager/service/SmartDeviceConfirmRequiredTest.java`:

```java
package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.SmartDeviceEntityMapper;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaService;
import com.household.manager.meross.service.MerossDeviceService;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.tapo.TapoDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Die Geraeteliste traegt das Bestaetigungs-Flag der zugehoerigen Switch-Entitaet
 * mit, damit die Geraeteseite den Ausschalt-Schutz ohne zweiten Request und ohne
 * eigene Kenntnis der entityId-Konvention anzeigen kann.
 */
class SmartDeviceConfirmRequiredTest {

    private SmartDeviceRepository repository;
    private EntityStateService entityStateService;
    private SmartDeviceService service;

    @BeforeEach
    void setUp() {
        repository = mock(SmartDeviceRepository.class);
        entityStateService = mock(EntityStateService.class);
        service = new SmartDeviceService(
                repository,
                mock(KasaService.class),
                mock(KasaDiscoveryService.class),
                mock(MerossDeviceService.class),
                mock(TapoDeviceService.class),
                new ObjectMapper(),
                new SmartDeviceEntityMapper(),
                entityStateService,
                mock(AuditService.class));
    }

    @Test
    @DisplayName("Gesetztes Flag der Switch-Entitaet erscheint in der Geraeteliste")
    void reportsConfirmRequiredFromEntityState() {
        when(repository.findAllByOrderByDeviceTypeAscDeviceNameAsc()).thenReturn(List.of(device()));
        EntityState guarded = new EntityState();
        guarded.setConfirmRequired(true);
        when(entityStateService.getByEntityId("switch.tapo_dev1")).thenReturn(Optional.of(guarded));

        List<SmartDeviceResponse> devices = service.getAllDevices();

        assertTrue(devices.get(0).isConfirmRequired(),
                "Flag der gespiegelten Switch-Entitaet muss durchgereicht werden");
    }

    @Test
    @DisplayName("Geraet ohne gespiegelte Entitaet meldet kein Bestaetigungs-Flag")
    void defaultsToFalseWithoutEntityState() {
        when(repository.findAllByOrderByDeviceTypeAscDeviceNameAsc()).thenReturn(List.of(device()));
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());

        List<SmartDeviceResponse> devices = service.getAllDevices();

        assertFalse(devices.get(0).isConfirmRequired());
    }

    private SmartDevice device() {
        SmartDevice device = new SmartDevice();
        device.setId(1L);
        device.setDeviceType(DeviceType.TAPO);
        device.setExternalDeviceId("DEV1");
        device.setDeviceName("Stehlampe");
        device.setOnline(true);
        device.setPoweredOn(true);
        device.setCapabilities("SWITCH");
        return device;
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag prüfen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=SmartDeviceConfirmRequiredTest`
Expected: Kompilierfehler `cannot find symbol: method isConfirmRequired()` — das Feld existiert noch nicht.

- [ ] **Step 3: Feld im DTO ergänzen**

In `SmartDeviceResponse.java` nach dem `colorTemp`-Feld (Zeile 96) einfügen:

```java
    /**
     * Ob dieses Geraet im UI nur nach Bestaetigung AUSgeschaltet werden darf. Kommt aus dem
     * {@code confirm_required}-Flag der gespiegelten Switch-Entitaet, nicht aus der
     * Geraetetabelle - gepflegt wird es auf der Entitaeten-Seite. Reiner Bedienschutz:
     * API, Flows und Telegram schalten weiterhin ungefragt.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("confirmRequired")
    private boolean confirmRequired;
```

- [ ] **Step 4: Anreicherung im Service implementieren**

In `SmartDeviceService.java` die Methode `toResponse` (ab Zeile 1081) um den Builder-Aufruf ergänzen — direkt nach `.capabilities(parseCapabilities(entity.getCapabilities()))`:

```java
                .confirmRequired(isConfirmRequired(entity))
```

Und darunter, neben den anderen privaten Helfern, diese Methode ergänzen:

```java
    /**
     * Liest das Bestaetigungs-Flag aus der gespiegelten Switch-Entitaet. Die entityId wird mit
     * exakt derselben Konstruktion gebildet wie in {@link SmartDeviceEntityMapper#map} - beide
     * Stellen muessen dieselbe Id ergeben, sonst zeigt die Geraeteseite einen Schutz an, den es
     * an der Entitaet nicht gibt (oder umgekehrt). Ohne gespiegelte Entitaet gilt "kein Schutz".
     */
    private boolean isConfirmRequired(SmartDevice device) {
        try {
            String entityId = EntityIds.build(EntityDomain.SWITCH,
                    EntitySource.valueOf(device.getDeviceType().name()),
                    device.getExternalDeviceId(), null);
            return entityStateService.getByEntityId(entityId)
                    .map(EntityState::isConfirmRequired)
                    .orElse(false);
        } catch (Exception ex) {
            log.debug("Bestaetigungs-Flag fuer {} nicht ermittelbar: {}",
                    device.getExternalDeviceId(), ex.getMessage());
            return false;
        }
    }
```

Dazu die Imports ergänzen:

```java
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
```

- [ ] **Step 5: Test laufen lassen, Erfolg prüfen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=SmartDeviceConfirmRequiredTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0` und `BUILD SUCCESS`

- [ ] **Step 6: Bestehende Backend-Tests prüfen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test 2>&1 | grep -E "Tests run:.*Errors|BUILD"`
Expected: nur die 3 bekannten Errors (`HouseholdManagerApplicationTests`, `HealthControllerTest`). Jede weitere Abweichung ist eine echte Regression und muss behoben werden.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/SmartDeviceResponse.java backend/src/main/java/com/household/manager/service/SmartDeviceService.java backend/src/test/java/com/household/manager/service/SmartDeviceConfirmRequiredTest.java
git commit -m "feat(devices): Bestaetigungs-Flag in der Geraeteliste mitliefern

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: Dashboard bestätigt nur noch beim Ausschalten

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts:416-426`
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`

- [ ] **Step 1: Bestehende Specs auf die neue Semantik umstellen (fehlschlagend)**

In `dashboard.component.spec.ts` die vier Confirm-Specs anpassen. Sie nutzen bisher `entity({ confirmRequired: true })` — der Fabrik-Default ist `state: 'off'`, was mit der neuen Semantik NICHT mehr fragen darf. Überall `state: 'on'` ergänzen:

Zeile 187: `fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true, state: 'on' }));`
Zeile 199: `const guarded = entity({ confirmRequired: true, state: 'on' });`
Zeile 215: `fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true, state: 'on' }));`
Zeile 244: `const guarded = entity({ confirmRequired: true, state: 'on' });`

In den beiden Specs, die nach der Bestätigung den Zustand prüfen (Zeile 207 und der Dialog-Spec), erwartet der Test bisher `state` `'on'` nach dem Toggle. Da jetzt ein eingeschalteter Schalter ausgeschaltet wird, dort auf `'off'` ändern:

Zeile 207: `expect(fixture.componentInstance.topSwitches[0].state).toBe('off');`

Zusätzlich diesen neuen Spec ans Ende der Datei (vor der schließenden `});`) einfügen:

```typescript
  it('einschalten eines geschuetzten schalters fragt nicht nach', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true, state: 'off' }));
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

    discardPeriodicTasks();
  }));
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag prüfen**

Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'`
Expected: Der neue Spec `einschalten eines geschuetzten schalters fragt nicht nach` schlägt fehl (`toggle` wurde nicht aufgerufen, `confirmSwitch` ist gesetzt), weil die Komponente noch beide Richtungen bestätigt.

- [ ] **Step 3: Guard in der Komponente umstellen**

In `dashboard.component.ts` die Methode `toggleSwitch` (Zeile 416) ändern — Bedingung und Doc-Kommentar:

```typescript
  /**
   * Schaltet einen Schalter. Geschuetzte Schalter oeffnen beim AUSschalten den
   * Bestaetigungsdialog; erst der Klick auf den Schalter im Dialog fuehrt den Toggle
   * aus. Einschalten laeuft immer direkt - ein versehentliches Einschalten ist
   * harmlos, ein versehentliches Ausschalten (Kuehlschrank, Router) nicht.
   */
  toggleSwitch(entity: SwitchEntity): void {
    if (this.pendingSwitchIds.has(entity.entityId)) {
      return;
    }
    if (entity.confirmRequired && entity.state === 'on') {
      this.confirmSwitch = entity;
      this.confirmSwitchList = [entity];
      return;
    }
    this.executeToggle(entity);
  }
```

- [ ] **Step 4: Tests laufen lassen, Erfolg prüfen**

Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'`
Expected: alle Dashboard-Specs grün.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(dashboard): Bestaetigung nur noch beim Ausschalten

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: Geräteseite fragt vor dem Ausschalten nach

**Files:**
- Modify: `frontend/src/app/models/smart-device.model.ts`
- Modify: `frontend/src/app/components/smart-device-list/smart-device-list.component.ts:295-315`
- Modify: `frontend/src/app/components/smart-device-list/smart-device-list.component.html`
- Modify: `frontend/src/app/components/smart-device-list/smart-device-list.component.scss`
- Test: `frontend/src/app/components/smart-device-list/smart-device-list.component.spec.ts`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

In `smart-device-list.component.spec.ts` ans Ende der Datei (vor der schließenden `});`) einfügen:

```typescript
  describe('Ausschalt-Bestaetigung', () => {
    const guardedDevice: SmartDevice = {
      ...device, id: 9, deviceName: 'Kuehlschrank', isPoweredOn: true, confirmRequired: true
    } as SmartDevice;

    beforeEach(() => {
      serviceSpy.getAllDevices.and.returnValue(of([guardedDevice]));
      serviceSpy.refreshDeviceState.and.returnValue(of(guardedDevice));
    });

    it('oeffnet beim Ausschalten den Dialog statt zu schalten', () => {
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();

      fixture.componentInstance.toggleDevice(guardedDevice);

      expect(serviceSpy.turnOff).not.toHaveBeenCalled();
      expect(fixture.componentInstance.confirmOffDevice?.id).toBe(9);
    });

    it('schaltet nach Bestaetigung aus und schliesst den Dialog', () => {
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();
      fixture.componentInstance.toggleDevice(guardedDevice);

      fixture.componentInstance.confirmTurnOff();

      expect(serviceSpy.turnOff).toHaveBeenCalledWith(9);
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });

    it('abbrechen schliesst den Dialog ohne zu schalten', () => {
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();
      fixture.componentInstance.toggleDevice(guardedDevice);

      fixture.componentInstance.closeConfirmOffDialog();

      expect(serviceSpy.turnOff).not.toHaveBeenCalled();
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });

    it('einschalten eines geschuetzten Geraets fragt nicht nach', () => {
      const offDevice = { ...guardedDevice, isPoweredOn: false } as SmartDevice;
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();

      fixture.componentInstance.toggleDevice(offDevice);

      expect(serviceSpy.turnOn).toHaveBeenCalledWith(9);
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });

    it('ungeschuetztes Geraet schaltet weiterhin direkt aus', () => {
      const plain = { ...device, isPoweredOn: true } as SmartDevice;
      const fixture = TestBed.createComponent(SmartDeviceListComponent);
      fixture.detectChanges();

      fixture.componentInstance.toggleDevice(plain);

      expect(serviceSpy.turnOff).toHaveBeenCalledWith(1);
      expect(fixture.componentInstance.confirmOffDevice).toBeNull();
    });
  });
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag prüfen**

Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/smart-device-list.component.spec.ts'`
Expected: Kompilierfehler `Property 'confirmOffDevice' does not exist` bzw. `'confirmTurnOff' does not exist` — Feld und Methoden fehlen noch.

- [ ] **Step 3: Modell erweitern**

In `frontend/src/app/models/smart-device.model.ts` im Interface `SmartDevice` nach `colorTemp?: number;` (Zeile 23) einfügen:

```typescript
  /**
   * Ob dieses Geraet nur nach Bestaetigung AUSgeschaltet werden darf. Kommt aus dem
   * `confirm_required`-Flag der gespiegelten Switch-Entitaet (gepflegt auf der
   * Entitaeten-Seite), nicht aus der Geraetetabelle. Reiner Bedienschutz im UI.
   */
  confirmRequired?: boolean;
```

- [ ] **Step 4: Komponente umstellen**

In `smart-device-list.component.ts` bei den anderen Zustandsfeldern (neben `togglingDevices`, ca. Zeile 70) ergänzen:

```typescript
  /** Geraet, dessen Ausschalten gerade bestaetigt werden muss; null = kein Dialog offen. */
  confirmOffDevice: SmartDevice | null = null;
```

Und die Methode `toggleDevice` (Zeile 295) ersetzen durch:

```typescript
  /**
   * Schaltet ein Geraet. Geschuetzte Geraete oeffnen beim AUSschalten erst den
   * Bestaetigungsdialog; Einschalten laeuft immer direkt.
   */
  toggleDevice(device: SmartDevice): void {
    if (!device.isOnline || this.isDeviceToggling(device.id)) {
      return;
    }
    if (device.confirmRequired && device.isPoweredOn) {
      this.confirmOffDevice = device;
      return;
    }
    this.executeToggle(device);
  }

  /** Bestaetigung im Dialog: schliesst ihn und schaltet aus. */
  confirmTurnOff(): void {
    const device = this.confirmOffDevice;
    this.confirmOffDevice = null;
    if (device) {
      this.executeToggle(device);
    }
  }

  closeConfirmOffDialog(): void {
    this.confirmOffDevice = null;
  }

  private executeToggle(device: SmartDevice): void {
    this.togglingDevices.add(device.id);
    const action = device.isPoweredOn
      ? this.smartDeviceService.turnOff(device.id)
      : this.smartDeviceService.turnOn(device.id);

    action.subscribe({
      next: () => {
        device.isPoweredOn = !device.isPoweredOn;
        this.togglingDevices.delete(device.id);
      },
      error: (error: Error) => {
        this.errorMessage = `Fehler beim Schalten von ${device.deviceName}: ${error.message}`;
        this.togglingDevices.delete(device.id);
      }
    });
  }
```

- [ ] **Step 5: Dialog-Markup ergänzen**

In `smart-device-list.component.html` ganz am Ende der Datei anfügen:

```html
<div class="confirm-backdrop" *ngIf="confirmOffDevice" (click)="closeConfirmOffDialog()">
  <div class="confirm-dialog" (click)="$event.stopPropagation()">
    <h3 class="confirm-dialog__title">Wirklich ausschalten?</h3>
    <p class="confirm-dialog__text">
      <strong>{{ confirmOffDevice.deviceName }}</strong> ist gegen versehentliches
      Ausschalten gesichert.
    </p>
    <div class="confirm-dialog__actions">
      <button type="button" class="confirm-dialog__cancel" (click)="closeConfirmOffDialog()">
        Abbrechen
      </button>
      <button type="button" class="confirm-dialog__confirm" (click)="confirmTurnOff()">
        Ausschalten
      </button>
    </div>
  </div>
</div>
```

- [ ] **Step 6: Styles ergänzen**

In `smart-device-list.component.scss` am Ende anfügen:

```scss
.confirm-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.confirm-dialog {
  background: #fff;
  border-radius: 12px;
  padding: 1.5rem;
  max-width: 22rem;
  width: calc(100% - 2rem);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25);

  &__title {
    margin: 0 0 0.75rem;
    font-size: 1.1rem;
  }

  &__text {
    margin: 0 0 1.25rem;
    color: #444;
  }

  &__actions {
    display: flex;
    gap: 0.75rem;
    justify-content: flex-end;
  }

  &__cancel,
  &__confirm {
    padding: 0.5rem 1rem;
    border-radius: 8px;
    border: none;
    cursor: pointer;
    font-weight: 600;
  }

  &__cancel {
    background: #eceff1;
    color: #333;
  }

  &__confirm {
    background: #d32f2f;
    color: #fff;
  }
}
```

- [ ] **Step 7: Tests laufen lassen, Erfolg prüfen**

Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/smart-device-list.component.spec.ts'`
Expected: alle Specs dieser Komponente grün, inklusive der fünf neuen.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/models/smart-device.model.ts frontend/src/app/components/smart-device-list/
git commit -m "feat(devices): Ausschalten geschuetzter Geraete nur mit Bestaetigung

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: Beschriftung und Dokumentation der neuen Semantik

**Files:**
- Modify: `frontend/src/app/pages/entities/entities.component.html:104`
- Modify: `backend/src/main/java/com/household/manager/model/entity/EntityState.java:43-46`
- Modify: `CLAUDE.md:173-174`

- [ ] **Step 1: Beschriftung ändern**

Die Semantik muss an der Pflege-Stelle ablesbar sein. In `entities.component.html` Zeile 104 ersetzen:

```html
                    Bestätigung beim Ausschalten
```

- [ ] **Step 2: Javadoc der Entität nachziehen**

Der Kommentar in `EntityState.java` (Zeile 43-46) behauptet noch die alte Semantik. Ersetzen durch:

```java
    /**
     * Bestätigungspflicht beim AUSschalten (reiner UI-Schutz in Dashboard und Geräteliste;
     * Einschalten läuft immer direkt, Flows/Telegram/API schalten ungefragt).
     * Benutzergepflegt wie {@link #customName}; wird vom Polling-Upsert nie überschrieben.
     */
```

- [ ] **Step 3: CLAUDE.md nachziehen**

Zeile 173-174 ersetzen durch:

```markdown
- **Switch Confirmation**: `confirm_required` flag on `entity_states`
  - UI-only guard against accidental **switch-off**: dashboard and device list show a confirmation dialog before turning a guarded device OFF; turning it ON is always direct, and flows/Telegram/the API keep switching directly either way
  - `GET /api/devices` enriches each device with `confirmRequired` from the mirrored switch entity (same entityId construction as `SmartDeviceEntityMapper`), so the device page needs no second request
```

- [ ] **Step 4: Entities-Specs laufen lassen**

Run: `npx ng test --watch=false --browsers=ChromeHeadless --include='**/entities.component.spec.ts'`
Expected: grün. Sollte ein Spec auf dem alten Text `Bestätigung erforderlich` prüfen, den Text dort ebenfalls auf `Bestätigung beim Ausschalten` anpassen.

- [ ] **Step 5: Gesamte Frontend-Suite laufen lassen**

Run: `npx ng test --watch=false --browsers=ChromeHeadless`
Expected: nur die 3 bekannten vorbestehenden Fails (App/Hero). Jede weitere Abweichung ist eine Regression und muss behoben werden.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/entities/entities.component.html backend/src/main/java/com/household/manager/model/entity/EntityState.java CLAUDE.md
git commit -m "docs: Nur-Aus-Semantik der Schalter-Bestaetigung dokumentieren

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Abschluss

Nach Task 4 ist das Feature vollständig. Der Nutzer setzt das Flag weiterhin auf der Entitäten-Seite; Geräteseite und Dashboard fragen daraufhin vor dem Ausschalten nach. Wirksam wird es in PROD erst nach `docker-compose up --build` (Backend **und** Frontend).
