import { HouseOpening } from '../../models/house-opening.model';
import { HOUSE_DEVICES } from '../../shared/house/house-devices';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { AdminHouseDevicesComponent } from './admin-house-devices.component';
import { HouseDevicePositionService } from '../../services/house-device-position.service';
import { HouseDevicePosition } from '../../models/house-device-position.model';
import { PlanPoint } from '../../shared/house/house-placement.util';
import { HouseOpeningService } from '../../services/house-opening.service';

const FRIDGE = 'kueche-meross-2112155672709990863548e1e98181eb';
const DINING_LAMP = 'esszimmer-kasa-8012e05a';

class FakePositionService {
  positions: HouseDevicePosition[] = [];
  failList = false;
  failWrite = false;
  readonly setCalls: { deviceId: string; point: PlanPoint }[] = [];
  readonly removeCalls: string[] = [];
  readonly rotationCalls: (number | null)[] = [];

  list(): Observable<HouseDevicePosition[]> {
    return this.failList ? throwError(() => new Error('weg')) : of(this.positions);
  }

  set(deviceId: string, point: PlanPoint, rotation: number | null = null): Observable<HouseDevicePosition> {
    this.setCalls.push({ deviceId, point });
    this.rotationCalls.push(rotation);
    return this.failWrite ? throwError(() => new Error('weg')) : of({ deviceId, x: point.x, y: point.y, rotation });
  }

  remove(deviceId: string): Observable<void> {
    this.removeCalls.push(deviceId);
    return this.failWrite ? throwError(() => new Error('weg')) : of(undefined);
  }
}

