import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { AdminHouseDevicesComponent } from '../admin-house-devices.component';
import { HouseDevicePositionService } from '../../../services/house-device-position.service';
import { HouseOpeningService } from '../../../services/house-opening.service';
import { HouseDevicePosition } from '../../../models/house-device-position.model';
import { HouseOpening, HouseOpeningRequest } from '../../../models/house-opening.model';
import { linkableDevices } from '../../../shared/house/house-openings.util';

class FakePositionService {
  list(): Observable<HouseDevicePosition[]> {
    return of([]);
  }
}

class FakeOpeningService {
  openings: HouseOpening[] = [];
  failList = false;
  writeError: unknown = null;
  private nextId = 100;
  readonly createCalls: HouseOpeningRequest[] = [];
  readonly updateCalls: { id: number; request: HouseOpeningRequest }[] = [];
  readonly removeCalls: number[] = [];

  list(): Observable<HouseOpening[]> {
    return this.failList ? throwError(() => new Error('weg')) : of(this.openings);
  }

  create(request: HouseOpeningRequest): Observable<HouseOpening> {
    this.createCalls.push(request);
    return this.writeError ? throwError(() => this.writeError) : of({ ...request, id: this.nextId++ });
  }

  update(id: number, request: HouseOpeningRequest): Observable<HouseOpening> {
    this.updateCalls.push({ id, request });
    return this.writeError ? throwError(() => this.writeError) : of({ ...request, id });
  }

  remove(id: number): Observable<void> {
    this.removeCalls.push(id);
    return this.writeError ? throwError(() => this.writeError) : of(undefined);
  }
}

function opening(overrides: Partial<HouseOpening> = {}): HouseOpening {
  return {
    id: 1, floor: 'ground', kind: 'WINDOW', size: 'NORMAL', x1: 20, y1: 130, x2: 20, y2: 170,
    hinge: 'START', swing: 'POSITIVE', deviceId: null, ...overrides
  };
}

function requestOf(o: HouseOpening): HouseOpeningRequest {
  const { id: _id, ...request } = o;
  return request;
}

const TERRASSENTUER = linkableDevices().find(d => d.label === 'Terrassentür')!.id;

