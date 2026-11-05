import { ComponentFixture, TestBed, fakeAsync, flush, tick } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { HouseDeviceDetailComponent, TARGET_GRACE_MS } from './house-device-detail.component';
import {
  BrightnessOutcome, HistoryRange, HistorySeries, HouseDeviceControlService, SwitchOutcome
} from '../house-device-control.service';
import { DeviceDetail, HistorySource } from '../../../shared/house/device-detail.util';

class FakeControl {
  historyCalls: { source: HistorySource; range: HistoryRange }[] = [];
  historyResponse: (range: HistoryRange) => Observable<HistorySeries[] | null> =
    () => of([{ name: 'Ist', points: [{ at: '2026-11-05T10:00:00', value: 21 }] }]);
  switchCalls: { entityId: string; on: boolean }[] = [];
  switchResponse: Observable<SwitchOutcome> = of('switched');
  targetCalls: { id: number; target: number }[] = [];
  brightnessCalls: { entityId: string; brightness: number }[] = [];
  targetResponse: Observable<void> = of(undefined);
  brightnessResponse: Observable<BrightnessOutcome> = of('set');

  loadHistory(source: HistorySource, range: HistoryRange) {
    this.historyCalls.push({ source, range });
    return this.historyResponse(range);
  }
  setSwitch(entityId: string, on: boolean) {
    this.switchCalls.push({ entityId, on });
    return this.switchResponse;
  }
  setTarget(id: number, target: number) {
    this.targetCalls.push({ id, target });
    return this.targetResponse;
  }
  setBrightness(entityId: string, brightness: number) {
    this.brightnessCalls.push({ entityId, brightness });
    return this.brightnessResponse;
  }
}

const climate: DeviceDetail = {
  deviceId: 'wohnzimmer-klima', title: 'Wohnzimmer', icon: 'thermostat',
  headline: { text: '21,5°', stale: false },
  lines: [{ text: '48 %', stale: true }],
  history: { type: 'temperature', entityId: 'sensor.t' },
  control: null
};

function thermostat(target: number | null, thermostatId = 8): DeviceDetail {
  return {
    deviceId: `heizung-${thermostatId}`, title: 'Bad', icon: 'heat', headline: { text: '19,0°', stale: false },
    lines: [], history: { type: 'thermostat', thermostatId },
    control: { type: 'target', thermostatId, target, min: 5, max: 30, step: 0.5 }
  };
}

function switchDetail(on: boolean | null, confirmOff = false, dimmable = false): DeviceDetail {
  return {
    deviceId: 'flur', title: 'Flur', icon: 'lightbulb', headline: { text: on ? 'an' : 'aus', stale: false },
    lines: [], history: null,
    control: { type: 'switch', entityId: 'switch.flur', on, confirmOff, dimmable }
  };
}