describe('AdminHouseDevicesComponent', () => {
  let fixture: ComponentFixture<AdminHouseDevicesComponent>;
  let service: FakePositionService;
  let openings: HouseOpening[];

  function render(): void {
    fixture = TestBed.createComponent(AdminHouseDevicesComponent);
    fixture.detectChanges();
    stubScreenMatrix();
  }

  /** Einheitsmatrix: Client-Koordinaten = Plan-Koordinaten. */
  function stubScreenMatrix(): void {
    const svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement | null;
    if (svg) {
      spyOn(svg, 'getScreenCTM').and.returnValue(new DOMMatrix() as unknown as SVGMatrix);
    }
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function dot(deviceId: string): Element | null {
    return fixture.nativeElement.querySelector(`.admin-house-devices__dot[data-device-id="${deviceId}"]`);
  }

  function listItem(deviceId: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(`.admin-house-devices__item[data-device-id="${deviceId}"]`);
  }

  function pointer(target: Element, type: string, x: number, y: number): void {
    target.dispatchEvent(new PointerEvent(type, { bubbles: true, clientX: x, clientY: y, pointerId: 1 }));
    fixture.detectChanges();
  }

  function svg(): Element {
    return fixture.nativeElement.querySelector('svg');
  }

  function tab(label: string): void {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('.admin-house-devices__tab')) as HTMLButtonElement[];
    buttons.find(button => button.textContent?.trim() === label)!.click();
    fixture.detectChanges();
  }

  beforeEach(() => {
    service = new FakePositionService();
    openings = [];
    TestBed.configureTestingModule({
      imports: [AdminHouseDevicesComponent],
      providers: [
        { provide: HouseDevicePositionService, useValue: service },
        { provide: HouseOpeningService, useValue: { list: () => of(openings) } }
      ]
    });
  });

  it('zeigtStandardmaessigErdgeschossMitAussenbereichen', () => {
    render();
    const names = Array.from(fixture.nativeElement.querySelectorAll('.admin-house-devices__name'))
      .map(node => (node as Element).textContent?.trim());
    expect(names).toContain('Esszimmer');
    expect(names).toContain('Garten');
    expect(names).toContain('Vor dem Haus');
    expect(names).not.toContain('Balkon');
    expect(text()).not.toContain('Balkon');
    const active = fixture.nativeElement.querySelector('.admin-house-devices__tab--active') as HTMLElement;
    expect(active.textContent?.trim()).toBe('Erdgeschoss');
  });

  it('obergeschossZeigtBalkon', () => {
    render();
    tab('Obergeschoss');
    const names = Array.from(fixture.nativeElement.querySelectorAll('.admin-house-devices__name'))
      .map(node => (node as Element).textContent?.trim());
    expect(names).toContain('Balkon');
    expect(names).toContain('Schlafzimmer');
    expect(names).not.toContain('Esszimmer');
  });

  it('platziertesGeraetErscheintAlsPunktUnplatziertesInDerListe', () => {
    service.positions = [{ deviceId: DINING_LAMP, x: 100, y: 150 }];
    render();
    expect(dot(DINING_LAMP)).not.toBeNull();
    expect(listItem(DINING_LAMP)).toBeNull();
    expect(dot(FRIDGE)).toBeNull();
    expect(listItem(FRIDGE)).not.toBeNull();
    expect(dot(DINING_LAMP)!.querySelector('circle.admin-house-devices__dot-mark')!.getAttribute('fill')).toBe('#ffd36b');
  });

  it('verknuepfterKontaktErscheintWederAlsPunktNochInDerListe', () => {
    const terrasse = HOUSE_DEVICES.find(d => d.kind === 'contact' && d.area === 'wohnzimmer')!;
    service.positions = [{ deviceId: terrasse.id, x: 300, y: 150 }];
    openings = [{
      id: 1, floor: 'ground', kind: 'DOOR', size: 'NORMAL', x1: 20, y1: 200, x2: 20, y2: 236,
      hinge: 'START', swing: 'POSITIVE', deviceId: terrasse.id
    }];
    render();
    expect(dot(terrasse.id)).toBeNull();
    expect(listItem(terrasse.id)).toBeNull();
  });

  it('ungueltigerPlatzStehtMitVermerkInDerListe', () => {
    service.positions = [{ deviceId: FRIDGE, x: 100, y: 150 }];
    render();
    expect(dot(FRIDGE)).toBeNull();
    expect(listItem(FRIDGE)!.textContent).toContain('Platz liegt außerhalb des Raums');
  });

  it('loslassenImEigenenBereichSpeichert', () => {
    render();
    const item = listItem(FRIDGE)!;
    pointer(item, 'pointerdown', 600, 600);
    pointer(svg(), 'pointermove', 100.26, 300.04);
    pointer(svg(), 'pointerup', 100.26, 300.04);
    expect(service.setCalls).toEqual([{ deviceId: FRIDGE, point: { x: 100.3, y: 300 } }]);
    expect(dot(FRIDGE)).not.toBeNull();
    expect(listItem(FRIDGE)).toBeNull();
  });

  it('loslassenAusserhalbSpeichertNichtUndZeigtHinweis', () => {
    render();
    const item = listItem(FRIDGE)!;
    pointer(item, 'pointerdown', 600, 600);
    pointer(svg(), 'pointermove', 100, 150);
    pointer(svg(), 'pointerup', 100, 150);
    expect(service.setCalls).toEqual([]);
    expect(text()).toContain('„Kühlschrank“ gehört in: Küche');
    expect(dot(FRIDGE)).toBeNull();
    expect(listItem(FRIDGE)).not.toBeNull();
  });

  it('tippenUndTippenSetztGeraet', () => {
    render();
    const item = listItem(FRIDGE)!;
    pointer(item, 'pointerdown', 600, 600);
    pointer(item, 'pointerup', 603, 602);
    expect(listItem(FRIDGE)!.classList).toContain('admin-house-devices__item--selected');
    expect(service.setCalls).toEqual([]);

    pointer(svg(), 'pointerdown', 120, 300);
    pointer(svg(), 'pointerup', 121, 300);
    expect(service.setCalls).toEqual([{ deviceId: FRIDGE, point: { x: 121, y: 300 } }]);
    expect(dot(FRIDGE)).not.toBeNull();
  });

  function button(label: string): HTMLButtonElement | undefined {
    return Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(entry => entry.textContent?.trim() === label);
  }

  function tapDot(deviceId: string, x: number, y: number): void {
    const mark = dot(deviceId)!;
    pointer(mark, 'pointerdown', x, y);
    pointer(mark, 'pointerup', x + 1, y);
  }

  it('grossgeraetZeigtGrundflaecheMitGespeicherterDrehung', () => {
    service.positions = [{ deviceId: FRIDGE, x: 100, y: 300, rotation: 90 }];
    render();
    const body = fixture.nativeElement.querySelector(`[data-body-id="${FRIDGE}"]`) as Element;
    expect(body.getAttribute('transform')).toContain('rotate(-90)');
    expect(fixture.nativeElement.querySelector(`[data-body-id="${DINING_LAMP}"]`)).toBeNull();
  });

  it('ungueltigerPlatzZeigtKeineGrundflaeche', () => {
    service.positions = [{ deviceId: FRIDGE, x: 400, y: 100, rotation: 90 }];
    render();
    expect(fixture.nativeElement.querySelector(`[data-body-id="${FRIDGE}"]`)).toBeNull();
  });

  it('drehenDrehtVonDerAutomatischenAusrichtungWeiter', () => {
    // Kueche y 240..350: Platz y 300 liegt naeher an der unteren Wand => Vorderseite nach oben (180 Grad)
    service.positions = [{ deviceId: FRIDGE, x: 100, y: 300 }];
    render();
    tapDot(FRIDGE, 100, 300);
    expect(button('Automatisch')).toBeUndefined();
    button('Drehen')!.click();
    fixture.detectChanges();
    expect(service.rotationCalls).toEqual([270]);
    expect(service.setCalls).toEqual([{ deviceId: FRIDGE, point: { x: 100, y: 300 } }]);
    expect(button('Automatisch')).toBeDefined();
  });

  it('automatischSetztDieDrehungZurueck', () => {
    service.positions = [{ deviceId: FRIDGE, x: 100, y: 300, rotation: 90 }];
    render();
    tapDot(FRIDGE, 100, 300);
    button('Automatisch')!.click();
    fixture.detectChanges();
    expect(service.rotationCalls).toEqual([null]);
    expect(button('Automatisch')).toBeUndefined();
  });

  it('verschiebenBehaeltDieDrehung', () => {
    service.positions = [{ deviceId: FRIDGE, x: 100, y: 300, rotation: 90 }];
    render();
    const mark = dot(FRIDGE)!;
    pointer(mark, 'pointerdown', 100, 300);
    pointer(mark, 'pointermove', 120, 300);
    pointer(mark, 'pointerup', 120, 300);
    expect(service.rotationCalls).toEqual([90]);
  });

  it('gewoehnlichesGeraetHatKeinDrehen', () => {
    service.positions = [{ deviceId: DINING_LAMP, x: 100, y: 150 }];
    render();
    tapDot(DINING_LAMP, 100, 150);
    expect(button('Platz zurücksetzen')).toBeDefined();
    expect(button('Drehen')).toBeUndefined();
  });

  it('zuruecksetzenRuftRemoveUndListetGeraet', () => {
    service.positions = [{ deviceId: DINING_LAMP, x: 100, y: 150 }];
    render();
    const mark = dot(DINING_LAMP)!;
    pointer(mark, 'pointerdown', 100, 150);
    pointer(mark, 'pointerup', 101, 150);
    const reset = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.trim() === 'Platz zurücksetzen')!;
    reset.click();
    fixture.detectChanges();
    expect(service.removeCalls).toEqual([DINING_LAMP]);
    expect(dot(DINING_LAMP)).toBeNull();
    expect(listItem(DINING_LAMP)).not.toBeNull();
  });

  it('speicherfehlerZeigtMeldungUndBehaeltStand', () => {
    service.positions = [{ deviceId: DINING_LAMP, x: 100, y: 150 }];
    service.failWrite = true;
    render();
    const mark = dot(DINING_LAMP)!;
    pointer(mark, 'pointerdown', 100, 150);
    pointer(svg(), 'pointermove', 150, 200);
    pointer(svg(), 'pointerup', 150, 200);
    expect(service.setCalls.length).toBe(1);
    expect(text()).toContain('Speichern fehlgeschlagen.');
    expect(dot(DINING_LAMP)!.getAttribute('transform')).toBe('translate(100 150)');
  });

  it('ladefehlerZeigtMeldungStattLeeremPlan', () => {
    service.failList = true;
    render();
    expect(text()).toContain('Geräteplätze konnten nicht geladen werden.');
    expect(fixture.nativeElement.querySelector('svg')).toBeNull();
    expect(fixture.nativeElement.querySelector('.admin-house-devices__item')).toBeNull();

    service.failList = false;
    const reload = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(button => button.textContent?.trim() === 'Neu laden')!;
    reload.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('svg')).not.toBeNull();
  });

  it('doppelteLabelsTragenBereichsnamen', () => {
    render();
    expect(listItem('kueche-climate')!.textContent).toContain('Raumklima · Küche');
    expect(listItem('laufband-climate')!.textContent).toContain('Raumklima · Laufband-Raum');
    expect(listItem('laufband-tapo-802238b6')!.textContent?.trim()).toBe('Laufband');
  });
});
