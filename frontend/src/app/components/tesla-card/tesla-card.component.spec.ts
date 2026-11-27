import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { TeslaCardComponent } from './tesla-card.component';
import { TeslaService } from '../../services/tesla.service';
import { TeslaStatus } from '../../models/tesla.model';

describe('TeslaCardComponent', () => {
  let fixture: ComponentFixture<TeslaCardComponent>;
  let service: jasmine.SpyObj<TeslaService>;

  const locked: TeslaStatus = {
    configured: true,
    vehicle: { vin: 'V1', batteryLevel: 62, rangeKm: 310, locked: true, atHome: true }
  };

  function create(status: TeslaStatus): void {
    service.getStatus.and.returnValue(of(status));
    fixture = TestBed.createComponent(TeslaCardComponent);
    fixture.detectChanges();
  }

  function button(command: string): HTMLButtonElement {
    return fixture.nativeElement.querySelector(`[data-command="${command}"]`);
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<TeslaService>('TeslaService', ['getStatus', 'sendCommand', 'imageUrl']);
    service.imageUrl.and.callFake((v: string) => `/api/v1/tesla/vehicle/image?v=${v}`);
    TestBed.configureTestingModule({
      imports: [TeslaCardComponent],
      providers: [{ provide: TeslaService, useValue: service }]
    });
  });

  it('bleibt unsichtbar ohne Tessie-Zugang', () => {
    create({ configured: false });
    expect(fixture.nativeElement.querySelector('.tesla-card')).toBeNull();
  });

  it('zeigt die Zeichnung ohne Fahrzeugbild', () => {
    create(locked);
    expect(fixture.nativeElement.querySelector('.tesla-card__image')).toBeNull();
    expect(fixture.nativeElement.querySelector('.tesla-card__svg')).not.toBeNull();
  });

  it('zeigt das echte Bild und fällt bei einem Ladefehler auf die Zeichnung zurück', () => {
    create({ ...locked, imageVersion: 'abc123' });
    const img = fixture.nativeElement.querySelector('.tesla-card__image') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/api/v1/tesla/vehicle/image?v=abc123');

    img.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.tesla-card__image')).toBeNull();
    expect(fixture.nativeElement.querySelector('.tesla-card__svg')).not.toBeNull();
  });

  it('zeigt Akku, Reichweite und Zuhause', () => {
    create(locked);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('62');
    expect(text).toContain('310 km');
    expect(text).toContain('Zuhause');
  });

  it('sendet Klima direkt', () => {
    create(locked);
    service.sendCommand.and.returnValue(of(locked));
    button('START_CLIMATE').click();
    expect(service.sendCommand).toHaveBeenCalledWith('START_CLIMATE');
  });

  it('Entriegeln fragt nach und prüft vorher frisch', () => {
    create(locked);
    service.sendCommand.and.returnValue(of(locked));
    button('UNLOCK').click();
    fixture.detectChanges();
    expect(service.sendCommand).not.toHaveBeenCalled();
    const confirm = fixture.nativeElement.querySelector('.tesla-card__btn--danger') as HTMLButtonElement;
    confirm.click();
    expect(service.getStatus).toHaveBeenCalledTimes(2);
    expect(service.sendCommand).toHaveBeenCalledWith('UNLOCK');
  });

  it('entriegelt nicht, wenn das Auto inzwischen offen ist', () => {
    create(locked);
    button('UNLOCK').click();
    fixture.detectChanges();
    service.getStatus.and.returnValue(of({ configured: true, vehicle: { vin: 'V1', locked: false } }));
    (fixture.nativeElement.querySelector('.tesla-card__btn--danger') as HTMLButtonElement).click();
    expect(service.sendCommand).not.toHaveBeenCalled();
  });

  it('zeigt einen Befehlsfehler', () => {
    create(locked);
    service.sendCommand.and.returnValue(throwError(() => new Error('Auto schläft')));
    button('OPEN_CHARGE_PORT').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Auto schläft');
  });

  it('behält den letzten Stand bei einem fehlgeschlagenen Abruf', fakeAsync(() => {
    create(locked);
    service.getStatus.and.returnValue(throwError(() => new Error('weg')));
    tick(TeslaCardComponent.REFRESH_MS);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('310 km');
    discardPeriodicTasks();
  }));
});
