import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { CameraControlComponent } from './camera-control.component';
import { ActionOutcome, HouseDeviceControlService } from '../house-device-control.service';
import { BlinkCamera } from '../../../models/blink.model';

class FakeControl {
  armedCalls: { id: string; armed: boolean }[] = [];
  snapshotCalls: string[] = [];
  armedResponse: Observable<ActionOutcome> = of('done');
  snapshotResponse: Observable<void> = of(undefined);
  setCameraArmed(id: string, armed: boolean) {
    this.armedCalls.push({ id, armed });
    return this.armedResponse;
  }
  takeSnapshot(id: string) {
    this.snapshotCalls.push(id);
    return this.snapshotResponse;
  }
  thumbnailUrl(id: string, key: number) {
    return `/thumb/${id}?t=${key}`;
  }
}

function cam(armed: boolean, id = '511671'): BlinkCamera {
  return { cameraId: id, name: 'Tuer', type: 'doorbell', armed, battery: null, syncName: 's', syncArmed: true };
}

describe('CameraControlComponent', () => {
  let fixture: ComponentFixture<CameraControlComponent>;
  let service: FakeControl;
  let changed: number;

  function create(camera: BlinkCamera | null | undefined, id = '511671'): HTMLElement {
    fixture = TestBed.createComponent(CameraControlComponent);
    fixture.componentRef.setInput('control', { type: 'camera', cameraId: id, camera });
    fixture.componentInstance.changed.subscribe(() => changed++);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function setControl(camera: BlinkCamera | null | undefined, id = '511671'): void {
    fixture.componentRef.setInput('control', { type: 'camera', cameraId: id, camera });
    fixture.detectChanges();
  }

  const sw = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('[role=switch]')!;
  const img = (el: HTMLElement) => el.querySelector<HTMLImageElement>('img');
  const byText = (el: HTMLElement, text: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find(b => b.textContent!.trim() === text)!;

  beforeEach(() => {
    service = new FakeControl();
    changed = 0;
    TestBed.configureTestingModule({
      imports: [CameraControlComponent],
      providers: [{ provide: HouseDeviceControlService, useValue: service }]
    });
  });

  it('camera undefined: Hinweis „Lade Kamera …“', () => {
    const el = create(undefined);
    expect(el.textContent).toContain('Lade Kamera');
    expect(img(el)).toBeNull();
  });

  it('camera null: nur „Kamera nicht erreichbar“', () => {
    const el = create(null);
    expect(el.textContent).toContain('Kamera nicht erreichbar');
    expect(img(el)).toBeNull();
    expect(el.querySelector('[role=switch]')).toBeNull();
    expect(el.querySelector('button')).toBeNull();
  });

  it('Ausschalten fragt: Abbrechen ruft nichts auf, Deaktivieren schaltet aus', () => {
    const el = create(cam(true));
    expect(sw(el).getAttribute('aria-checked')).toBe('true');
    sw(el).click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Bewegungserkennung der Kamera deaktivieren?');
    expect(service.armedCalls).toEqual([]);
    byText(el, 'Abbrechen').click();
    fixture.detectChanges();
    expect(service.armedCalls).toEqual([]);
    expect(el.textContent).not.toContain('deaktivieren?');
    sw(el).click();
    fixture.detectChanges();
    byText(el, 'Deaktivieren').click();
    expect(service.armedCalls).toEqual([{ id: '511671', armed: false }]);
    expect(changed).toBe(1);
  });

  it('Einschalten geht direkt ohne Dialog', () => {
    const el = create(cam(false));
    sw(el).click();
    fixture.detectChanges();
    expect(service.armedCalls).toEqual([{ id: '511671', armed: true }]);
    expect(el.textContent).not.toContain('deaktivieren?');
  });

  it('unavailable zeigt „Gerät nicht erreichbar“ ohne changed', () => {
    service.armedResponse = of('unavailable');
    const el = create(cam(false));
    sw(el).click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Gerät nicht erreichbar');
    expect(changed).toBe(0);
  });

  it('waehrend der Aktion ist der Schalter gesperrt', () => {
    service.armedResponse = new Observable<ActionOutcome>(() => undefined);
    const el = create(cam(false));
    sw(el).click();
    fixture.detectChanges();
    expect(sw(el).disabled).toBeTrue();
    sw(el).click();
    expect(service.armedCalls.length).toBe(1);
  });

  it('neues control derselben Kamera (Takt) laesst die img-src gleich', () => {
    const el = create(cam(true));
    const before = img(el)!.getAttribute('src');
    expect(before).toContain('/thumb/511671');
    setControl(cam(false));
    expect(img(el)!.getAttribute('src')).toBe(before);
  });

  it('Schnappschuss erfolgreich: src aendert sich', () => {
    const el = create(cam(true));
    const before = img(el)!.getAttribute('src');
    spyOn(Date, 'now').and.returnValue(Date.now() + 5000);
    byText(el, 'Schnappschuss').click();
    fixture.detectChanges();
    expect(service.snapshotCalls).toEqual(['511671']);
    expect(img(el)!.getAttribute('src')).not.toBe(before);
  });

  it('Schnappschuss scheitert: src gleich, Text „Schnappschuss fehlgeschlagen“', () => {
    service.snapshotResponse = throwError(() => new Error('x'));
    const el = create(cam(true));
    const before = img(el)!.getAttribute('src');
    byText(el, 'Schnappschuss').click();
    fixture.detectChanges();
    expect(img(el)!.getAttribute('src')).toBe(before);
    expect(el.textContent).toContain('Schnappschuss fehlgeschlagen');
  });

  it('Bildfehler zeigt Platzhalter; neuer Schnappschuss blendet ihn aus', () => {
    const el = create(cam(true));
    img(el)!.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(img(el)).toBeNull();
    expect(el.querySelector('.camera__placeholder')).not.toBeNull();
    spyOn(Date, 'now').and.returnValue(Date.now() + 5000);
    byText(el, 'Schnappschuss').click();
    fixture.detectChanges();
    expect(img(el)).not.toBeNull();
    expect(el.querySelector('.camera__placeholder')).toBeNull();
  });

  it('spaete Antwort nach Kamerawechsel wird ignoriert', () => {
    const pending = new Subject<void>();
    service.snapshotResponse = pending;
    const el = create(cam(true));
    byText(el, 'Schnappschuss').click();
    setControl(cam(true, '999'), '999');
    const srcOther = img(el)!.getAttribute('src');
    pending.error(new Error('x'));
    fixture.detectChanges();
    expect(el.textContent).not.toContain('Schnappschuss fehlgeschlagen');
    expect(img(el)!.getAttribute('src')).toBe(srcOther);
    expect(byText(el, 'Schnappschuss').disabled).toBeFalse();
  });

  it('Kamerawechsel setzt Dialog zurueck', () => {
    const el = create(cam(true));
    sw(el).click();
    fixture.detectChanges();
    setControl(cam(true, '999'), '999');
    expect(el.textContent).not.toContain('deaktivieren?');
  });
});