describe('HouseDeviceDetailComponent', () => {
  let fixture: ComponentFixture<HouseDeviceDetailComponent>;
  let control: FakeControl;
  let el: HTMLElement;

  function create(detail: DeviceDetail, brightness: number | null = null) {
    fixture = TestBed.createComponent(HouseDeviceDetailComponent);
    fixture.componentRef.setInput('detail', detail);
    fixture.componentRef.setInput('brightness', brightness);
    fixture.detectChanges();
    el = fixture.nativeElement;
  }

  function setDetail(detail: DeviceDetail) {
    fixture.componentRef.setInput('detail', detail);
    fixture.detectChanges();
  }

  function q(selector: string): HTMLElement | null {
    return el.querySelector(selector);
  }

  function click(selector: string) {
    (q(selector) as HTMLElement).click();
    fixture.detectChanges();
  }

  beforeEach(() => {
    control = new FakeControl();
    TestBed.configureTestingModule({
      imports: [HouseDeviceDetailComponent],
      providers: [{ provide: HouseDeviceControlService, useValue: control }]
    });
  });

  it('zeigt Titel, headline groß, lines; veraltete Werte abgedunkelt', () => {
    create(climate);
    expect(q('.device-detail__title')?.textContent).toContain('Wohnzimmer');
    const headline = q('.device-detail__headline')!;
    expect(headline.textContent).toContain('21,5°');
    expect(headline.classList).not.toContain('device-detail__value--stale');
    const line = q('.device-detail__line')!;
    expect(line.textContent).toContain('48 %');
    expect(line.classList).toContain('device-detail__value--stale');
  });

  it('Verlauf: lädt beim Öffnen DAY; Knopf 7 Tage lädt WEEK', () => {
    create(climate);
    expect(control.historyCalls.map(c => c.range)).toEqual(['DAY']);
    expect(q('.device-detail__chart')).not.toBeNull();
    expect(q('[data-range="WEEK"]')?.textContent).toContain('7 Tage');
    click('[data-range="WEEK"]');
    expect(control.historyCalls.map(c => c.range)).toEqual(['DAY', 'WEEK']);
  });

  it('Verlauf null ⇒ Text Kein Verlauf verfügbar', () => {
    control.historyResponse = () => of(null);
    create(climate);
    expect(el.textContent).toContain('Kein Verlauf verfügbar');
    expect(q('.device-detail__chart')).toBeNull();
  });

  it('ohne Verlaufsquelle kein Abruf', () => {
    create(switchDetail(true));
    expect(control.historyCalls.length).toBe(0);
    expect(q('.device-detail__chart')).toBeNull();
  });

  it('Refresh mit gleicher Quelle lädt den Verlauf nicht erneut', () => {
    create(climate);
    setDetail({ ...climate, headline: { text: '21,6°', stale: false } });
    expect(control.historyCalls.length).toBe(1);
  });

  it('Zeitraumwechsel bestellt den laufenden Abruf ab', () => {
    const day = new Subject<HistorySeries[] | null>();
    const week = new Subject<HistorySeries[] | null>();
    control.historyResponse = range => range === 'DAY' ? day : week;
    create(climate);
    click('[data-range="WEEK"]');
    week.next([{ name: 'Woche', points: [{ at: '2026-11-01T00:00:00', value: 18 }] }]);
    fixture.detectChanges();
    const weekOptions = JSON.stringify(fixture.componentInstance.chartOptions);
    day.next(null);
    fixture.detectChanges();
    expect(JSON.stringify(fixture.componentInstance.chartOptions)).toBe(weekOptions);
    expect(el.textContent).not.toContain('Kein Verlauf verfügbar');
    expect(weekOptions).toContain('Woche');
  });

  it('Diagramm bricht Linien bei Lücken (connectNulls false)', () => {
    create(climate);
    const series = fixture.componentInstance.chartOptions!['series'] as { connectNulls: boolean }[];
    expect(series.length).toBe(1);
    expect(series.every(s => s.connectNulls === false)).toBeTrue();
  });

  it('Sollwert: + fünfmal in 1 s ⇒ genau ein setTarget mit target+2,5 nach 600 ms', fakeAsync(() => {
    create(thermostat(20));
    for (let i = 0; i < 5; i++) {
      click('.device-detail__step--up');
      tick(200);
    }
    expect(control.targetCalls.length).toBe(0);
    tick(600);
    expect(control.targetCalls).toEqual([{ id: 8, target: 22.5 }]);
    flush(1000);
  }));

  it('Sollwert: Anzeige folgt sofort dem lokalen Wert', fakeAsync(() => {
    create(thermostat(20));
    click('.device-detail__step--down');
    expect(q('.device-detail__target')?.textContent).toContain('19,5');
    flush(1000);
  }));

  it('Sollwert: Grenze 30 wird nicht überschritten', fakeAsync(() => {
    create(thermostat(29.5));
    click('.device-detail__step--up');
    click('.device-detail__step--up');
    tick(600);
    expect(control.targetCalls).toEqual([{ id: 8, target: 30 }]);
    flush(1000);
  }));

  it('Sollwert unbekannt ⇒ −/+ gesperrt', () => {
    create(thermostat(null));
    expect((q('.device-detail__step--up') as HTMLButtonElement).disabled).toBeTrue();
    expect((q('.device-detail__step--down') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('neues detail-Input während einer offenen −/+-Eingabe setzt den lokalen Wert nicht zurück', fakeAsync(() => {
    create(thermostat(20));
    click('.device-detail__step--up');
    setDetail(thermostat(20));
    expect(q('.device-detail__target')?.textContent).toContain('20,5');
    tick(600);
    setDetail(thermostat(20));
    expect(q('.device-detail__target')?.textContent).toContain('20,5');
    setDetail(thermostat(20.5));
    setDetail(thermostat(21));
    expect(q('.device-detail__target')?.textContent).toContain('21,0');
    flush(1000);
  }));

  it('Gerätewechsel in der Sammelpause sendet den Sollwert an das ALTE Thermostat', fakeAsync(() => {
    create(thermostat(20, 1));
    click('.device-detail__step--up');
    tick(200);
    setDetail(thermostat(18, 8));
    flush(1000);
    expect(control.targetCalls).toEqual([{ id: 1, target: 20.5 }]);
    expect(q('.device-detail__target')?.textContent).toContain('18,0');
  }));

  it('Wechsel auf ein Nicht-Thermostat sendet den offenen Sollwert trotzdem an das alte', fakeAsync(() => {
    create(thermostat(20, 1));
    click('.device-detail__step--up');
    setDetail(climate);
    flush(1000);
    expect(control.targetCalls).toEqual([{ id: 1, target: 20.5 }]);
  }));

  it('Schließen innerhalb der Sammelpause sendet den letzten Sollwert', fakeAsync(() => {
    create(thermostat(20, 1));
    click('.device-detail__step--up');
    fixture.destroy();
    flush(1000);
    expect(control.targetCalls).toEqual([{ id: 1, target: 20.5 }]);
  }));

  it('Gerät meldet anderen Wert ⇒ nach der Karenz zeigt die Anzeige den Serverwert', fakeAsync(() => {
    create(thermostat(20));
    click('.device-detail__step--up');
    tick(600);
    setDetail(thermostat(20));
    expect(q('.device-detail__target')?.textContent).toContain('20,5');
    tick(TARGET_GRACE_MS);
    fixture.detectChanges();
    expect(q('.device-detail__target')?.textContent).toContain('20,0');
  }));

  it('Gerät meldet nach dem Schreiben einen dritten Wert ⇒ sofort Serverwert', fakeAsync(() => {
    create(thermostat(20));
    click('.device-detail__step--up');
    tick(600);
    setDetail(thermostat(19));
    expect(q('.device-detail__target')?.textContent).toContain('19,0');
    flush(1000);
  }));

  it('setTarget scheitert ⇒ lokaler Wert verworfen, Fehler angezeigt', fakeAsync(() => {
    control.targetResponse = throwError(() => new Error('502'));
    create(thermostat(20));
    click('.device-detail__step--up');
    tick(600);
    fixture.detectChanges();
    expect(q('.device-detail__target')?.textContent).toContain('20,0');
    expect(q('.device-detail__error')?.textContent).toContain('Gerät nicht erreichbar');
    flush(1000);
  }));

  it('Schalter aus bei confirmOff ⇒ Bestätigungsdialog, erst Ausschalten ruft setSwitch(false)', () => {
    create(switchDetail(true, true));
    click('.device-detail__switch');
    expect(control.switchCalls.length).toBe(0);
    expect(q('.device-detail__confirm')).not.toBeNull();
    click('.device-detail__confirm-off');
    expect(control.switchCalls).toEqual([{ entityId: 'switch.flur', on: false }]);
    expect(q('.device-detail__confirm')).toBeNull();
  });

  it('Bestätigungsdialog: Abbrechen schaltet nicht', () => {
    create(switchDetail(true, true));
    click('.device-detail__switch');
    click('.device-detail__confirm-cancel');
    expect(control.switchCalls.length).toBe(0);
    expect(q('.device-detail__confirm')).toBeNull();
  });

  it('Einschalten ruft direkt setSwitch(true)', () => {
    create(switchDetail(false, true));
    click('.device-detail__switch');
    expect(control.switchCalls).toEqual([{ entityId: 'switch.flur', on: true }]);
  });

  it('confirmOff false ⇒ Aus direkt', () => {
    create(switchDetail(true, false));
    click('.device-detail__switch');
    expect(control.switchCalls).toEqual([{ entityId: 'switch.flur', on: false }]);
  });

  it('Helligkeit: Regler nur bei dimmable', () => {
    create(switchDetail(true, false, false));
    expect(q('.device-detail__brightness')).toBeNull();
    setDetail(switchDetail(true, false, true));
    expect(q('.device-detail__brightness')).not.toBeNull();
  });

  it('Helligkeit: gesperrt bei on:false', () => {
    create(switchDetail(false, false, true), 40);
    expect((q('.device-detail__brightness') as HTMLInputElement).disabled).toBeTrue();
  });

  it('Helligkeit: change ⇒ setBrightness, input nicht', () => {
    create(switchDetail(true, false, true), 40);
    const slider = q('.device-detail__brightness') as HTMLInputElement;
    expect(slider.value).toBe('40');
    slider.value = '70';
    slider.dispatchEvent(new Event('input'));
    expect(control.brightnessCalls.length).toBe(0);
    slider.dispatchEvent(new Event('change'));
    expect(control.brightnessCalls).toEqual([{ entityId: 'switch.flur', brightness: 70 }]);
  });

  it('Helligkeit scheitert ⇒ Regler springt auf den gebundenen Wert zurück, Meldung', () => {
    control.brightnessResponse = throwError(() => new Error('502'));
    create(switchDetail(true, false, true), 40);
    const slider = q('.device-detail__brightness') as HTMLInputElement;
    slider.value = '70';
    slider.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(slider.value).toBe('40');
    expect(q('.device-detail__error')?.textContent).toContain('Gerät nicht erreichbar');
  });

  it('Helligkeit bei frisch ausgeschalteter Lampe ⇒ Meldung Lampe ist aus, Regler zurück', () => {
    control.brightnessResponse = of('lamp-off');
    create(switchDetail(true, false, true), 40);
    const slider = q('.device-detail__brightness') as HTMLInputElement;
    slider.value = '70';
    slider.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(slider.value).toBe('40');
    expect(q('.device-detail__error')?.textContent).toContain('Lampe ist aus');
  });

  it('Fehler beim Schalten ⇒ Meldung Gerät nicht erreichbar', () => {
    control.switchResponse = throwError(() => new Error('502'));
    create(switchDetail(false));
    click('.device-detail__switch');
    expect(q('.device-detail__error')?.textContent).toContain('Gerät nicht erreichbar');
  });

  it('unavailable ⇒ Meldung, already ⇒ keine', () => {
    control.switchResponse = of('unavailable');
    create(switchDetail(false));
    click('.device-detail__switch');
    expect(q('.device-detail__error')?.textContent).toContain('Gerät nicht erreichbar');
    control.switchResponse = of('already');
    click('.device-detail__switch');
    expect(q('.device-detail__error')).toBeNull();
  });

  it('on:null ⇒ Schalter disabled', () => {
    create(switchDetail(null));
    expect((q('.device-detail__switch') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('✕ emittiert closed', () => {
    create(climate);
    let closed = false;
    fixture.componentInstance.closed.subscribe(() => closed = true);
    click('.device-detail__close');
    expect(closed).toBeTrue();
  });
});