describe('HouseOpeningsEditorComponent', () => {
  let fixture: ComponentFixture<AdminHouseDevicesComponent>;
  let service: FakeOpeningService;

  function render(): void {
    fixture = TestBed.createComponent(AdminHouseDevicesComponent);
    fixture.detectChanges();
    const svgElement = fixture.nativeElement.querySelector('svg') as SVGSVGElement | null;
    if (svgElement) {
      spyOn(svgElement, 'getScreenCTM').and.returnValue(new DOMMatrix() as unknown as SVGMatrix);
    }
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function svg(): Element {
    return fixture.nativeElement.querySelector('svg');
  }

  function pointer(target: Element, type: string, x: number, y: number): void {
    target.dispatchEvent(new PointerEvent(type, { bubbles: true, clientX: x, clientY: y, pointerId: 1 }));
    fixture.detectChanges();
  }

  function button(label: string, scope: ParentNode = fixture.nativeElement): HTMLButtonElement {
    const found = Array.from(scope.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(b => b.textContent?.trim() === label);
    if (!found) {
      throw new Error(`Knopf „${label}“ fehlt`);
    }
    return found;
  }

  function click(label: string, scope?: ParentNode): void {
    button(label, scope).click();
    fixture.detectChanges();
  }

  function hit(id: number): Element | null {
    return fixture.nativeElement.querySelector(`.house-openings__hit[data-opening-id="${id}"]`);
  }

  function line(id: number): Element | null {
    return fixture.nativeElement.querySelector(`.house-openings__line[data-opening-id="${id}"]`);
  }

  function tapOpening(id: number, x: number, y: number): void {
    pointer(hit(id)!, 'pointerdown', x, y);
    pointer(hit(id)!, 'pointerup', x, y);
  }

  function panel(): HTMLElement {
    return fixture.nativeElement.querySelector('.house-openings__panel');
  }

  function sensorSelect(): HTMLSelectElement {
    return panel().querySelector('select') as HTMLSelectElement;
  }

  beforeEach(() => {
    service = new FakeOpeningService();
    TestBed.configureTestingModule({
      imports: [AdminHouseDevicesComponent],
      providers: [
        { provide: HouseDevicePositionService, useValue: new FakePositionService() },
        { provide: HouseOpeningService, useValue: service }
      ]
    });
  });

  it('fensterHinzufuegenUndAnWandTippenLegtAn', () => {
    render();
    click('Fenster hinzufügen');
    expect(text()).toContain('Auf eine Wand tippen');
    const catcher = fixture.nativeElement.querySelector('.house-openings__catcher') as Element;
    pointer(catcher, 'pointerdown', 25, 150);
    pointer(catcher, 'pointerup', 25, 150);
    expect(service.createCalls).toEqual([{
      floor: 'ground', kind: 'WINDOW', size: 'NORMAL', x1: 20, y1: 130, x2: 20, y2: 170,
      // Linke Aussenwand, Blick nach -x: „links“ ist das groessere y.
      hinge: 'END', swing: 'POSITIVE', deviceId: null
    }]);
    expect(line(100)).not.toBeNull();
    expect(line(100)!.getAttribute('stroke')).toBe('#5b8cff');
    expect(text()).not.toContain('Auf eine Wand tippen');
  });

  it('tuerHinzufuegenZeichnetTuerMitBogen', () => {
    render();
    click('Tür hinzufügen');
    const catcher = fixture.nativeElement.querySelector('.house-openings__catcher') as Element;
    pointer(catcher, 'pointerdown', 208, 150);
    pointer(catcher, 'pointerup', 208, 150);
    expect(service.createCalls[0]).toEqual(jasmine.objectContaining({
      kind: 'DOOR', size: 'NORMAL', x1: 210, y1: 132, x2: 210, y2: 168, swing: 'NEGATIVE', hinge: 'START'
    }));
    // Trennwand, Oeffnung nach -x (Esszimmer), Blick nach +x: „links“ ist das kleinere y = START.
    expect(line(100)!.getAttribute('stroke')).toBe('#c9d6f0');
    expect(fixture.nativeElement.querySelector('.house-openings__arc[data-opening-id="100"]')).not.toBeNull();
  });

  it('tippenOhneWandLegtNichtsAn', () => {
    render();
    click('Fenster hinzufügen');
    const catcher = fixture.nativeElement.querySelector('.house-openings__catcher') as Element;
    pointer(catcher, 'pointerdown', 100, 150);
    pointer(catcher, 'pointerup', 100, 150);
    expect(service.createCalls).toEqual([]);
    expect(fixture.nativeElement.querySelector('.admin-house-devices__messages').textContent)
      .toContain('Hier ist keine Wand, die lang genug ist.');
  });

  it('ueberlappungWirdAbgelehnt', () => {
    service.openings = [opening()];
    render();
    click('Fenster hinzufügen');
    const catcher = fixture.nativeElement.querySelector('.house-openings__catcher') as Element;
    pointer(catcher, 'pointerdown', 22, 160);
    pointer(catcher, 'pointerup', 22, 160);
    expect(service.createCalls).toEqual([]);
    expect(text()).toContain('Dort ist schon ein Fenster oder eine Tür.');
  });

  it('ziehenEntlangDerWandSpeichert', () => {
    service.openings = [opening()];
    render();
    pointer(hit(1)!, 'pointerdown', 20, 150);
    pointer(svg(), 'pointermove', 21, 200);
    expect(line(1)!.getAttribute('y1')).toBe('180');
    pointer(svg(), 'pointerup', 21, 200);
    expect(service.updateCalls).toEqual([{ id: 1, request: requestOf(opening({ y1: 180, y2: 220 })) }]);
    expect(line(1)!.getAttribute('y1')).toBe('180');
  });

  it('ziehenAufAndereWandSetztSwingNeu', () => {
    service.openings = [opening()];
    render();
    pointer(hit(1)!, 'pointerdown', 20, 150);
    pointer(svg(), 'pointermove', 208, 150);
    pointer(svg(), 'pointerup', 208, 150);
    expect(service.updateCalls).toEqual([{
      id: 1, request: requestOf(opening({ x1: 210, x2: 210, swing: 'NEGATIVE', hinge: 'END' }))
    }]);
  });

  it('ziehenRundetAufEineNachkommastelleUndBehaeltLaenge', () => {
    service.openings = [opening()];
    render();
    pointer(hit(1)!, 'pointerdown', 20, 150);
    pointer(svg(), 'pointermove', 21, 200.27);
    pointer(svg(), 'pointerup', 21, 200.27);
    expect(service.updateCalls[0].request).toEqual(jasmine.objectContaining({ y1: 180.3, y2: 220.3 }));
  });

  it('ziehenInsLeereSpringtZurueck', () => {
    service.openings = [opening()];
    render();
    pointer(hit(1)!, 'pointerdown', 20, 150);
    pointer(svg(), 'pointermove', 100, 150);
    pointer(svg(), 'pointerup', 100, 150);
    expect(service.updateCalls).toEqual([]);
    expect(text()).toContain('Hier ist keine Wand, die lang genug ist.');
    expect(line(1)!.getAttribute('y1')).toBe('130');
  });

  it('ziehenAufAnderesElementSpringtZurueck', () => {
    service.openings = [opening(), opening({ id: 2, y1: 200, y2: 240 })];
    render();
    pointer(hit(1)!, 'pointerdown', 20, 150);
    pointer(svg(), 'pointermove', 21, 215);
    pointer(svg(), 'pointerup', 21, 215);
    expect(service.updateCalls).toEqual([]);
    expect(text()).toContain('Dort ist schon ein Fenster oder eine Tür.');
  });

  it('groesseWechselnBehaeltMitte', () => {
    service.openings = [opening()];
    render();
    tapOpening(1, 20, 150);
    click('breit', panel());
    expect(service.updateCalls).toEqual([{
      id: 1, request: requestOf(opening({ size: 'WIDE', y1: 118, y2: 182 }))
    }]);
  });

  it('groesseDiePasstNichtWirdAbgelehnt', () => {
    service.openings = [opening({ y1: 90, y2: 130 })];
    render();
    tapOpening(1, 20, 110);
    click('breit', panel());
    expect(service.updateCalls).toEqual([]);
    expect(text()).toContain('Diese Größe passt hier nicht hin.');
  });

  it('anschlagRechtsSetztHinge', () => {
    service.openings = [opening({ kind: 'DOOR', y1: 130, y2: 166 })];
    render();
    tapOpening(1, 20, 150);
    expect(button('links', panel()).getAttribute('aria-pressed')).toBe('false');
    click('links', panel());
    expect(service.updateCalls[0].request.hinge).toBe('END');
  });

  it('terrassentuerHatKeinenAnschlag', () => {
    service.openings = [opening({ kind: 'DOOR', size: 'TERRACE', y1: 130, y2: 202 })];
    render();
    tapOpening(1, 20, 150);
    expect(panel().textContent).not.toContain('Anschlag');
  });

  it('trennwandZeigtOeffnetIn', () => {
    service.openings = [opening({ x1: 210, x2: 210, swing: 'NEGATIVE' })];
    render();
    tapOpening(1, 210, 150);
    expect(panel().textContent).toContain('Öffnet in:');
    click('Wohnzimmer', panel());
    expect(service.updateCalls).toEqual([{
      id: 1, request: requestOf(opening({ x1: 210, x2: 210, swing: 'POSITIVE' }))
    }]);
  });

  it('aussenwandZeigtKeinOeffnetIn', () => {
    service.openings = [opening()];
    render();
    tapOpening(1, 20, 150);
    expect(panel().textContent).not.toContain('Öffnet in:');
  });

  it('sensorListeOhneBelegte', () => {
    service.openings = [opening(), opening({ id: 2, y1: 300, y2: 340, deviceId: TERRASSENTUER })];
    render();
    tapOpening(1, 20, 150);
    const options = Array.from(sensorSelect().options).map(o => o.textContent?.trim() ?? '');
    expect(options[0]).toBe('kein Sensor');
    expect(options.some(o => o.startsWith('Terrassentür'))).toBeFalse();
    expect(options.some(o => o.startsWith('Haustür'))).toBeTrue();

    const haustuer = Array.from(sensorSelect().options).find(o => o.textContent?.startsWith('Haustür'))!;
    sensorSelect().value = haustuer.value;
    sensorSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(service.updateCalls).toEqual([{ id: 1, request: requestOf(opening({ deviceId: 'front-door' })) }]);
    expect(fixture.nativeElement.querySelector('.house-openings__linked[data-opening-id="1"]')).not.toBeNull();
  });

  it('unbekannterSensorZeigtHinweis', () => {
    service.openings = [opening({ deviceId: 'gibt-es-nicht' })];
    render();
    tapOpening(1, 20, 150);
    expect(panel().textContent).toContain('Sensor nicht gefunden');
    sensorSelect().value = '';
    sensorSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(service.updateCalls).toEqual([{ id: 1, request: requestOf(opening({ deviceId: null })) }]);
  });

  it('sensorAuswahlSpringtNachSpeicherfehlerZurueck', () => {
    service.openings = [opening()];
    render();
    tapOpening(1, 20, 150);
    service.writeError = new Error('weg');
    const haustuer = Array.from(sensorSelect().options).find(o => o.textContent?.startsWith('Haustür'))!;
    sensorSelect().value = haustuer.value;
    sensorSelect().dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(text()).toContain('Speichern fehlgeschlagen.');
    expect(sensorSelect().value).toBe('');
  });

  it('doppelterTippBeimSetzenLegtNurEinElementAn', () => {
    render();
    click('Fenster hinzufügen');
    const catcher = fixture.nativeElement.querySelector('.house-openings__catcher') as Element;
    pointer(catcher, 'pointerdown', 25, 150);
    pointer(catcher, 'pointerup', 25, 150);
    // Der Setz-Modus ist beendet, der Fänger weg: ein zweiter Tipp erreicht nichts mehr.
    expect(fixture.nativeElement.querySelector('.house-openings__catcher')).toBeNull();
    pointer(catcher, 'pointerdown', 25, 150);
    pointer(catcher, 'pointerup', 25, 150);
    expect(service.createCalls.length).toBe(1);
  });

  it('fangerIstImSetzModusDasLetzteKindDesPlans', () => {
    render();
    expect(fixture.nativeElement.querySelector('.house-openings__catcher')).toBeNull();
    click('Fenster hinzufügen');
    const catcher = fixture.nativeElement.querySelector('.house-openings__catcher') as Element;
    expect(svg().lastElementChild).toBe(catcher);
    expect(svg().classList).toContain('admin-house-devices__plan--placing');
  });

  it('etagenwechselBeendetDenSetzModus', () => {
    render();
    click('Fenster hinzufügen');
    expect(fixture.nativeElement.querySelector('.house-openings__catcher')).not.toBeNull();
    click('Obergeschoss');
    click('Erdgeschoss');
    expect(fixture.nativeElement.querySelector('.house-openings__catcher')).toBeNull();
    expect(button('Fenster hinzufügen').getAttribute('aria-pressed')).toBe('false');
  });

  it('umzugAnAndereWandBehaeltDieAnschlagSeite', () => {
    service.openings = [opening({ hinge: 'START' })];
    render();
    tapOpening(1, 20, 150);
    const before = button('links', panel()).getAttribute('aria-pressed');
    pointer(hit(1)!, 'pointerdown', 20, 150);
    pointer(svg(), 'pointermove', 208, 150);
    pointer(svg(), 'pointerup', 208, 150);
    expect(button('links', panel()).getAttribute('aria-pressed')).toBe(before);
  });

  it('loeschenMitRueckfrage', () => {
    service.openings = [opening()];
    render();
    tapOpening(1, 20, 150);
    click('Löschen', panel());
    expect(service.removeCalls).toEqual([]);
    expect(text()).toContain('Dieses Element löschen?');
    click('Ja, löschen');
    expect(service.removeCalls).toEqual([1]);
    expect(line(1)).toBeNull();
    expect(panel()).toBeNull();
  });

  it('elementAufKeinerWandStehtInListe', () => {
    service.openings = [opening({ x1: 100, y1: 150, x2: 140, y2: 150 })];
    render();
    expect(line(1)).toBeNull();
    const orphans = fixture.nativeElement.querySelector('.house-openings__orphans') as HTMLElement;
    expect(orphans.textContent).toContain('Liegt auf keiner Wand');
    expect(orphans.textContent).toContain('Fenster');
    click('Löschen', orphans);
    click('Ja, löschen');
    expect(service.removeCalls).toEqual([1]);
    expect(fixture.nativeElement.querySelector('.house-openings__orphans')).toBeNull();
  });

  it('speicherfehlerUnd409ZeigenMeldungUndBehaltenStand', () => {
    service.openings = [opening()];
    service.writeError = new HttpErrorResponse({ status: 409 });
    render();
    pointer(hit(1)!, 'pointerdown', 20, 150);
    pointer(svg(), 'pointermove', 21, 200);
    pointer(svg(), 'pointerup', 21, 200);
    expect(service.updateCalls.length).toBe(1);
    expect(fixture.nativeElement.querySelector('.admin-house-devices__messages').textContent)
      .toContain('Speichern fehlgeschlagen.');
    expect(line(1)!.getAttribute('y1')).toBe('130');

    service.writeError = new Error('weg');
    tapOpening(1, 20, 150);
    click('breit', panel());
    expect(text()).toContain('Speichern fehlgeschlagen.');
    expect(line(1)!.getAttribute('y1')).toBe('130');
    expect(button('normal', panel()).getAttribute('aria-pressed')).toBe('true');
  });

  it('ladefehlerZeigtMeldung', () => {
    service.failList = true;
    render();
    expect(text()).toContain('Fenster und Türen konnten nicht geladen werden.');
    expect(svg()).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.admin-house-devices__item')).not.toBeNull();

    service.failList = false;
    service.openings = [opening()];
    click('Neu laden');
    expect(text()).not.toContain('Fenster und Türen konnten nicht geladen werden.');
    expect(line(1)).not.toBeNull();
  });

  it('elementeAndererEtagenErscheinenNicht', () => {
    service.openings = [opening({ floor: 'upper' })];
    render();
    expect(line(1)).toBeNull();
  });
});
